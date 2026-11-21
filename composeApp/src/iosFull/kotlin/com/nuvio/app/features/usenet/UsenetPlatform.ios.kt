package com.nuvio.app.features.usenet

import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.usenet_indexer_search_failed
import nuvio.composeapp.generated.resources.usenet_indexer_search_paused
import nuvio.composeapp.generated.resources.usenet_size_pack
import nuvio.composeapp.generated.resources.usenet_size_pack_share
import nuvio.composeapp.generated.resources.usenet_size_season
import nuvio.composeapp.generated.resources.usenet_size_season_share
import org.jetbrains.compose.resources.getString
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDateFormatterMediumStyle
import platform.Foundation.NSDateFormatterNoStyle
import platform.Foundation.NSDateFormatterShortStyle
import platform.Foundation.dateWithTimeIntervalSince1970

/**
 * iOS Full links the engine as a library and runs it inside the app (an iOS app cannot start a
 * process). Sources live in the Keychain on this device; search state and statistics in its
 * preferences. The provider test and tuner need raw NNTP sockets and are not offered yet.
 */
internal actual object UsenetPlatform {
    private val sourceStorage by lazy { KeychainUsenetSourceStorage() }
    private val engineStorage by lazy { DefaultsUsenetPreferences("usenet_performance") }
    private val indexerStateStorage by lazy { DefaultsNewznabStateStorage() }
    private val unplayableStorage by lazy { DefaultsUnplayableReleaseStorage() }
    // Exists from the start, so screens can watch it before the stored list is first read.
    private val outcomeRevision = MutableStateFlow(0)
    private val unplayable by lazy { UnplayableReleases(unplayableStorage, outcomeRevision) }
    private val statsStorage by lazy { DefaultsUsenetStatsStorage() }
    private val sourceStats by lazy { UsenetSourceStats(statsStorage) }
    private val client by lazy { NewznabClient(indexerStateStorage, sourceStats) { UsenetRepository.engineSettings().searchAgent } }
    private val service by lazy { BuiltInUsenetService(client, unplayable = unplayable, labels = ::localizedLabels, stats = sourceStats) }
    private val sidecar by lazy {
        UsenetSidecar(IosUsenetEngineHost(), { UsenetRepository.engineSettings() }, unplayable, sourceStats)
    }
    private val json = Json { ignoreUnknownKeys = true }

    init {
        UsenetStartupReports.initialize(DefaultsUsenetPreferences(UsenetStartupReports.PREFERENCES))
    }

    actual val isSupported: Boolean = true

    actual val providerToolsSupported: Boolean = false

    // The statistics learn the providers' server URLs, so engine reports, which name servers by URL, count for them.
    actual fun loadSources(profileId: Int): UsenetSourceConfiguration =
        sourceStorage.read(profileId).also { sourceStats.knowProviders(it.providers) }

    actual fun saveSources(profileId: Int, value: UsenetSourceConfiguration) {
        sourceStorage.update(profileId, value)
        sourceStats.knowProviders(value.providers)
    }

    actual fun removeProfile(profileId: Int) = sourceStorage.removeProfile(profileId)

    actual fun clearAll() {
        sourceStorage.clear()
        indexerStateStorage.clear()
        unplayableStorage.clear()
        UsenetStartupReports.clear()
        statsStorage.clearAll()
    }

    actual fun loadEngineSettings(): UsenetConfiguration = engineStorage.getString(ENGINE_KEY)
        ?.let { runCatching { json.decodeFromString(UsenetConfiguration.serializer(), it) }.getOrNull() }
        ?.sanitized()
        ?: UsenetConfiguration()

    actual fun saveEngineSettings(value: UsenetConfiguration) {
        engineStorage.putString(ENGINE_KEY, json.encodeToString(UsenetConfiguration.serializer(), value.sanitized()))
        sidecar.settingsChanged()
    }

    actual suspend fun testProvider(provider: UsenetProvider, allowPrivateNetwork: Boolean): UsenetProviderTestResult =
        if (runCatching { provider.validate() }.isFailure) UsenetProviderTestResult.INVALID else UsenetProviderTestResult.UNREACHABLE

    actual suspend fun testIndexer(indexer: UsenetIndexer): Boolean = withContext(Dispatchers.IO) {
        try {
            indexer.validate()
            client.verify(indexer)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    actual suspend fun tuneProvider(
        provider: UsenetProvider,
        allowPrivateNetwork: Boolean,
        pipeline: Int,
        onProgress: (TuneProgress) -> Unit,
    ): TuneOutcome = TuneOutcome.Failed(TuneFailure.CONNECT, UsenetProviderTestResult.UNREACHABLE)

    actual val indexerStatusChanges: StateFlow<Int> get() = client.statusChanges

    actual fun indexerStatus(indexer: UsenetIndexer): UsenetIndexerStatus = client.status(indexer)

    actual fun search(
        config: UsenetSourceConfiguration,
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?,
        groupName: String,
    ): Flow<UsenetSearchUpdate> {
        if (!config.ready) return emptyFlow()
        // The engine is likely needed soon: start Go while the indexers answer.
        sidecar.prewarmForSources()
        return service.search(config, type, videoId, season, episode, groupName, BUILT_IN_USENET_GROUP_ID)
            .flowOn(Dispatchers.IO)
    }

    actual val outcomeChanges: StateFlow<Int> = outcomeRevision.asStateFlow()

    actual fun arrangeOutcomes(groups: List<AddonStreamGroup>, availableFirst: Boolean): List<AddonStreamGroup> {
        if (groups.none { it.addonId == BUILT_IN_USENET_GROUP_ID }) return groups
        return BuiltInUsenetService.arrangeOutcomes(groups, BUILT_IN_USENET_GROUP_ID, unplayable::contains,
            unplayable::played, availableFirst, unplayable::sibling)
    }

    actual suspend fun prepare(stream: StreamItem, season: Int?, episode: Int?, profileId: Int): StreamItem {
        return try {
            sidecar.resolve(stream, season, episode, profileId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsenetPreparationException) {
            val scope = when (e.scope) {
                UsenetPreparationException.Scope.SOURCE -> UsenetFailureScope.SOURCE
                UsenetPreparationException.Scope.PROVIDER -> UsenetFailureScope.PROVIDER
                UsenetPreparationException.Scope.ENGINE -> UsenetFailureScope.ENGINE
            }
            throw UsenetPlaybackException(e.message.orEmpty(), e.failure, scope)
        } catch (e: Exception) {
            throw UsenetPlaybackException(e.message ?: e::class.simpleName.orEmpty())
        }
    }

    actual fun release(url: String?) = sidecar.release(url)

    actual fun restoredStream(nzbUrl: String, name: String, title: String?, size: Long?, publishedAt: Long?, series: Boolean): StreamItem? {
        val config = UsenetRepository.sources()
        val restored = BuiltInUsenetService.restoredStream(nzbUrl, name, title, size, publishedAt, config, series)
            ?.takeUnless(unplayable::contains) ?: return null
        // No search lists it: its download and playback count for the indexer its link names.
        UsenetKeptLinks.indexerOf(nzbUrl, config)?.let { indexer ->
            sourceStats.remember(listOfNotNull(restored.nzbUrl), indexer.id, if (series) ContentKind.SHOW else ContentKind.MOVIE)
        }
        return restored
    }

    actual fun prefetch(owner: Any, stream: StreamItem, season: Int?, episode: Int?, profileId: Int) {
        sidecar.prefetch(owner, stream, season, episode, profileId)
    }

    actual fun cancelPrefetch(owner: Any) {
        sidecar.cancelPrefetch(owner)
    }

    actual fun isEngineUrl(url: String?): Boolean = url != null && UsenetEngineUrls.isSession(url)

    actual fun onFirstFrame(url: String?) {
        if (url != null && UsenetEngineUrls.isSession(url)) sidecar.firstFrame(url)
    }

    actual fun markStartup(url: String, name: String) {
        if (UsenetEngineUrls.isSession(url)) sidecar.markStartup(url, name)
    }

    actual fun markStartupValue(url: String, name: String, value: Long) {
        if (UsenetEngineUrls.isSession(url)) sidecar.markStartupValue(url, name, value)
    }

    actual val lastStartupReport: StateFlow<String> get() = UsenetStartupReports.latest

    actual fun onPlaybackFailure(url: String?, failure: String?) {
        if (url != null && UsenetEngineUrls.isSession(url)) sidecar.playbackFailed(url, failure)
    }

    actual fun onAppForegrounded() {
        UsenetSidecar.appForeground = true
        if (UsenetRepository.engineSettings().prewarmOnLaunch) sidecar.prewarm()
    }

    actual fun onAppBackgrounded() {
        UsenetSidecar.appForeground = false
        sidecar.onAppBackgrounded()
        // Counts held back from frequent saves are written before the app may be suspended.
        sourceStats.flush()
    }

    actual val statsRevision: StateFlow<Int> get() = sourceStats.revision

    actual fun stats(indexers: List<UsenetIndexer>, providers: List<UsenetProvider>): UsenetStatsSnapshot {
        sourceStats.load()
        sourceStats.flush()
        val period = sourceStats.period.value
        val content = sourceStats.content.value
        return UsenetStatsSnapshot(
            period = period,
            content = content,
            indexers = indexers.associate { indexer ->
                indexer.id to IndexerStatsUi(
                    sourceStats.indexer(indexer.id, period, content),
                    sourceStats.indexerToday(indexer.id, content),
                    sourceStats.indexerSince(indexer.id),
                )
            },
            providers = providers.associate { provider ->
                provider.id to ProviderStatsUi(sourceStats.provider(provider.id, period, content), sourceStats.providerSince(provider.id))
            },
        )
    }

    actual fun setStatsPeriod(period: StatsPeriod) = sourceStats.setPeriod(period)

    actual fun setStatsContent(content: StatsContent) = sourceStats.setContent(content)

    actual fun resetStats() = sourceStats.reset()

    actual fun previewRanking(profile: StreamRankingProfile, samples: List<RankingSample>, mediaType: String): RankingPreview? {
        // Indexer names sort by their priority, as the indexers of real results do.
        val indexerOrder = UsenetRepository.sources().indexers.sortedBy { it.priority }
            .mapIndexed { index, indexer -> indexer.name.lowercase() to index }.toMap()
        return RankingPreviewer.preview(profile, samples, mediaType, indexerOrder)
    }

    actual fun dateTime(epochMs: Long): String = format(epochMs, NSDateFormatterShortStyle, NSDateFormatterMediumStyle)

    actual fun clockTime(epochMs: Long): String = format(epochMs, NSDateFormatterNoStyle, NSDateFormatterShortStyle)

    private fun format(epochMs: Long, date: ULong, time: ULong): String = NSDateFormatter().apply {
        dateStyle = date
        timeStyle = time
    }.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))

    private suspend fun localizedLabels(): UsenetLabels = runCatching {
        UsenetLabels(
            indexerPaused = getString(Res.string.usenet_indexer_search_paused),
            indexerFailed = getString(Res.string.usenet_indexer_search_failed),
            sizeSeasonShare = getString(Res.string.usenet_size_season_share),
            sizePackShare = getString(Res.string.usenet_size_pack_share),
            sizeSeason = getString(Res.string.usenet_size_season),
            sizePack = getString(Res.string.usenet_size_pack),
        )
    }.getOrDefault(UsenetLabels())

    private const val ENGINE_KEY = "configuration"
}
