package com.nuvio.app.features.usenet

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** Executes the APK-installed PIE engine from nativeLibraryDir as a child process. */
internal class AndroidUsenetEngineHost(private val context: Context) : UsenetEngineHost {
    private var process: Process? = null

    private val reader = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "usenet-sidecar-io").apply { isDaemon = true }
    }

    override val nzbCacheDir: String
        get() = File(context.cacheDir, UsenetSidecar.NZB_CACHE_DIR).absolutePath

    /**
     * Go cannot consistently locate Android's APEX trust store across OS versions: the system trust
     * manager's public CA certificates are handed over instead.
     */
    override fun certificates(): List<String> {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val certificates = (factory.trustManagers.first { it is X509TrustManager } as X509TrustManager).acceptedIssuers
        return certificates.map { certificate ->
            "-----BEGIN CERTIFICATE-----\n" + Base64.encodeToString(certificate.encoded, Base64.NO_WRAP) + "\n-----END CERTIFICATE-----\n"
        }
    }

    override fun start(bootstrap: String, mark: (String) -> Unit): String {
        val binary = File(context.applicationInfo.nativeLibraryDir, "libnuvio_usenet.so")
        if (!binary.canExecute()) {
            throw UsenetPreparationException("The Usenet engine is missing from this build.", UsenetPreparationException.Scope.ENGINE)
        }
        // Go defaults to MADV_FREE on Android, so memory the engine returns after a session stays
        // in its RSS until the kernel is under pressure. GODEBUG is read before main, hence set here.
        val child = ProcessBuilder(binary.absolutePath)
            .apply { environment()["GODEBUG"] = "madvdontneed=1" }
            .start()
        mark("sidecar_spawned")
        process = child
        reader.execute { drainQuietly(child.errorStream) }
        // Keep stdin open until stop. Parent death closes the pipe and causes the Go process
        // to terminate even if nothing else ran.
        child.outputStream.write((bootstrap + "\n").toByteArray())
        child.outputStream.flush()
        mark("sidecar_bootstrap_sent")
        val ready = reader.submit<String> {
            child.inputStream.bufferedReader().readLine() ?: throw IOException("Usenet engine exited before startup")
        }
        try {
            return ready.get(15, TimeUnit.SECONDS)
        } catch (e: Exception) {
            ready.cancel(true)
            throw e
        }
    }

    override fun isRunning(): Boolean = process?.let(::isRunning) == true

    override fun stop() {
        val child = process ?: return
        process = null
        runCatching { child.outputStream.close() }
        // Process.isAlive and timed waitFor require API 26; Android destroy sends SIGKILL.
        runCatching {
            val deadline = SystemClock.elapsedRealtime() + 2000
            while (isRunning(child) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20)
            if (isRunning(child)) child.destroy()
        }
        runCatching { child.inputStream.close() }; runCatching { child.errorStream.close() }
    }

    override fun clearNzbCache() {
        File(nzbCacheDir).listFiles()?.forEach { it.delete() }
    }

    private companion object {
        fun isRunning(child: Process): Boolean =
            try { child.exitValue(); false } catch (_: IllegalThreadStateException) { true }

        /**
         * Reads the engine's stderr until it ends, keeping none of it: no credentials in Android logs.
         * Stopping the engine closes the stream under this read, which then throws.
         */
        fun drainQuietly(input: InputStream) {
            try {
                input.use { val bytes = ByteArray(4096); while (it.read(bytes) >= 0) Unit }
            } catch (_: IOException) {
            }
        }
    }
}
