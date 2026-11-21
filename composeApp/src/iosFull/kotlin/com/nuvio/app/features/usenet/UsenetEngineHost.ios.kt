@file:OptIn(ExperimentalForeignApi::class)

package com.nuvio.app.features.usenet

import com.nuvio.app.features.usenet.native.nuvio_usenet_free
import com.nuvio.app.features.usenet.native.nuvio_usenet_start
import com.nuvio.app.features.usenet.native.nuvio_usenet_stop
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.connect
import platform.posix.sockaddr_in
import platform.posix.socket

/**
 * Runs the engine as a library inside the app, which iOS requires: no app may start a process. The
 * engine serves the same loopback HTTP API as the Android sidecar.
 */
internal class IosUsenetEngineHost : UsenetEngineHost {
    private var running = false
    private var port = 0

    override val nzbCacheDir: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
        "$caches/${UsenetSidecar.NZB_CACHE_DIR}"
    }

    /** Go reads the system's trust store on iOS. */
    override fun certificates(): List<String> = emptyList()

    override fun start(bootstrap: String, mark: (String) -> Unit): String {
        mark("sidecar_bootstrap_sent")
        val answer = nuvio_usenet_start(bootstrap.cstr) ?: error("The Usenet engine gave no answer")
        val record = try { answer.toKString() } finally { nuvio_usenet_free(answer) }
        port = PORT.find(record)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        running = port > 0
        return record
    }

    /**
     * Whether the engine still listens. iOS may take a suspended app's listening socket; the engine
     * is then started again for the next stream.
     */
    override fun isRunning(): Boolean = running && listening(port)

    override fun stop() {
        if (!running) return
        running = false
        port = 0
        nuvio_usenet_stop()
    }

    override fun clearNzbCache() {
        val files = NSFileManager.defaultManager
        files.contentsOfDirectoryAtPath(nzbCacheDir, null)?.forEach { name ->
            files.removeItemAtPath("$nzbCacheDir/$name", null)
        }
    }

    private fun listening(port: Int): Boolean = memScoped {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        if (fd < 0) return@memScoped true
        try {
            val address = alloc<sockaddr_in>()
            address.sin_len = sizeOf<sockaddr_in>().convert()
            address.sin_family = AF_INET.convert()
            // Network byte order on these little-endian devices.
            address.sin_port = (((port and 0xff) shl 8) or ((port shr 8) and 0xff)).convert()
            address.sin_addr.s_addr = 0x0100007Fu // 127.0.0.1
            connect(fd, address.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0
        } finally {
            close(fd)
        }
    }

    private companion object {
        val PORT = Regex("\"port\"\\s*:\\s*(\\d+)")
    }
}
