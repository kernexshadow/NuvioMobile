package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.epochMs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.Headers
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.utils.io.readRemaining
import kotlin.time.TimeSource
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.io.IOException
import kotlinx.io.readByteArray

/**
 * The HTTP client Usenet requests use: it follows redirects, connects within 10 s and waits up to
 * [socketTimeoutMillis] for data and [requestTimeoutMillis] for a whole request. API keys are in
 * query strings and provider credentials in engine requests, so it has no URL logging, breadcrumbs
 * or disk cache.
 */
internal expect fun createUsenetHttpClient(socketTimeoutMillis: Long = 20_000, requestTimeoutMillis: Long = 25_000): HttpClient

/** Requests to an indexer are paused until [until] because it reported a limit. */
internal class IndexerCooldownException(val until: Long) : IllegalStateException("Indexer limit reached")

/** The indexer answered with an HTTP error status and no Newznab error that says more. */
internal class IndexerHttpException(val code: Int) : IllegalStateException("Indexer HTTP $code")

/**
 * True when the indexer refused the search itself (Newznab 200-203, or an HTTP 4xx), which asking
 * again would not change. Not a timeout, an unreachable indexer, a server error or a cooldown, which
 * may pass; nor an authentication failure, 408 or 429.
 */
internal fun isSearchRejection(e: Exception): Boolean = e is NewznabUnsupportedSearchException ||
    (e is IndexerHttpException && e.code in 400..499 && e.code !in setOf(401, 403, 408, 429))

internal class NewznabClient(
    storage: NewznabStateStorage,
    /** Counts every request; null in unit tests that do not look. */
    private val stats: UsenetSourceStats? = null,
    /** The User-Agent of the next request; settings can change it at any time. */
    private val agent: () -> String = { NUVIO_USER_AGENT },
) {

    private val client = createUsenetHttpClient()
    private val states = NewznabIndexerStates(storage, ::now)
    private val seasonsLock = SynchronizedObject()
    /** Least recently used first. */
    private val seasons = LinkedHashMap<String, Pair<Long, List<UsenetRelease>>>()

    /** Caps cost an API hit on most indexers and rarely change; [live] skips the cache and any cooldown. */
    suspend fun capabilities(indexer: UsenetIndexer, live: Boolean = false): NewznabCapabilities {
        val cached = states.get(indexer)?.takeIf { it.caps != null }
        if (!live && cached != null && now() - cached.capsFetchedAt < CAPS_TTL_MS) return cached.caps!!
        val caps = try {
            request(indexer, NewznabProtocol.apiUrl(indexer, "caps").build(), live, parse = NewznabProtocol::capabilities)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // An expired copy still describes the indexer better than failing the search.
            if (!live && cached != null) return cached.caps!! else throw e
        }
        states.update(indexer) { it.copy(caps = caps, capsFetchedAt = now()) }
        return caps
    }

    /**
     * Reads up to [results] items of each search, or every page when zero. An episode is always searched on its own: a
     * long-running series has more releases per season than a search reads, which are its newest
     * uploads, of any episode. The season search adds what an episode search never returns, the
     * season packs, and the episode's numbered releases that an indexer filed without the episode.
     * It runs alongside the episode's, which its pages would otherwise hold up.
     */
    suspend fun search(indexer: UsenetIndexer, request: UsenetSearchRequest, caps: NewznabCapabilities,
        results: Int = UsenetSourceConfiguration.DEFAULT_INDEXER_RESULTS): List<UsenetRelease> = coroutineScope {
        val season = async {
            try {
                Result.success(seasonReleases(indexer, request, caps, results).orEmpty()
                    .filter { NewznabProtocol.matches(it, request, idSearch = true, requireNumbering = true) })
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Result.failure(e) }
        }
        val url = NewznabProtocol.searchUrl(indexer, request, caps) ?: return@coroutineScope season.await().getOrThrow()
        val episode = try {
            pages(indexer, url, request, caps, results)
                .filter { NewznabProtocol.matches(it, request, NewznabProtocol.isIdSearch(url)) }
        } catch (e: CancellationException) { throw e }
        // When the season answered, a failed or paused episode search still shows its releases.
        catch (e: Exception) { return@coroutineScope season.await().getOrNull()?.takeIf { it.isNotEmpty() } ?: throw e }
        // A failed season search leaves only the packs out.
        (episode + season.await().getOrDefault(emptyList())).distinctBy { it.nzbUrl }
    }

    /**
     * Checks the endpoint and the API key with live requests. Caps alone prove nothing about
     * the key, since many indexers serve them publicly; a one-result search uses it.
     */
    suspend fun verify(indexer: UsenetIndexer) {
        capabilities(indexer, live = true)
        val latest = NewznabProtocol.apiUrl(indexer, "search").setQueryParameter("limit", "1").build()
        // A search refused for its parameters still means the key was accepted; a page that is
        // no Newznab answer (a login page served with the public caps) does not.
        request(indexer, latest, ignoreCooldown = true, parse = NewznabProtocol::requireSearchAnswer)
    }

    /**
     * Watching a series asks for one episode after another, and a single season
     * search gives them all its packs for [SEASON_TTL_MS]. Memory only: release
     * URLs carry the API key.
     */
    private suspend fun seasonReleases(indexer: UsenetIndexer, request: UsenetSearchRequest,
        caps: NewznabCapabilities, results: Int): List<UsenetRelease>? {
        val url = NewznabProtocol.searchUrl(indexer, request, caps, wholeSeason = true) ?: return null
        // A season read for fewer results than asked for is read again.
        val key = "$url#$results"
        // Keyed by the endpoint and key, which another profile or a renamed indexer can share:
        // reused releases take the asking indexer's id and name, which ordering and deduplication use.
        synchronized(seasonsLock) { seasons.remove(key)?.also { seasons[key] = it } }?.takeIf { now() - it.first < SEASON_TTL_MS }?.let { cached ->
            return cached.second.map { it.copy(indexerId = indexer.id, indexerName = indexer.name) }
        }
        val releases = try {
            pages(indexer, url, request, caps, results, wholeSeason = true)
        } catch (e: CancellationException) { throw e }
        // Some indexers reject season-only searches; remember it so each episode does not retry.
        // Any other failure may pass, so it is not remembered: the next episode asks again.
        catch (e: Exception) { if (isSearchRejection(e)) emptyList() else throw e }
        synchronized(seasonsLock) {
            seasons.remove(key)
            seasons[key] = now() to releases
            if (seasons.size > MAX_SEASONS) seasons.remove(seasons.keys.first())
        }
        return releases
    }

    /**
     * Bounds fetching to [results] items, or reads every page when zero; never downloads NZBs. Indexers
     * leave items out of a page while more follow, so the total they report says whether to ask for
     * the next; without one, a full page does. An empty page ends the search.
     */
    private suspend fun pages(indexer: UsenetIndexer, url: Url, request: UsenetSearchRequest,
        caps: NewznabCapabilities, results: Int, wholeSeason: Boolean = false): List<UsenetRelease> {
        require(results >= 0) { "Invalid fetch limit" }
        val limit = caps.limit.coerceIn(1, 100)
        val kind = if (request.series) ContentKind.SHOW else ContentKind.MOVIE
        val releases = mutableListOf<UsenetRelease>()
        val seenUrls = hashSetOf<String>()
        val seenPages = hashSetOf<String>()
        var offset = 0
        while (results == 0 || offset < results) {
            currentCoroutineContext().ensureActive()
            val pageSize = if (results == 0) limit else minOf(limit, results - offset)
            val next = URLBuilder(if (offset == 0) url else
                NewznabProtocol.searchUrl(indexer, request, caps, offset, wholeSeason) ?: break)
                .setQueryParameter("limit", pageSize.toString()).build()
            val answer = try {
                request(indexer, next, kind = kind) { xml ->
                    NewznabProtocol.releases(xml, indexer) to UsenetText.sha256Hex(xml)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // A failed first page is still an error; later failures retain the pages already read.
                if (offset == 0) throw e else break
            }
            val (page, fingerprint) = answer
            if (page.items == 0 || !seenPages.add(fingerprint)) break
            val fresh = page.releases.filter { seenUrls.add(it.nzbUrl) }
            // Some indexers ignore offsets or cycle through the same pages with changing metadata.
            if (page.releases.isNotEmpty() && fresh.isEmpty()) break
            releases += if (results == 0) fresh else fresh.take(pageSize)
            if (offset > Int.MAX_VALUE - pageSize) break
            offset += pageSize
            if (page.total?.let { offset >= it } ?: (page.items < pageSize)) break
        }
        return releases
    }

    /**
     * Skips indexers in a cooldown without a request, and opens one when an
     * indexer throttles us or reports a spent quota, so later searches do not
     * spend requests that can only fail. Every request sent counts in the indexer's statistics,
     * for [kind] when it searched for one.
     */
    private suspend fun <T> request(indexer: UsenetIndexer, url: Url, ignoreCooldown: Boolean = false,
        kind: ContentKind? = null, parse: (String) -> T): T {
        val blockedUntil = states.get(indexer)?.blockedUntil ?: 0
        if (!ignoreCooldown && blockedUntil > now()) throw IndexerCooldownException(blockedUntil)
        fun count(outcome: RequestOutcome, elapsedMs: Long = 0) = stats?.indexerRequest(indexer.id, kind, outcome, elapsedMs)
        val started = TimeSource.Monotonic.markNow()
        val response = try { get(url) }
            catch (e: CancellationException) { throw e }
            catch (e: IOException) { count(RequestOutcome.FAILED); note(indexer, UsenetIndexerProblem.UNREACHABLE); throw e }
            catch (e: Exception) { count(RequestOutcome.FAILED); note(indexer, UsenetIndexerProblem.RESPONSE); throw e }
        val elapsedMs = started.elapsedNow().inWholeMilliseconds
        val retryAfter = retryAfterMs(response.headers["Retry-After"])
        if (response.code == 429) {
            count(RequestOutcome.LIMITED)
            throw cooldown(indexer, retryAfter?.coerceAtMost(MAX_THROTTLE_MS) ?: THROTTLE_MS)
        }
        return try {
            if (response.code !in 200..299) {
                NewznabProtocol.throwIfKnownError(response.body)
                if (response.code == 401 || response.code == 403) throw NewznabAuthException()
                throw IndexerHttpException(response.code)
            }
            parse(response.body).also {
                val apiHitsLeft = left(response.headers, API_LEFT_HEADERS)
                val grabsLeft = left(response.headers, GRABS_LEFT_HEADERS)
                // Indexers announce a spent daily quota before they start refusing requests.
                if (apiHitsLeft == 0 || grabsLeft == 0) cooldown(indexer, EXHAUSTED_MS)
                if (apiHitsLeft != null || grabsLeft != null) states.update(indexer) {
                    it.copy(problem = null, apiHitsLeft = apiHitsLeft, grabsLeft = grabsLeft, leftAt = now())
                } else note(indexer, null)
                count(RequestOutcome.ANSWERED, elapsedMs)
            }
        } catch (_: NewznabQuotaException) {
            count(RequestOutcome.LIMITED)
            throw cooldown(indexer, retryAfter ?: QUOTA_MS)
        } catch (e: NewznabAuthException) {
            count(RequestOutcome.FAILED)
            note(indexer, UsenetIndexerProblem.AUTH); throw e
        } catch (e: Exception) {
            count(RequestOutcome.FAILED)
            note(indexer, UsenetIndexerProblem.RESPONSE); throw e
        }
    }

    /** Most requests succeed: only a changed outcome is written. */
    private fun note(indexer: UsenetIndexer, problem: UsenetIndexerProblem?) {
        if (states.get(indexer)?.problem != problem) states.update(indexer) { it.copy(problem = problem) }
    }

    /** Increments whenever an indexer status may have changed. */
    val statusChanges: StateFlow<Int> get() = states.revision

    fun status(indexer: UsenetIndexer): UsenetIndexerStatus = states.get(indexer).let { state ->
        // The counts are daily: one from before the indexer's last reset would mislead.
        val fresh = state != null && now() - state.leftAt < LEFT_TTL_MS
        UsenetIndexerStatus(state?.blockedUntil ?: 0, state?.problem, state?.apiHitsLeft?.takeIf { fresh },
            state?.grabsLeft?.takeIf { fresh }, state?.leftAt?.takeIf { fresh } ?: 0)
    }

    private fun left(headers: Headers, names: List<String>) =
        names.firstNotNullOfOrNull { headers[it]?.trim()?.toIntOrNull() }?.coerceAtLeast(0)

    /** Extends, never shortens, the cooldown: concurrent refusals must not undercut each other. */
    private fun cooldown(indexer: UsenetIndexer, durationMs: Long): IndexerCooldownException {
        states.update(indexer) { it.copy(blockedUntil = maxOf(it.blockedUntil, now() + durationMs)) }
        return IndexerCooldownException(states.get(indexer)?.blockedUntil ?: (now() + durationMs))
    }

    private fun retryAfterMs(value: String?): Long? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        text.toLongOrNull()?.let { return if (it > 0) it * 1000 else null }
        return NewznabDates.rfc1123(text)?.minus(now())?.takeIf { it > 0 }
    }

    private fun now() = epochMs()

    private class IndexerResponse(val code: Int, val headers: Headers, val body: String)

    /** Errors never carry the URL, which holds the API key. */
    private suspend fun get(url: Url): IndexerResponse {
        val response = try {
            client.get(url) {
                header(USER_AGENT, agent())
                header("Cache-Control", "no-store")
            }
        } catch (e: CancellationException) { throw e }
        catch (_: IOException) { throw IOException("Could not reach indexer") }
        catch (_: Exception) { throw IllegalStateException("Indexer request failed") }
        val body = try { body(response) }
            catch (e: CancellationException) { throw e }
            catch (_: IOException) { throw IOException("Could not reach indexer") }
        return IndexerResponse(response.status.value, response.headers, body)
    }

    private suspend fun body(response: HttpResponse): String {
        check((response.contentLength() ?: 0) <= MAX_XML_BYTES) { "Indexer response is too large" }
        val bytes = response.bodyAsChannel().readRemaining(MAX_XML_BYTES + 1L).readByteArray()
        check(bytes.size <= MAX_XML_BYTES) { "Indexer response is too large" }
        return bytes.decodeToString()
    }

    private companion object {
        const val MAX_XML_BYTES = 4 * 1024 * 1024
        const val CAPS_TTL_MS = 7L * 24 * 3600 * 1000
        const val THROTTLE_MS = 60_000L
        const val MAX_THROTTLE_MS = 15 * 60_000L
        const val QUOTA_MS = 30 * 60_000L
        const val EXHAUSTED_MS = 15 * 60_000L
        const val SEASON_TTL_MS = 2 * 3600_000L
        const val MAX_SEASONS = 50
        const val LEFT_TTL_MS = 24 * 3600_000L
        val API_LEFT_HEADERS = listOf("X-RateLimit-Daily-Remaining", "x-api-remaining")
        val GRABS_LEFT_HEADERS = listOf("X-DNZBLimit-Daily-Remaining", "x-grab-remaining")
    }
}
