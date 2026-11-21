package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamSubtitle
import com.nuvio.app.features.streams.epochMs
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Runs the engine for [UsenetSidecar]: a child process on Android, a library inside the app on iOS,
 * where an app cannot start one. Either way the engine serves its loopback HTTP API.
 */
internal interface UsenetEngineHost {
    /** Where the engine keeps downloaded NZBs. */
    val nzbCacheDir: String

    /** PEM certificates the engine is to trust; empty when it reads the system's itself. */
    fun certificates(): List<String>

    /**
     * Starts the engine with [bootstrap], the record it reads at startup, and returns its readiness
     * record, {"protocol":1,"port":N}. Throws when it does not start; [mark] names startup steps.
     * Blocks the calling thread.
     */
    fun start(bootstrap: String, mark: (String) -> Unit): String

    fun isRunning(): Boolean

    /** Stops the engine; its sessions and sockets go with it. */
    fun stop()

    fun clearNzbCache()
}

/**
 * Opens Usenet streams in the engine and owns its sessions. All media remains in the engine.
 * Provider secrets only cross an authenticated loopback control request.
 */
internal class UsenetSidecar(
    private val host: UsenetEngineHost,
    private val settings: () -> UsenetConfiguration,
    private val unplayable: UnplayableReleases,
    /** Counts NZB downloads, outcomes and what each provider did; null in unit tests that do not look. */
    private val stats: UsenetSourceStats? = null,
) {
    private val mutex = Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = createUsenetHttpClient(socketTimeoutMillis = 120_000, requestTimeoutMillis = 150_000)
    private var processProfile: String? = null
    private var endpoint: String? = null
    private var token: String? = null
    private var sessionId: String? = null
    @Volatile private var playbackUrl: String? = null
    private var idleStop: Job? = null
    private val retiredSessions = mutableSetOf<String>() // guarded by mutex

    /** A live session's servers, in the order its open sent them, and what it plays. */
    private class SessionStats(val servers: List<String>, val kind: ContentKind)

    /** By session id; the engine reports each session's providers when it closes. Guarded by mutex. */
    private val sessionStats = mutableMapOf<String, SessionStats>()
    private var retirementJob: Job? = null
    private val preparationGuard = SynchronizedObject()
    private var preparationJob: Job? = null
    private var preparationOwner: Any? = null
    private var preparationKey: PreparationKey? = null
    private var preparationGeneration: Any? = null
    // A prefetched session waiting for its Play. Guarded by mutex; the session itself holds the
    // parsed NZB and cached articles, so nothing is kept twice.
    private var preparedStream: StreamItem? = null
    private var preparedKey: PreparationKey? = null
    private var preparedGeneration: Any? = null
    private var preparedExpiry: Job? = null

    /** What makes a prepared session the one a later Play asks for. */
    private data class PreparationKey(
        val nzb: String?, val servers: List<String>, val headers: Map<String, String>,
        val fileIdx: Int?, val selector: String?, val season: Int?, val episode: Int?,
        val configuration: UsenetConfiguration, val profileId: Int?,
    )

    private fun preparationKey(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): PreparationKey {
        val configuration = settings()
        return PreparationKey(stream.nzbUrl, stream.nzbServers, nzbHeaders(stream, configuration), stream.fileIdx,
            stream.fileMustInclude, season, episode, configuration, profileId)
    }

    /** The release each recent engine URL plays, so playback outcomes reach its entry. */
    private val playingLock = SynchronizedObject()
    private val playing = LruMap<String, StreamItem>(8)

    /** Open timings by engine URL, logged once the first frame shows. */
    private val tracesLock = SynchronizedObject()
    private val traces = LruMap<String, Trace>(4)

    private class Trace(val fast: Boolean, val fastNzb: Boolean) {
        private val lock = SynchronizedObject()
        private val start = TimeSource.Monotonic.markNow()
        val recordedAt = epochMs()
        private val marks = LinkedHashMap<String, Long>()
        var engine: JsonObject = JsonObject(emptyMap())

        fun mark(name: String) = synchronized(lock) {
            if (name !in marks) marks[name] = start.elapsedNow().inWholeMilliseconds
        }

        fun value(name: String, value: Long) = synchronized(lock) {
            if (name !in marks) marks[name] = value
        }

        fun marksJson(): JsonObject = synchronized(lock) { JsonObject(marks.mapValues { JsonPrimitive(it.value) }) }
    }

    /** The player reached step [name] with the engine URL [url]; only its first time counts. */
    fun markStartup(url: String, name: String) {
        synchronized(tracesLock) { traces[url] }?.mark(name)
    }

    /** A measured [value] for the startup of [url], such as how long the video decoder took to set up. */
    fun markStartupValue(url: String, name: String, value: Long) {
        synchronized(tracesLock) { traces[url] }?.value(name, value)
    }

    /** Shares the session mutex so launch and Play cannot start duplicate engines. */
    fun prewarm(): Job = cleanup.launch {
        mutex.withLock {
            if (sessionId != null) return@withLock
            if (!appForeground || !settings().prewarmOnLaunch) {
                stopLocked()
                return@withLock
            }
            try {
                start()
                // Visibility can change while the blocking bootstrap runs.
                if (!appForeground) stopLocked()
            } catch (_: Exception) {
                // Optional startup must not crash browsing; Play retries and reports the error.
                stopLocked()
            }
        }
    }

    /** Results have made Usenet relevant. Warm only Go and its trust roots, for two idle minutes. */
    fun prewarmForSources(): Job = cleanup.launch {
        mutex.withLock {
            if (!appForeground || sessionId != null) return@withLock
            try {
                start()
                if (!appForeground) { stopLocked(); return@withLock }
                if (!settings().prewarmOnLaunch) {
                    idleStop = cleanup.launch {
                        delay(120_000)
                        mutex.withLock { if (sessionId == null) stopLocked() }
                    }
                }
            } catch (_: Exception) { stopLocked() }
        }
    }

    /**
     * Opens [stream] now and keeps it ready for two minutes, so its Play starts from an open
     * session. Only one candidate may own a prepared session; [owner] is the screen asking.
     */
    fun prefetch(owner: Any, stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): Job {
        val key = preparationKey(stream, season, episode, profileId)
        if (!key.configuration.prefetchResults) return Job().apply { complete() }
        return synchronized(preparationGuard) {
            if (preparationOwner === owner && preparationKey == key) return@synchronized preparationJob!!
            preparationJob?.cancel()
            preparationOwner = owner
            preparationKey = key
            val generation = Any()
            preparationGeneration = generation
            cleanup.launch {
                mutex.withLock {
                    // A session that plays is never replaced by a guess.
                    if (!appForeground || !settings().prefetchResults || (sessionId != null && preparedStream == null)) {
                        return@withLock
                    }
                    if (preparedStream != null) releaseSessionLocked()
                    try {
                        val resolved = openLocked(stream, season, episode, profileId)
                        preparedStream = resolved
                        preparedKey = key
                        preparedGeneration = generation
                        preparedExpiry = cleanup.launch {
                            delay(PREPARED_LIFETIME_MS)
                            mutex.withLock {
                                if (preparedGeneration === generation) {
                                    preparedExpiry = null
                                    releaseSessionLocked()
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // Optional preparation is silent; Play retries it and reports the error.
                    }
                }
            }.also { preparationJob = it }
        }
    }

    /** Drops the prepared session of [owner], or of anyone when null; a session that plays stays. */
    fun cancelPrefetch(owner: Any? = null): Job {
        val generation = synchronized(preparationGuard) {
            if (owner != null && preparationOwner !== owner) return Job().apply { complete() }
            preparationJob?.cancel()
            preparationJob = null; preparationOwner = null; preparationKey = null
            preparationGeneration.also { preparationGeneration = null }
        }
        return cleanup.launch {
            mutex.withLock { if (generation != null && preparedGeneration === generation) releaseSessionLocked() }
        }
    }

    suspend fun resolve(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): StreamItem {
        var resolved: StreamItem? = null
        try {
            return withContext(Dispatchers.IO) {
                resolveLocked(stream, season, episode, profileId).also { resolved = it }
            }
        } catch (cancelled: CancellationException) {
            // withContext can discard a successful result when its caller is cancelled during
            // dispatcher handoff. No screen can own it then.
            release(resolved?.url)
            throw cancelled
        }
    }

    private suspend fun resolveLocked(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): StreamItem {
        val key = preparationKey(stream, season, episode, profileId)
        val pending = synchronized(preparationGuard) {
            if (preparationKey != key) preparationJob?.cancel()
            preparationJob
        }
        // Joined outside the session mutex, so Play shares an open that is already running.
        pending?.join()
        return mutex.withLock {
            synchronized(preparationGuard) {
                if (preparationJob === pending) {
                    preparationJob = null; preparationOwner = null; preparationKey = null; preparationGeneration = null
                }
            }
            val cached = preparedStream
            if (cached != null && preparedKey == key && host.isRunning()) {
                clearPreparedLocked()
                cached.url?.let { url -> synchronized(tracesLock) { traces[url] }?.mark("prepared_session_reused") }
                return@withLock cached
            }
            if (preparedStream != null) releaseSessionLocked()
            openLocked(stream, season, episode, profileId)
        }
    }

    /**
     * The engine downloads the NZB with these. An addon's User-Agent takes precedence over the
     * download setting; searches have their own independent setting.
     */
    private fun nzbHeaders(stream: StreamItem, configuration: UsenetConfiguration): Map<String, String> {
        val headers = stream.behaviorHints.proxyHeaders?.request.orEmpty()
        return if (headers.keys.any { it.equals(USER_AGENT, ignoreCase = true) }) headers
        else headers + (USER_AGENT to configuration.downloadAgent)
    }

    private suspend fun openLocked(stream: StreamItem, season: Int?, episode: Int?, profileId: Int?): StreamItem {
        val configuration = settings()
        val trace = Trace(configuration.fastMkvStartup, configuration.fastNzbFetch)
        var previousId = sessionId
        var openedId: String? = null
        return try {
            trace.mark("resolve_lock_acquired")
            start(trace)
            previousId = sessionId
            trace.mark("engine_ready")
            val request = buildJsonObject {
                put("keepSessionId", previousId ?: "")
                // The NZB URL carries the indexer key, so profiles of one account share its cache
                // entry and a profile switch does not download it again.
                put("cacheScope", "unscoped")
                put("legacyCacheScope", profileId?.toString() ?: "unscoped")
                put("nzbUrl", stream.nzbUrl)
                put("servers", JsonArray(stream.nzbServers.map(::JsonPrimitive)))
                stream.fileIdx?.let { put("fileIdx", it) }
                stream.fileMustInclude?.let { put("fileMustInclude", it) }
                season?.let { put("season", it) }
                episode?.let { put("episode", it) }
                put("headers", JsonObject(nzbHeaders(stream, configuration).mapValues { JsonPrimitive(it.value) }))
                putJsonObject("config") {
                    put("profile", configuration.profile)
                    put("readAhead", configuration.readAhead)
                    put("maxConnections", configuration.maxConnections)
                    put("fastMkvStartup", configuration.fastMkvStartup)
                    put("fastNzbFetch", configuration.fastNzbFetch)
                    put("cacheNzb", configuration.cacheNzb)
                    put("allowPrivateNetwork", configuration.allowPrivateNetwork)
                    put("holeFilling", configuration.holeFilling)
                    put("maxMissingArticles", configuration.maxMissingArticles)
                    put("maxConsecutiveMissing", configuration.maxConsecutiveMissing)
                }
            }
            trace.mark("session_request")
            val result = Json.parseToJsonElement(control("/sessions", request)).jsonObject
            trace.mark("session_response")
            val newId = result.getValue("id").jsonPrimitive.content
            val newPath = result.getValue("path").jsonPrimitive.content
            require(newId.matches(Regex("[0-9a-f]{48}")) && newPath.startsWith("/stream/$newId/"))
            openedId = newId
            // The engine reconciled abandoned sessions before this open.
            retiredSessions.clear()
            val engineUrl = requireNotNull(endpoint)
            val url = UsenetEngineUrls.stream(engineUrl, newId, newPath)
            val nativeSubtitles = (result["subtitles"] as? JsonArray)?.map { element ->
                val item = element.jsonObject
                val subtitleId = item.getValue("id").jsonPrimitive.content
                require(subtitleId.toIntOrNull() != null)
                val filename = item.optString("filename")
                StreamSubtitle(
                    url = "$engineUrl/subtitle/$newId/$subtitleId/${filename.encodeURLParameter()}",
                    language = item.optString("lang", "und"),
                    name = filename.takeIf { it.isNotBlank() },
                )
            }.orEmpty()
            trace.mark("resolved")
            // The NZB stays as the stream's identity; with a URL it is no longer prepared again.
            // The servers carry credentials and are not needed any more.
            val resolved = stream.copy(
                url = url,
                nzbServers = emptyList(),
                externalUrl = null,
                infoHash = null,
                externalSubtitles = stream.externalSubtitles + nativeSubtitles,
                behaviorHints = stream.behaviorHints.copy(
                    proxyHeaders = null,
                    filename = result.getValue("filename").jsonPrimitive.content,
                    videoSize = result.getValue("size").jsonPrimitive.long,
                ),
            )
            // Validate the entire response before replacing a working session.
            currentCoroutineContext().ensureActive()
            sessionId = newId
            playbackUrl = url
            sessionStats[newId] = SessionStats(stream.nzbServers, if (episode != null) ContentKind.SHOW else ContentKind.MOVIE)
            synchronized(playingLock) { playing[url] = stream }
            trace.engine = result["startup"] as? JsonObject ?: JsonObject(emptyMap())
            // Only an NZB the engine says it fetched counts as a download from the indexer.
            val nzbLookup = (trace.engine["nzbCache"] as? JsonObject)?.optString("lookup")
            if (nzbLookup == "miss" || nzbLookup == "disabled") stats?.grabbed(stream.nzbUrl)
            synchronized(tracesLock) { traces[url] = trace }
            previousId?.let(::retireSessionLocked)
            resolved
        } catch (e: Exception) {
            // Prefetches open here too, so a release found unplayable before it was chosen counts.
            (e as? UsenetPreparationException)?.failure?.let {
                unplayable.record(stream, it)
                if (it in UnplayableReleases.KINDS) stats?.unavailable(stream.nzbUrl)
            }
            // An open the engine refused never becomes a session to close: what its providers did,
            // such as the articles they lacked, came with the refusal.
            (e as? UsenetPreparationException)?.providers?.let { report ->
                runCatching {
                    stats?.providerSession(stream.nzbServers, if (episode != null) ContentKind.SHOW else ContentKind.MOVIE,
                        report, refused = true, failure = e.failure)
                }
            }
            if (e !is CancellationException) runCatching {
                Logger.withTag("UsenetPrepare").i {
                    "open failed source=\"${stream.name}\" release=\"${stream.title?.lineSequence()?.firstOrNull()}\" " +
                        "kind=${(e as? UsenetPreparationException)?.failure ?: "-"} error=${e::class.simpleName}: " +
                        redacted(e.message.orEmpty())
                }
            }
            if (previousId != null && sessionId == previousId && host.isRunning()) {
                openedId?.let(::retireSessionLocked)
            } else {
                stopLocked()
            }
            throw e
        }
    }

    /**
     * Retry bounded cleanup without delaying playback. A later open also reconciles these IDs,
     * including lost open responses.
     */
    private fun retireSessionLocked(id: String) {
        retiredSessions += id
        if (retirementJob?.isActive == true) return
        retirementJob = cleanup.launch {
            repeat(3) { attempt ->
                if (attempt > 0) delay(1_000L * attempt)
                mutex.withLock {
                    for (retired in retiredSessions.toList()) {
                        val answer = runCatching {
                            withTimeoutOrNull(2_000L) { control("/sessions/$retired", null, delete = true) }
                        }.getOrNull()
                        if (answer != null) {
                            retiredSessions.remove(retired)
                            reportSessionLocked(retired, answer)
                        }
                    }
                }
            }
        }
    }

    /** The release behind [url] showed its first frame: remember it, and log how the open went. */
    fun firstFrame(url: String) {
        synchronized(playingLock) { playing[url] }?.let {
            unplayable.recordPlayed(it)
            stats?.played(it.nzbUrl)
        }
        val trace = synchronized(tracesLock) { traces.remove(url) } ?: return
        trace.mark("first_frame")
        cleanup.launch {
            val engine = mutex.withLock {
                val id = sessionId?.takeIf { url == playbackUrl } ?: return@withLock null
                runCatching {
                    // Diagnostics are optional and this holds the session lock: never wait long.
                    val response = http.get("$endpoint/sessions/$id/diagnostics") {
                        header("Authorization", "Bearer $token")
                        timeout { requestTimeoutMillis = 5_000 }
                    }
                    if (response.status.isSuccess()) Json.parseToJsonElement(response.bodyAsText()).jsonObject else null
                }.getOrNull()
            } ?: trace.engine
            val marks = trace.marksJson()
            runCatching {
                Logger.withTag("UsenetStartup").i {
                    buildJsonObject {
                        put("marksMs", marks)
                        engine["marksMs"]?.let { put("engineMarksMs", it) }
                    }.toString()
                }
            }
            runCatching {
                UsenetStartupReports.save(
                    buildJsonObject {
                        put("fastMkvStartup", trace.fast)
                        put("fastNzbFetch", trace.fastNzb)
                        put("recordedAtMs", trace.recordedAt)
                        put("marksMs", marks)
                        put("engine", engine)
                    }.toString(),
                )
            }
        }
    }

    /** Playback of [url] failed with the engine's [failure] kind; repeatable kinds are remembered. */
    fun playbackFailed(url: String, failure: String?) {
        synchronized(playingLock) { playing[url] }?.let {
            unplayable.record(it, failure)
            if (failure in UnplayableReleases.KINDS) stats?.unavailable(it.nzbUrl)
        }
    }

    /**
     * Only release the session owned by the exiting player. An older screen must not tear down a
     * newer playback session during navigation.
     */
    fun release(url: String?) {
        cleanup.launch { mutex.withLock { if (url != null && url == playbackUrl) releaseSessionLocked() } }
    }

    private suspend fun releaseSessionLocked() {
        clearPreparedLocked()
        val id = sessionId
        sessionId = null; playbackUrl = null
        try {
            if (id != null) {
                val answer = withTimeoutOrNull(2_000L) { control("/sessions/$id", null, delete = true) }
                if (answer == null) { stopLocked(); return }
                reportSessionLocked(id, answer)
            }
        } catch (_: Exception) { stopLocked(); return }
        if (!appForeground) { stopLocked(); return }
        idleStop?.cancel()
        if (settings().prewarmOnLaunch) { idleStop = null; return }
        // Keep the initialized Go runtime and trust roots across episode changes, while dropping
        // all provider sockets, credentials and article buffers.
        idleStop = cleanup.launch { delay(30_000); mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    /** Adds what a closed session's providers did, as the engine answered its close, to their statistics. */
    private fun reportSessionLocked(id: String, answer: String) {
        val session = sessionStats.remove(id) ?: return
        runCatching { stats?.providerSession(session.servers, session.kind, answer) }
    }

    fun onAppBackgrounded() {
        cancelPrefetch()
        cleanup.launch { mutex.withLock { if (sessionId == null) stopLocked() } }
    }

    private fun clearPreparedLocked() {
        preparedExpiry?.cancel(); preparedExpiry = null
        preparedStream = null; preparedKey = null
        preparedGeneration = null
    }

    fun settingsChanged(): Job {
        // An unused prepared session was opened with the old settings; playback keeps its own.
        val invalidation = cancelPrefetch()
        return cleanup.launch {
            invalidation.join()
            mutex.withLock {
                if (!settings().cacheNzb) runCatching { host.clearNzbCache() }
            }
            prewarm().join()
        }
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun start(trace: Trace? = null) {
        idleStop?.cancel(); idleStop = null
        val cfg = settings()
        // A settings change must not restart the engine underneath the playing source while its
        // replacement is still being prepared. The new memory target applies once no session owns it.
        if (host.isRunning() && endpoint != null &&
            (processProfile == cfg.profile || sessionId != null)) {
            trace?.mark("sidecar_reused")
            return
        }
        trace?.mark("sidecar_starting")
        stopLocked()
        // From the platform's secure random generator.
        val secret = Uuid.random().toHexString() + Uuid.random().toHexString()
        val bootstrap = buildJsonObject {
            put("token", secret)
            put("nzbCacheDir", host.nzbCacheDir)
            put("memoryMiB", when (cfg.profile) {
                UsenetConfiguration.PROFILE_LOW_MEMORY -> 96
                UsenetConfiguration.PROFILE_THROUGHPUT -> 224
                else -> 144
            })
            put("certificates", JsonArray(host.certificates().map(::JsonPrimitive)))
        }.toString()
        try {
            processProfile = cfg.profile
            val record = Json.parseToJsonElement(host.start(bootstrap) { trace?.mark(it) }).jsonObject
            require(record.getValue("protocol").jsonPrimitive.int == 1)
            val port = record.getValue("port").jsonPrimitive.int; require(port in 1..65535)
            endpoint = "http://127.0.0.1:$port"; token = secret
            UsenetEngineUrls.remember(port)
            trace?.mark("sidecar_ready")
        } catch (e: UsenetPreparationException) {
            stopLocked()
            throw e
        } catch (_: Exception) {
            stopLocked()
            throw UsenetPreparationException("The Usenet engine could not start.", UsenetPreparationException.Scope.ENGINE)
        }
    }

    private suspend fun control(path: String, payload: JsonObject?, delete: Boolean = false): String {
        val response = try {
            http.request("$endpoint$path") {
                method = if (delete) HttpMethod.Delete else HttpMethod.Post
                header("Authorization", "Bearer $token")
                if (!delete) setBody(TextContent(payload.toString(), ContentType.Application.Json))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw UsenetPreparationException("Could not reach the Usenet engine.", UsenetPreparationException.Scope.ENGINE)
        }
        val text = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            throw IOException("The Usenet engine stopped answering.")
        }
        if (response.status.isSuccess()) return text
        val failure = response.headers["X-Usenet-Failure"]
        val scope = when (failure) {
            "configuration" -> UsenetPreparationException.Scope.ENGINE
            "provider" -> UsenetPreparationException.Scope.PROVIDER
            // Busy can be the previous cancelled open still unwinding.
            else -> UsenetPreparationException.Scope.SOURCE
        }
        throw UsenetPreparationException(
            text.take(240).ifBlank { "The Usenet engine answered HTTP ${response.status.value}." }, scope, failure,
            providers = response.headers["X-Usenet-Providers"],
        )
    }

    private fun stopLocked() {
        clearPreparedLocked()
        retirementJob?.cancel(); retirementJob = null
        retiredSessions.clear()
        // A stopped engine reports nothing more for its sessions.
        sessionStats.clear()
        idleStop?.cancel(); idleStop = null
        processProfile = null; endpoint = null; token = null; sessionId = null; playbackUrl = null
        runCatching { host.stop() }
    }

    companion object {
        const val NZB_CACHE_DIR = "usenet-nzb"
        private const val PREPARED_LIFETIME_MS = 120_000L

        /** [message] with each URL cut to its scheme and host: no path, query, key or login reaches the log. */
        internal fun redacted(message: String): String = redactUrls(message)

        @Volatile var appForeground = true

        /** As org.json's optString: the value as text, or [fallback] when it is absent. */
        private fun JsonObject.optString(name: String, fallback: String = ""): String =
            (this[name] as? JsonPrimitive)?.content ?: fallback
    }
}
