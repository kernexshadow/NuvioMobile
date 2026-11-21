package com.nuvio.app.features.usenet

import co.touchlab.kermit.Logger
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.epochMs
import kotlin.math.abs
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Where [UnplayableReleases] persists; the app uses private platform preferences. */
internal interface UnplayableReleaseStorage {
    fun load(): String?
    fun save(value: String)
    /** Changes when the stored list is cleared from outside, so a loaded copy is dropped. */
    val generation: Int get() = 0
}

@Serializable
internal data class UnplayableRelease(val kind: String, val at: Long, val providers: String,
    val episode: Int? = null, val posted: Long? = null)

/** How another episode of the same upload last went: [episode] failed, or played. */
internal data class SiblingOutcome(val episode: Int, val failed: Boolean, val blank: Boolean = false)

/** How a release last went with the current providers: it failed for good, or it played recently. */
internal data class ReleaseOutcome(val failed: Boolean = false, val played: Boolean = false)

/**
 * How releases last went with the current providers. Mostly releases the engine found unplayable:
 * articles removed or damaged. Such a release fails again on every attempt, and only after its NZB
 * was downloaded, so results put it last and fallback skips it. Releases that showed their first
 * frame are kept too, for two weeks: results label them and fallback tries them first, since their
 * NZB is usually cached and their articles were there. An entry is keyed by a hash of the NZB URL, exact within one
 * indexer, and by one of the title and size in megabytes: another indexer gives the same upload
 * another URL, and only its NZB would prove it is the same. Nothing secret is stored. Other
 * providers may still have the articles, so a change of providers forgets an entry, as do 30 days.
 */
internal class UnplayableReleases(
    private val storage: UnplayableReleaseStorage,
    /** Bumped when a release is remembered; the app hands in one that exists before this does. */
    private val changes: MutableStateFlow<Int> = MutableStateFlow(0),
    private val now: () -> Long = ::epochMs
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = SynchronizedObject()
    /** Increments when a release is remembered, so a shown result list can be arranged again. */
    val revision: StateFlow<Int> = changes.asStateFlow()
    private var loaded: MutableMap<String, UnplayableRelease>? = null
    private var loadedGeneration = 0

    /** Loaded on first use, and again after an account reset cleared the storage. */
    private val entries: MutableMap<String, UnplayableRelease>
        get() {
            loaded?.takeIf { loadedGeneration == storage.generation }?.let { return it }
            loadedGeneration = storage.generation
            return runCatching { json.decodeFromString<Map<String, UnplayableRelease>>(storage.load() ?: "{}") }
                .getOrDefault(emptyMap()).toMutableMap().also { loaded = it }
        }

    /** Remembers a release whose open or playback failed with an engine [kind] in [KINDS]; others are ignored. */
    fun record(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, kind: String?, posted: Long? = null): Unit = synchronized(lock) {
        if (kind !in KINDS) return
        if (put(nzbUrl, title, size, servers, requireNotNull(kind), posted)) {
            runCatching { Logger.withTag("UsenetUnplayable").i { "remembered kind=$kind release=\"${title?.lineSequence()?.firstOrNull()}\" size=${size ?: 0}" } }
        }
    }

    /**
     * Remembers a release that showed its first frame. A later failure replaces this, and so does
     * a newer success: a release already marked within a day is not written again, unless its
     * upload's newest outcome is another episode's.
     */
    fun recordPlayed(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, posted: Long? = null): Unit = synchronized(lock) {
        val known = find(nzbUrl, title, size, servers, posted)
        val siblingsKnow = siblingKey(title)?.let { (key, episode) ->
            entries[key]?.let { it.kind == PLAYED && it.episode == episode && it.posted == posted } == true
        } ?: true
        if (known?.kind == PLAYED && now() - known.at < REPLAYED_MS && siblingsKnow) return
        put(nzbUrl, title, size, servers, PLAYED, posted)
    }

    private fun put(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, kind: String, posted: Long?): Boolean {
        val keys = keys(nzbUrl, title, listOf(size?.let { it / MB })).ifEmpty { return false }
        val entry = UnplayableRelease(kind, now(), providersKey(servers), posted = posted)
        keys.forEach { entries[it] = entry }
        // The upload's other episodes are told by the newest outcome among its episodes.
        siblingKey(title)?.let { (key, episode) -> entries[key] = entry.copy(episode = episode) }
        entries.entries.removeAll { now() - it.value.at > RETENTION_MS }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.minBy { it.value.at }.key)
        storage.save(json.encodeToString(entries.toMap()))
        changes.value += 1
        return true
    }

    /** The newest entry for this release with these providers, if any. */
    private fun find(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, posted: Long?): UnplayableRelease? {
        val providers = providersKey(servers)
        // Indexers round sizes differently (one to thousands of bytes): compare by megabyte, either side.
        val megabytes = size?.takeIf { it > 0 }?.let { (it / MB - 1..it / MB + 1).toList() } ?: listOf(null)
        return keys(nzbUrl, title, megabytes).mapNotNull { entries[it] }
            // A blank post is the same with every provider.
            .filter { it.providers == providers || it.kind == BLANK }
            // A repost under the same title and size is another upload; indexers list one upload's
            // time a little differently.
            .filter { posted == null || it.posted == null || abs(posted - it.posted) <= POSTING_SLACK_MS }
            .maxByOrNull { it.at }
    }

    fun contains(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, posted: Long? = null): Boolean =
        outcome(nzbUrl, title, size, servers, posted).failed

    /** Whether the release showed its first frame with these providers within [PLAYED_MS]. */
    fun played(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, posted: Long? = null): Boolean =
        outcome(nzbUrl, title, size, servers, posted).played

    /** [contains] and [played] from one lookup: a result list asks both of hundreds of releases. */
    fun outcome(nzbUrl: String?, title: String?, size: Long?, servers: List<String>, posted: Long? = null): ReleaseOutcome = synchronized(lock) {
        val entry = find(nzbUrl, title, size, servers, posted) ?: return ReleaseOutcome()
        val age = now() - entry.at
        return ReleaseOutcome(failed = entry.kind in KINDS && age <= RETENTION_MS, played = entry.kind == PLAYED && age <= PLAYED_MS)
    }

    /**
     * How another episode of this upload last went with these providers: episodes posted together
     * are usually removed, or kept, together. Null for a pack, or when only this episode was tried.
     * A repost of the same title is another upload: only a release [posted] about when that
     * episode was, allowing a week per episode between them as a season airs, is told.
     */
    fun sibling(title: String?, servers: List<String>, posted: Long?): SiblingOutcome? = synchronized(lock) {
        val (key, episode) = siblingKey(title) ?: return null
        val entry = entries[key]?.takeIf { it.episode != null && it.episode != episode } ?: return null
        if (entry.providers != providersKey(servers) && entry.kind != BLANK) return null
        val window = abs(episode - entry.episode!!) * EPISODE_INTERVAL_MS + POSTING_SLACK_MS
        if (posted == null || entry.posted == null || abs(posted - entry.posted) > window) return null
        val age = now() - entry.at
        return when {
            entry.kind in KINDS && age <= RETENTION_MS -> SiblingOutcome(entry.episode, failed = true, blank = entry.kind == BLANK)
            entry.kind == PLAYED && age <= PLAYED_MS -> SiblingOutcome(entry.episode, failed = false)
            else -> null
        }
    }

    fun sibling(stream: StreamItem): SiblingOutcome? = sibling(stream.title, stream.nzbServers, stream.publishedAt)

    private fun siblingKey(title: String?): Pair<String, Int>? {
        val name = title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        return NewznabProtocol.episodeTemplate(name)?.let { (template, episode) -> "s:" + sha256(template) to episode }
    }

    fun record(stream: StreamItem, kind: String?) =
        record(stream.nzbUrl, stream.title, stream.behaviorHints.videoSize, stream.nzbServers, kind, stream.publishedAt)

    fun contains(stream: StreamItem): Boolean =
        contains(stream.nzbUrl, stream.title, stream.behaviorHints.videoSize, stream.nzbServers, stream.publishedAt)

    fun recordPlayed(stream: StreamItem) =
        recordPlayed(stream.nzbUrl, stream.title, stream.behaviorHints.videoSize, stream.nzbServers, stream.publishedAt)

    fun played(stream: StreamItem): Boolean =
        played(stream.nzbUrl, stream.title, stream.behaviorHints.videoSize, stream.nzbServers, stream.publishedAt)

    /** The keys of a release at each of [megabytes]; every key is hashed once, which is what a lookup costs. */
    private fun keys(nzbUrl: String?, title: String?, megabytes: List<Long?>): List<String> {
        val name = title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val titles = when {
            name == null -> emptyList()
            // A season pack's title names it, and its listed size changes once the episode count
            // gives an episode's share: the size is left out of its key.
            NewznabProtocol.packSeasons(name).isNotEmpty() -> listOf("t:" + sha256("$name\npack"))
            else -> megabytes.filter { it != null && it > 0 }.distinct().map { "t:" + sha256("$name\n${it}MB") }
        }
        return listOfNotNull(nzbUrl?.takeIf { it.isNotBlank() }?.let { "u:" + sha256(it) }) + titles
    }

    private var lastServers: List<String>? = null
    private var lastProvidersKey = ""

    /** The providers' addresses only: credentials, connections and priorities do not change what they hold. */
    private fun providersKey(servers: List<String>): String {
        // A search asks with the same providers for every result.
        if (servers == lastServers) return lastProvidersKey
        return sha256(servers.map(::serverAddress).distinct().sorted().joinToString("\n"))
            .also { lastServers = servers; lastProvidersKey = it }
    }

    /** Lowercase hex, as stored entries already are. */
    private fun sha256(value: String): String = UsenetText.sha256Hex(value)

    companion object {
        /** Engine failures that the same release repeats with the same providers. */
        val KINDS = setOf("missing-article", "hole-limit", "invalid-article", BLANK)
        /** A release whose video holds only zeros: a fake post under a real name. */
        private const val BLANK = "blank-content"
        /** A release that played; not a failure kind. */
        private const val PLAYED = "played"
        private const val RETENTION_MS = 30L * 24 * 3600 * 1000
        /** Articles of a release that played can still be removed later: trust it for two weeks. */
        private const val PLAYED_MS = 14L * 24 * 3600 * 1000
        private const val REPLAYED_MS = 24L * 3600 * 1000
        /** An airing season's episodes are posted about a week apart; a whole season at once. */
        private const val EPISODE_INTERVAL_MS = 8L * 24 * 3600 * 1000
        private const val POSTING_SLACK_MS = 2L * 24 * 3600 * 1000
        private const val MAX_ENTRIES = 2000
        private const val MB = 1_000_000L
    }
}

/**
 * A server URL's scheme, host and port as java.net.URI gives them ("nntps://host:563"); its
 * credentials, connections and priority are left out. Empty for a URL that cannot be read.
 */
internal fun serverAddress(server: String): String {
    val scheme = server.substringBefore("://", "").takeIf { it.isNotEmpty() && it.first().isLetter() } ?: return ""
    val authority = server.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    val hostAndPort = authority.substringAfterLast('@')
    val (host, port) = if (hostAndPort.startsWith('[')) {
        hostAndPort.substringBefore(']') + "]" to hostAndPort.substringAfter("]", "").removePrefix(":")
    } else {
        hostAndPort.substringBefore(':') to hostAndPort.substringAfter(':', "")
    }
    if (!isValidHost(host)) return ""
    return "$scheme://$host:${if (port.isEmpty()) -1 else port.toIntOrNull() ?: return ""}"
}
