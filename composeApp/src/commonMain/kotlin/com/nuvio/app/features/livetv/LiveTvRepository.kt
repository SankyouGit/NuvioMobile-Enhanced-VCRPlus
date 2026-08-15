package com.nuvio.app.features.livetv

import com.nuvio.app.features.addons.httpGetText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64

private const val XTREAM_EPG_LIMIT = 96
private const val XTREAM_EPG_MAX_CONCURRENCY = 8
private const val XTREAM_EPG_REQUEST_TIMEOUT_MS = 10_000L

object LiveTvRepository {
    private val mutableUiState = MutableStateFlow(LiveTvUiState())
    val uiState = mutableUiState.asStateFlow()
    private val epgScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val epgMutex = Mutex()
    private var epgJob: Job? = null
    private var favoriteEpgReloadJob: Job? = null
    private var activeEpgSourceUrl: String? = null
    private var activeEpgUrls: List<String> = emptyList()
    private var activeEpgRequestHeaders: Map<String, String> = emptyMap()

    private var initialized = false

    fun ensureLoaded() {
        if (initialized) return
        initialized = true
        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvStorage.loadSourceType(),
            sourceUrl = LiveTvStorage.loadSourceUrl().orEmpty(),
            stalkerSettings = LiveTvStorage.loadStalkerSettings(),
            xtreamSettings = LiveTvStorage.loadXtreamSettings(),
            favoriteUrls = LiveTvStorage.loadFavoriteUrls(),
            recentChannel = LiveTvStorage.loadRecentChannel(),
        )
    }

    fun onProfileChanged() {
        favoriteEpgReloadJob?.cancel()
        favoriteEpgReloadJob = null
        epgJob?.cancel()
        epgJob = null
        activeEpgSourceUrl = null
        activeEpgUrls = emptyList()
        activeEpgRequestHeaders = emptyMap()
        initialized = false
        mutableUiState.value = LiveTvUiState()
        ensureLoaded()
    }

    suspend fun load(sourceUrl: String): Result<List<LiveTvChannel>> {
        val normalizedUrl = sourceUrl.trim()
        if (!normalizedUrl.startsWith("http://") && !normalizedUrl.startsWith("https://")) {
            val error = IllegalArgumentException("Geçerli bir HTTP veya HTTPS M3U bağlantısı girin.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceUrl = normalizedUrl,
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            if (normalizedUrl.looksLikeDirectVideoUrl()) {
                val channel = directStreamChannel(normalizedUrl)
                LiveTvStorage.saveSourceUrl(normalizedUrl)
                LiveTvStorage.saveLocalPlaylistData("")
                LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
                mutableUiState.value = LiveTvUiState(
                    sourceType = LiveTvSourceType.M3u,
                    sourceUrl = normalizedUrl,
                    stalkerSettings = mutableUiState.value.stalkerSettings,
                    xtreamSettings = mutableUiState.value.xtreamSettings,
                    channels = listOf(channel),
                    favoriteUrls = mutableUiState.value.favoriteUrls,
                    recentChannel = mutableUiState.value.recentChannel,
                    isLoaded = true,
                )
                return@runCatching listOf(channel)
            }

            val playlist = withContext(Dispatchers.Default) {
                val playlistData = httpGetTextWithHeaders(
                    url = normalizedUrl,
                    headers = M3U_PLAYLIST_REQUEST_HEADERS,
                )
                if (playlistData.looksLikeHlsManifest()) {
                    ParsedM3uPlaylist(
                        channels = listOf(directStreamChannel(normalizedUrl)),
                        epgUrls = emptyList(),
                    )
                } else {
                    parseM3uPlaylistData(playlistData)
                }
            }
            val channels = playlist.channels
            require(channels.isNotEmpty()) { "Bu M3U listesinde oynatılabilir kanal bulunamadı." }
            LiveTvStorage.saveSourceUrl(normalizedUrl)
            LiveTvStorage.saveLocalPlaylistData("")
            LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.M3u,
                sourceUrl = normalizedUrl,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isEpgLoading = playlist.epgUrls.isNotEmpty(),
                isLoaded = true,
            )
            loadEpgInBackground(normalizedUrl, playlist.epgUrls)
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "M3U listesi yüklenemedi.",
            )
        }
    }

    suspend fun loadLocalPlaylist(fileName: String, playlistData: String): Result<List<LiveTvChannel>> {
        val trimmedData = playlistData.trim()
        val displayName = fileName.trim().ifBlank { "Local M3U playlist" }
        if (trimmedData.isBlank()) {
            val error = IllegalArgumentException("Seçilen M3U dosyası boş.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.M3u,
            sourceUrl = displayName,
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            val playlist = withContext(Dispatchers.Default) {
                parseM3uPlaylistData(trimmedData)
            }
            val channels = playlist.channels
            require(channels.isNotEmpty()) { "Bu M3U dosyasında oynatılabilir kanal bulunamadı." }
            LiveTvStorage.saveSourceUrl(displayName)
            LiveTvStorage.saveLocalPlaylistData(trimmedData)
            LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.M3u,
                sourceUrl = displayName,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isEpgLoading = playlist.epgUrls.isNotEmpty(),
                isLoaded = true,
            )
            loadEpgInBackground(displayName, playlist.epgUrls)
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "M3U dosyası yüklenemedi.",
            )
        }
    }

    suspend fun loadStoredLocalPlaylist(): Result<List<LiveTvChannel>> {
        val playlistData = LiveTvStorage.loadLocalPlaylistData().orEmpty()
        if (playlistData.isBlank()) {
            return Result.failure(IllegalStateException("Kayıtlı M3U dosyası bulunamadı."))
        }
        return loadLocalPlaylist(
            fileName = LiveTvStorage.loadSourceUrl().orEmpty().ifBlank { "Local M3U playlist" },
            playlistData = playlistData,
        )
    }

    suspend fun loadStalker(settings: LiveTvStalkerSettings): Result<List<LiveTvChannel>> {
        val normalizedSettings = settings.normalized()
        if (!normalizedSettings.isConfigured) {
            val error = IllegalArgumentException("Portal URL ve MAC adresi zorunludur.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }
        if (!normalizedSettings.portalUrl.startsWith("http://") && !normalizedSettings.portalUrl.startsWith("https://")) {
            val error = IllegalArgumentException("Geçerli bir HTTP veya HTTPS portal bağlantısı girin.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            sourceType = LiveTvSourceType.Stalker,
            sourceUrl = normalizedSettings.portalUrl,
            stalkerSettings = normalizedSettings,
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            val channels = withContext(Dispatchers.Default) {
                fetchStalkerChannels(normalizedSettings)
            }
            require(channels.isNotEmpty()) { "Bu Stalker Portal içinde oynatılabilir kanal bulunamadı." }
            LiveTvStorage.saveLocalPlaylistData("")
            LiveTvStorage.saveSourceType(LiveTvSourceType.Stalker)
            LiveTvStorage.saveStalkerSettings(normalizedSettings)
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.Stalker,
                sourceUrl = normalizedSettings.portalUrl,
                stalkerSettings = normalizedSettings,
                xtreamSettings = mutableUiState.value.xtreamSettings,
                channels = channels,
                favoriteUrls = mutableUiState.value.favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isLoaded = true,
            )
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "Stalker Portal yüklenemedi.",
            )
        }
    }

    suspend fun discoverXtreamCategories(
        settings: LiveTvXtreamSettings,
    ): Result<List<LiveTvXtreamCategory>> {
        val normalizedSettings = settings.normalized()
        if (!normalizedSettings.isConfigured) {
            val error = IllegalArgumentException("Server URL, username, and password are required.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }
        if (!normalizedSettings.serverUrl.startsWith("http://") && !normalizedSettings.serverUrl.startsWith("https://")) {
            val error = IllegalArgumentException("Enter a valid HTTP or HTTPS Xtream server URL.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            val categoryMap = withContext(Dispatchers.Default) {
                LiveTvRepositoryXtream.getLiveCategories(normalizedSettings)
            }
            val categories = categoryMap
                .map { (id, name) -> LiveTvXtreamCategory(id = id, name = name) }
                .sortedBy { it.name.lowercase() }
            require(categories.isNotEmpty()) { "No live categories were found for this Xtream provider." }
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                errorMessage = null,
            )
            categories
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                errorMessage = error.message ?: "Xtream categories could not be loaded.",
            )
        }
    }

    suspend fun loadXtream(settings: LiveTvXtreamSettings): Result<List<LiveTvChannel>> {
        val normalizedSettings = settings.normalized()
        if (!normalizedSettings.isConfigured) {
            val error = IllegalArgumentException("Server URL, username, and password are required.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }
        if (!normalizedSettings.serverUrl.startsWith("http://") && !normalizedSettings.serverUrl.startsWith("https://")) {
            val error = IllegalArgumentException("Enter a valid HTTP or HTTPS Xtream server URL.")
            mutableUiState.value = mutableUiState.value.copy(errorMessage = error.message)
            return Result.failure(error)
        }

        mutableUiState.value = mutableUiState.value.copy(
            isLoading = true,
            errorMessage = null,
        )

        return runCatching {
            val categoryMap = withContext(Dispatchers.Default) {
                LiveTvRepositoryXtream.getLiveCategories(normalizedSettings)
            }
            val requestedCategoryIds = normalizedSettings.selectedCategoryIds
            val validCategoryIds = requestedCategoryIds.intersect(categoryMap.keys)
            if (requestedCategoryIds.isNotEmpty()) {
                require(validCategoryIds.isNotEmpty()) {
                    "Your selected Xtream categories are no longer available. Reload categories and choose again."
                }
            }
            val effectiveSettings = normalizedSettings.copy(selectedCategoryIds = validCategoryIds)
            val channels = withContext(Dispatchers.Default) {
                fetchXtreamChannels(effectiveSettings, categoryMap)
            }
            require(channels.isNotEmpty()) { "No playable channels were found for the selected Xtream categories." }
            LiveTvStorage.saveLocalPlaylistData("")
            LiveTvStorage.saveSourceType(LiveTvSourceType.Xtream)
            LiveTvStorage.saveXtreamSettings(effectiveSettings)
            val favoriteUrls = mutableUiState.value.favoriteUrls
            val hasFavoriteXtreamChannels = channels.any { channel ->
                channel.streamUrl in favoriteUrls && !channel.xtreamStreamId.isNullOrBlank()
            }
            mutableUiState.value = LiveTvUiState(
                sourceType = LiveTvSourceType.Xtream,
                sourceUrl = effectiveSettings.serverUrl,
                stalkerSettings = mutableUiState.value.stalkerSettings,
                xtreamSettings = effectiveSettings,
                channels = channels,
                favoriteUrls = favoriteUrls,
                recentChannel = mutableUiState.value.recentChannel,
                isEpgLoading = hasFavoriteXtreamChannels,
                isLoaded = true,
            )
            loadXtreamFavoriteEpgInBackground(effectiveSettings)
            channels
        }.onFailure { error ->
            mutableUiState.value = mutableUiState.value.copy(
                isLoading = false,
                isEpgLoading = false,
                isLoaded = mutableUiState.value.channels.isNotEmpty(),
                errorMessage = error.message ?: "Xtream provider could not be loaded.",
            )
        }
    }

    suspend fun prepareForPlayback(channel: LiveTvChannel): LiveTvChannel =
        if (mutableUiState.value.sourceType == LiveTvSourceType.Stalker && !channel.stalkerCommand.isNullOrBlank()) {
            resolveStalkerPlaybackChannel(channel)
        } else {
            channel
        }

    fun disconnect() {
        LiveTvStorage.saveSourceUrl("")
        LiveTvStorage.saveLocalPlaylistData("")
        LiveTvStorage.saveSourceType(LiveTvSourceType.M3u)
        LiveTvRepositoryStalker.clearSession()
        mutableUiState.value = LiveTvUiState(
            sourceType = LiveTvSourceType.M3u,
            stalkerSettings = LiveTvStorage.loadStalkerSettings(),
            xtreamSettings = LiveTvStorage.loadXtreamSettings(),
            favoriteUrls = mutableUiState.value.favoriteUrls,
            recentChannel = mutableUiState.value.recentChannel,
        )
    }

    fun toggleFavorite(channel: LiveTvChannel) {
        val state = mutableUiState.value
        val favorites = state.favoriteUrls.toMutableSet()
        val wasAdded = favorites.add(channel.streamUrl)
        if (!wasAdded) {
            favorites.remove(channel.streamUrl)
        }
        LiveTvStorage.saveFavoriteUrls(favorites)

        val nowEpochMs = LiveTvClock.nowEpochMs()
        val favoriteTvgIds = state.channels
            .asSequence()
            .filter { candidate -> candidate.streamUrl in favorites }
            .mapNotNull(LiveTvChannel::tvgId)
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()
        val retainedProgrammes = state.programmesByChannel
            .mapValues { (channelId, programmes) ->
                if (channelId in favoriteTvgIds) {
                    programmes
                } else {
                    programmes.filter { programme ->
                        nowEpochMs in programme.startEpochMs until programme.stopEpochMs
                    }
                }
            }
            .filterValues(List<LiveTvProgramme>::isNotEmpty)

        mutableUiState.value = state.copy(
            favoriteUrls = favorites,
            programmesByChannel = retainedProgrammes,
        )

        if (state.sourceType == LiveTvSourceType.Xtream && state.xtreamSettings.isConfigured) {
            favoriteEpgReloadJob?.cancel()
            favoriteEpgReloadJob = epgScope.launch {
                delay(250L)
                val latestState = mutableUiState.value
                if (latestState.sourceType == LiveTvSourceType.Xtream && latestState.xtreamSettings.isConfigured) {
                    loadXtreamFavoriteEpgInBackground(latestState.xtreamSettings)
                }
            }
            return
        }

        if (
            !channel.tvgId.isNullOrBlank() &&
            activeEpgUrls.isNotEmpty() &&
            activeEpgSourceUrl == state.sourceUrl
        ) {
            favoriteEpgReloadJob?.cancel()
            favoriteEpgReloadJob = epgScope.launch {
                delay(600L)
                val latestState = mutableUiState.value
                if (
                    activeEpgUrls.isNotEmpty() &&
                    activeEpgSourceUrl == latestState.sourceUrl
                ) {
                    mutableUiState.value = latestState.copy(isEpgLoading = true)
                    loadEpgInBackground(
                        sourceUrl = latestState.sourceUrl,
                        epgUrls = activeEpgUrls,
                        requestHeaders = activeEpgRequestHeaders,
                    )
                }
            }
        }
    }

    fun recordRecentChannel(channel: LiveTvChannel) {
        val recentChannel = LiveTvRecentChannel(
            streamUrl = channel.streamUrl,
            name = channel.name,
            logoUrl = channel.logoUrl,
            group = channel.group,
            tvgId = channel.tvgId,
        )
        LiveTvStorage.saveRecentChannel(recentChannel)
        mutableUiState.value = mutableUiState.value.copy(recentChannel = recentChannel)
    }

    private fun loadXtreamFavoriteEpgInBackground(settings: LiveTvXtreamSettings) {
        activeEpgSourceUrl = null
        activeEpgUrls = emptyList()
        activeEpgRequestHeaders = emptyMap()
        epgJob?.cancel()

        val state = mutableUiState.value
        if (state.sourceType != LiveTvSourceType.Xtream || state.xtreamSettings != settings) {
            return
        }
        val favoriteChannels = state.channels.filter { channel ->
            channel.streamUrl in state.favoriteUrls && !channel.xtreamStreamId.isNullOrBlank()
        }
        if (favoriteChannels.isEmpty()) {
            mutableUiState.value = state.copy(
                programmesByChannel = emptyMap(),
                currentProgrammes = emptyMap(),
                isEpgLoading = false,
            )
            return
        }

        val sourceUrl = state.sourceUrl
        mutableUiState.value = state.copy(isEpgLoading = true)
        epgJob = epgScope.launch {
            val nowEpochMs = LiveTvClock.nowEpochMs()
            val semaphore = Semaphore(XTREAM_EPG_MAX_CONCURRENCY)
            val schedules = coroutineScope {
                favoriteChannels.map { channel ->
                    async {
                        semaphore.withPermit {
                            val programmes = withTimeoutOrNull(XTREAM_EPG_REQUEST_TIMEOUT_MS) {
                                runCatching {
                                    LiveTvRepositoryXtream.getFavoriteEpg(
                                        settings = settings,
                                        streamId = channel.xtreamStreamId.orEmpty(),
                                        nowEpochMs = nowEpochMs,
                                    )
                                }.getOrDefault(emptyList())
                            }.orEmpty()
                            channel.epgKey() to programmes
                        }
                    }
                }.awaitAll().toMap()
            }
            if (!isActive) return@launch

            val latestState = mutableUiState.value
            if (
                latestState.sourceType == LiveTvSourceType.Xtream &&
                latestState.sourceUrl == sourceUrl &&
                latestState.xtreamSettings == settings
            ) {
                val favoriteKeys = latestState.channels
                    .asSequence()
                    .filter { channel -> channel.streamUrl in latestState.favoriteUrls }
                    .map(LiveTvChannel::epgKey)
                    .toSet()
                val filteredSchedules = schedules
                    .filterKeys { key -> key in favoriteKeys }
                    .filterValues(List<LiveTvProgramme>::isNotEmpty)
                mutableUiState.value = latestState.copy(
                    programmesByChannel = filteredSchedules,
                    currentProgrammes = currentXmlTvProgrammes(filteredSchedules, nowEpochMs),
                    isEpgLoading = false,
                )
            }
        }
    }

    private fun loadEpgInBackground(
        sourceUrl: String,
        epgUrls: List<String>,
        requestHeaders: Map<String, String> = emptyMap(),
    ) {
        activeEpgSourceUrl = sourceUrl
        activeEpgUrls = epgUrls
        activeEpgRequestHeaders = requestHeaders
        epgJob?.cancel()
        if (epgUrls.isEmpty()) {
            mutableUiState.value = mutableUiState.value.copy(isEpgLoading = false)
            return
        }
        epgJob = epgScope.launch {
            epgMutex.lock()
            try {
                if (!isActive) return@launch
                val nowEpochMs = LiveTvClock.nowEpochMs()
                val programmesByChannel = mergeXmlTvProgrammeSchedules(
                    epgUrls.mapNotNull { epgUrl ->
                        runCatching {
                            val content = if (requestHeaders.isEmpty()) {
                                httpGetText(epgUrl)
                            } else {
                                httpGetTextWithHeaders(epgUrl, requestHeaders)
                            }
                            parseXmlTvProgrammeSchedule(
                                content = content,
                                nowEpochMs = nowEpochMs,
                            )
                        }.getOrNull()
                    },
                )
                if (!isActive) return@launch
                if (mutableUiState.value.sourceUrl == sourceUrl) {
                    mutableUiState.value = mutableUiState.value.copy(
                        programmesByChannel = programmesByChannel,
                        currentProgrammes = currentXmlTvProgrammes(programmesByChannel, nowEpochMs),
                        isEpgLoading = false,
                    )
                }
            } finally {
                epgMutex.unlock()
            }
        }
    }
}

internal expect object LiveTvStorage {
    fun loadSourceType(): LiveTvSourceType
    fun saveSourceType(type: LiveTvSourceType)
    fun loadSourceUrl(): String?
    fun saveSourceUrl(url: String)
    fun loadLocalPlaylistData(): String?
    fun saveLocalPlaylistData(data: String)
    fun loadStalkerSettings(): LiveTvStalkerSettings
    fun saveStalkerSettings(settings: LiveTvStalkerSettings)
    fun loadXtreamSettings(): LiveTvXtreamSettings
    fun saveXtreamSettings(settings: LiveTvXtreamSettings)
    fun loadFavoriteUrls(): Set<String>
    fun saveFavoriteUrls(urls: Set<String>)
    fun loadRecentChannel(): LiveTvRecentChannel?
    fun saveRecentChannel(channel: LiveTvRecentChannel?)
}

private data class StalkerSession(
    val settings: LiveTvStalkerSettings,
    val token: String,
)

private suspend fun fetchStalkerChannels(settings: LiveTvStalkerSettings): List<LiveTvChannel> {
    val session = LiveTvRepositoryStalker.session(settings)
    val genres = LiveTvRepositoryStalker.getGenres(session)
    return LiveTvRepositoryStalker.getChannels(session, genres)
}

private suspend fun resolveStalkerPlaybackChannel(channel: LiveTvChannel): LiveTvChannel {
    val settings = LiveTvRepository.uiState.value.stalkerSettings.normalized()
    if (!settings.isConfigured) return channel
    val session = LiveTvRepositoryStalker.session(settings)
    val resolvedUrl = LiveTvRepositoryStalker.createLink(session, channel.stalkerCommand.orEmpty())
        ?: channel.streamUrl
    return channel.copy(
        streamUrl = resolvedUrl,
        headers = channel.headers + LiveTvRepositoryStalker.playbackHeaders(session),
    )
}

private suspend fun fetchXtreamChannels(
    settings: LiveTvXtreamSettings,
    categories: Map<String, String>? = null,
): List<LiveTvChannel> {
    val categoryMap = categories ?: LiveTvRepositoryXtream.getLiveCategories(settings)
    return LiveTvRepositoryXtream.getLiveStreams(
        settings = settings,
        categories = categoryMap,
        selectedCategoryIds = settings.selectedCategoryIds.intersect(categoryMap.keys),
    )
}

private object LiveTvRepositoryXtream {
    private const val MAX_SCOPED_CATEGORY_REQUESTS = 24
    suspend fun getLiveCategories(settings: LiveTvXtreamSettings): Map<String, String> {
        val data = request(settings, action = "get_live_categories").jsonArrayOrEmpty()
        return data.associateNotNull { element ->
            val obj = element as? JsonObject ?: return@associateNotNull null
            val id = obj.stringValue("category_id") ?: obj.stringValue("id") ?: return@associateNotNull null
            val name = obj.stringValue("category_name") ?: obj.stringValue("name") ?: return@associateNotNull null
            id to name
        }
    }

    suspend fun getLiveStreams(
        settings: LiveTvXtreamSettings,
        categories: Map<String, String>,
        selectedCategoryIds: Set<String> = emptySet(),
    ): List<LiveTvChannel> {
        val useUnscopedFilteredRequest = selectedCategoryIds.isNotEmpty() && (
            selectedCategoryIds.size == categories.size ||
                selectedCategoryIds.size > MAX_SCOPED_CATEGORY_REQUESTS
            )
        val data = when {
            selectedCategoryIds.isEmpty() ->
                request(settings, action = "get_live_streams").jsonArrayOrEmpty()

            useUnscopedFilteredRequest ->
                request(settings, action = "get_live_streams")
                    .jsonArrayOrEmpty()
                    .filter { element ->
                        val obj = element as? JsonObject
                        obj?.stringValue("category_id") in selectedCategoryIds
                    }

            else -> {
                val scopedData = mutableListOf<JsonElement>()
                var scopedRequestFailed = false
                for (categoryId in selectedCategoryIds.sorted()) {
                    val response = runCatching {
                        request(
                            settings = settings,
                            action = "get_live_streams",
                            extraParameters = mapOf("category_id" to categoryId),
                        )
                    }.getOrNull()
                    if (response !is JsonArray) {
                        scopedRequestFailed = true
                        break
                    }
                    scopedData.addAll(response)
                }
                if (!scopedRequestFailed) {
                    scopedData
                } else {
                    request(settings, action = "get_live_streams")
                        .jsonArrayOrEmpty()
                        .filter { element ->
                            val obj = element as? JsonObject
                            obj?.stringValue("category_id") in selectedCategoryIds
                        }
                }
            }
        }

        return data.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val name = obj.stringValue("name") ?: return@mapIndexedNotNull null
            val streamId = obj.stringValue("stream_id") ?: obj.stringValue("id") ?: return@mapIndexedNotNull null
            val categoryId = obj.stringValue("category_id")
            if (selectedCategoryIds.isNotEmpty() && categoryId !in selectedCategoryIds) {
                return@mapIndexedNotNull null
            }
            val directSource = obj.stringValue("direct_source")
                ?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            val extension = obj.stringValue("container_extension")
                ?.trim()
                ?.trimStart('.')
                ?.takeIf(String::isNotBlank)
                ?: "ts"
            val streamUrl = directSource ?: settings.liveStreamUrl(streamId, extension)
            LiveTvChannel(
                id = "xtream-$streamId-$index",
                name = name,
                streamUrl = streamUrl,
                tvgId = obj.stringValue("epg_channel_id") ?: obj.stringValue("tvg_id"),
                logoUrl = obj.stringValue("stream_icon") ?: obj.stringValue("logo"),
                group = categoryId?.let(categories::get).orEmpty(),
                headers = M3U_STREAM_REQUEST_HEADERS,
                xtreamStreamId = streamId,
            )
        }.distinctBy { it.streamUrl }
    }

    suspend fun getFavoriteEpg(
        settings: LiveTvXtreamSettings,
        streamId: String,
        nowEpochMs: Long,
    ): List<LiveTvProgramme> {
        if (streamId.isBlank()) return emptyList()
        val parameters = mapOf(
            "stream_id" to streamId,
            "limit" to XTREAM_EPG_LIMIT.toString(),
        )
        val shortEpg = runCatching {
            request(
                settings = settings,
                action = "get_short_epg",
                extraParameters = parameters,
            )
        }.getOrNull()?.let { response ->
            parseXtreamEpgListings(response, nowEpochMs)
        }.orEmpty()
        if (shortEpg.isNotEmpty()) return shortEpg

        return runCatching {
            request(
                settings = settings,
                action = "get_simple_data_table",
                extraParameters = mapOf("stream_id" to streamId),
            )
        }.getOrNull()?.let { response ->
            parseXtreamEpgListings(response, nowEpochMs)
        }.orEmpty()
    }

    private suspend fun request(
        settings: LiveTvXtreamSettings,
        action: String,
        extraParameters: Map<String, String> = emptyMap(),
    ): JsonElement {
        val parameters = buildMap {
            put("username", settings.username)
            put("password", settings.password)
            put("action", action)
            putAll(extraParameters)
        }
        val url = settings.playerApiEndpoint() + parameters.entries.joinToString(
            separator = "&",
            prefix = "?",
        ) { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        return stalkerJson.parseToJsonElement(httpGetTextWithHeaders(url, M3U_PLAYLIST_REQUEST_HEADERS))
    }
}

internal fun parseXtreamEpgListings(
    data: JsonElement,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
): List<LiveTvProgramme> {
    val listings = (data as? JsonObject)?.arrayValue("epg_listings").orEmpty()
    return listings.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val startEpochMs = obj.stringValue("start_timestamp").toXtreamEpochMs() ?: return@mapNotNull null
        val stopEpochMs = obj.stringValue("stop_timestamp").toXtreamEpochMs() ?: return@mapNotNull null
        if (stopEpochMs <= startEpochMs || stopEpochMs <= nowEpochMs) return@mapNotNull null
        val title = decodeXtreamText(obj.stringValue("title")).ifBlank { "Untitled" }
        LiveTvProgramme(
            title = title,
            startEpochMs = startEpochMs,
            stopEpochMs = stopEpochMs,
            timeLabel = "${LiveTvClock.formatLocalTime(startEpochMs)} – ${LiveTvClock.formatLocalTime(stopEpochMs)}",
        )
    }.sortedBy(LiveTvProgramme::startEpochMs)
        .distinctBy { programme -> Triple(programme.startEpochMs, programme.stopEpochMs, programme.title) }
}

private fun String?.toXtreamEpochMs(): Long? {
    val value = this?.trim()?.toLongOrNull() ?: return null
    return if (value >= 10_000_000_000L) value else value * 1000L
}

private fun decodeXtreamText(value: String?): String {
    val raw = value?.trim().orEmpty()
    if (raw.isBlank()) return ""
    val decoded = runCatching {
        Base64.Default.decode(raw).decodeToString().trim()
    }.getOrNull()
    return decoded?.takeIf { candidate ->
        candidate.isNotBlank() &&
            '\uFFFD' !in candidate &&
            candidate.all { char -> char == '\n' || char == '\r' || char == '\t' || char.code >= 32 }
    } ?: raw
}

private object LiveTvRepositoryStalker {
    private var cachedSession: StalkerSession? = null

    fun clearSession() {
        cachedSession = null
    }

    suspend fun session(settings: LiveTvStalkerSettings): StalkerSession {
        cachedSession
            ?.takeIf { it.settings == settings && it.token.isNotBlank() }
            ?.let { return it }

        val token = request(settings, type = "stb", action = "handshake")
            .stalkerJs()
            .stringValue("token")
            .orEmpty()
            .trim()
        require(token.isNotBlank()) { "Stalker Portal token alınamadı." }
        return StalkerSession(settings = settings, token = token).also {
            cachedSession = it
        }
    }

    suspend fun getGenres(session: StalkerSession): Map<String, String> {
        val data = request(session.settings, session.token, type = "itv", action = "get_genres")
            .stalkerJs()
            .arrayValue("data")
        return data.associateNotNull { element ->
            val obj = element as? JsonObject ?: return@associateNotNull null
            val id = obj.stringValue("id") ?: obj.stringValue("alias") ?: return@associateNotNull null
            val title = obj.stringValue("title") ?: obj.stringValue("name") ?: return@associateNotNull null
            id to title
        }
    }

    suspend fun getChannels(
        session: StalkerSession,
        genres: Map<String, String>,
    ): List<LiveTvChannel> {
        val channels = mutableListOf<LiveTvChannel>()
        repeat(20) { pageIndex ->
            val page = pageIndex + 1
            val data = request(
                settings = session.settings,
                token = session.token,
                type = "itv",
                action = "get_ordered_list",
                extraParameters = mapOf("p" to page.toString()),
            ).stalkerJs().arrayValue("data")
            if (data.isEmpty()) return@repeat
            data.forEachIndexed { index, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                val name = obj.stringValue("name")
                    ?: obj.stringValue("title")
                    ?: return@forEachIndexed
                val command = obj.stringValue("cmd")
                    ?: obj.stringValue("mc_cmd")
                    ?: obj.stringValue("url")
                    ?: return@forEachIndexed
                val streamUrl = command.toStalkerPlayableUrl()
                if (streamUrl.isBlank()) return@forEachIndexed
                val genreId = obj.stringValue("tv_genre_id") ?: obj.stringValue("genre_id")
                channels += LiveTvChannel(
                    id = obj.stringValue("id") ?: "stalker-${page}-$index-${streamUrl.hashCode()}",
                    name = name,
                    streamUrl = streamUrl,
                    tvgId = obj.stringValue("xmltv_id") ?: obj.stringValue("tvg_id"),
                    logoUrl = obj.stringValue("logo") ?: obj.stringValue("logo_url"),
                    group = genreId?.let(genres::get).orEmpty(),
                    headers = playbackHeaders(session),
                    stalkerCommand = command,
                )
            }
        }
        return channels.distinctBy { it.id.ifBlank { it.streamUrl } }
    }

    suspend fun createLink(session: StalkerSession, command: String): String? {
        val data = request(
            settings = session.settings,
            token = session.token,
            type = "itv",
            action = "create_link",
            extraParameters = mapOf("cmd" to command),
        ).stalkerJs()
        return (data.stringValue("cmd") ?: data.stringValue("url") ?: data.stringValue("stream_url"))
            ?.toStalkerPlayableUrl()
            ?.takeIf { it.isNotBlank() }
    }

    fun playbackHeaders(session: StalkerSession): Map<String, String> =
        baseHeaders(session.settings) + mapOf(
            "Authorization" to "Bearer ${session.token}",
        )

    private suspend fun request(
        settings: LiveTvStalkerSettings,
        token: String? = null,
        type: String,
        action: String,
        extraParameters: Map<String, String> = emptyMap(),
    ): JsonObject {
        val parameters = buildMap {
            put("type", type)
            put("action", action)
            put("JsHttpRequest", "1-xml")
            if (!token.isNullOrBlank()) put("token", token)
            if (settings.username.isNotBlank()) put("login", settings.username)
            if (settings.password.isNotBlank()) put("password", settings.password)
            putAll(extraParameters)
        }
        val url = settings.portalEndpoint() + parameters.entries.joinToString(
            separator = "&",
            prefix = if (settings.portalEndpoint().contains("?")) "&" else "?",
        ) { (key, value) ->
            "${key.encodeURLParameter()}=${value.encodeURLParameter()}"
        }
        val payload = httpGetTextWithHeaders(url, baseHeaders(settings) + tokenHeader(token))
        return stalkerJson.parseToJsonElement(payload).jsonObject
    }

    private fun baseHeaders(settings: LiveTvStalkerSettings): Map<String, String> =
        mapOf(
            "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3",
            "X-User-Agent" to "Model: MAG254; Link: Ethernet",
            "Referer" to settings.portalBaseUrl(),
            "Cookie" to "mac=${settings.macAddress}; stb_lang=en; timezone=Europe%2FIstanbul",
        )

    private fun tokenHeader(token: String?): Map<String, String> =
        if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")
}

private val stalkerJson = Json { ignoreUnknownKeys = true; isLenient = true }

private fun LiveTvStalkerSettings.normalized(): LiveTvStalkerSettings =
    copy(
        portalUrl = portalUrl.trim().trimEnd('/'),
        macAddress = macAddress.trim().uppercase(),
        username = username.trim(),
        password = password.trim(),
    )

private fun LiveTvXtreamSettings.normalized(): LiveTvXtreamSettings =
    copy(
        serverUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
        username = username.trim(),
        password = password.trim(),
        selectedCategoryIds = selectedCategoryIds.map(String::trim).filter(String::isNotBlank).toSet(),
    )

private fun LiveTvXtreamSettings.playerApiEndpoint(): String =
    "${serverUrl.trim().trimEnd('/')}/player_api.php"

internal fun LiveTvXtreamSettings.xmlTvEndpoint(): String {
    val baseUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/')
    return "$baseUrl/xmltv.php?username=${username.trim().encodeURLParameter()}&password=${password.trim().encodeURLParameter()}"
}

private fun LiveTvXtreamSettings.liveStreamUrl(streamId: String, extension: String): String =
    buildString {
        append(serverUrl.trim().trimEnd('/'))
        append("/live/")
        append(username.encodeURLParameter())
        append("/")
        append(password.encodeURLParameter())
        append("/")
        append(streamId.encodeURLParameter())
        append(".")
        append(extension.trim().trimStart('.').ifBlank { "ts" })
    }

private fun LiveTvStalkerSettings.portalEndpoint(): String {
    val normalized = portalUrl.trim().trimEnd('/')
    return when {
        normalized.endsWith("portal.php", ignoreCase = true) -> normalized
        normalized.contains("portal.php?", ignoreCase = true) -> normalized
        else -> "$normalized/portal.php"
    }
}

private fun LiveTvStalkerSettings.portalBaseUrl(): String =
    portalUrl.trim().substringBefore("/portal.php").trimEnd('/') + "/c/"

private fun String.toStalkerPlayableUrl(): String =
    trim()
        .removePrefix("ffmpeg ")
        .removePrefix("auto ")
        .substringBefore(' ')
        .trim()

private fun JsonObject.stalkerJs(): JsonObject =
    (this["js"] as? JsonObject) ?: this

private fun JsonObject.stringValue(name: String): String? =
    (this[name] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.arrayValue(name: String): List<JsonElement> =
    (this[name] as? JsonArray)?.toList().orEmpty()

private fun JsonElement.jsonArrayOrEmpty(): List<JsonElement> =
    (this as? JsonArray)?.toList()
        ?: (this as? JsonObject)?.arrayValue("data")
        ?: emptyList()

private inline fun <K, V> Iterable<JsonElement>.associateNotNull(transform: (JsonElement) -> Pair<K, V>?): Map<K, V> =
    mapNotNull(transform).toMap()

internal fun parseM3uPlaylist(content: String): List<LiveTvChannel> =
    parseM3uPlaylistData(content).channels

internal data class ParsedM3uPlaylist(
    val channels: List<LiveTvChannel>,
    val epgUrls: List<String>,
)

internal fun parseM3uPlaylistData(content: String): ParsedM3uPlaylist {
    val channels = mutableListOf<LiveTvChannel>()
    val epgUrls = linkedSetOf<String>()
    var metadata: ParsedM3uMetadata? = null
    var pendingHeaders = emptyMap<String, String>()

    content.lineSequence().forEach { rawLine ->
        val line = rawLine.trim().removePrefix("\uFEFF")
        when {
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                val attributes = parseM3uAttributes(line)
                listOfNotNull(attributes["url-tvg"], attributes["x-tvg-url"])
                    .flatMap { it.split(',', ';') }
                    .map(String::trim)
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .forEach(epgUrls::add)
            }

            line.startsWith("#EXTINF", ignoreCase = true) -> {
                metadata = parseExtInf(line)
            }

            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + ("User-Agent" to line.substringAfter('=').trim())
            }

            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + ("Referer" to line.substringAfter('=').trim())
            }

            line.startsWith("#EXTHTTP:", ignoreCase = true) -> {
                pendingHeaders = pendingHeaders + parseExtHttpHeaders(line.substringAfter(':'))
            }

            line.isNotEmpty() && !line.startsWith("#") -> {
                val parsedUrl = parseStreamUrl(line)
                val current = metadata ?: ParsedM3uMetadata(
                    name = "Kanal ${channels.size + 1}",
                    tvgId = null,
                    logoUrl = null,
                    group = "",
                )
                channels += LiveTvChannel(
                    id = "${parsedUrl.url}#${channels.size}",
                    name = current.name.ifBlank { "Kanal ${channels.size + 1}" },
                    streamUrl = parsedUrl.url,
                    tvgId = current.tvgId,
                    logoUrl = current.logoUrl,
                    group = current.group,
                    headers = defaultM3uStreamHeaders(parsedUrl.url) + pendingHeaders + parsedUrl.headers,
                    streamType = parsedUrl.url.inferM3uStreamType(),
                )
                metadata = null
                pendingHeaders = emptyMap()
            }
        }
    }

    return ParsedM3uPlaylist(
        channels = channels
            .distinctBy { it.streamUrl }
            .filterNot { isLikelyCategoryHeading(it.name) },
        epgUrls = epgUrls.toList(),
    )
}

private data class ParsedM3uMetadata(
    val name: String,
    val tvgId: String?,
    val logoUrl: String?,
    val group: String,
)

private data class ParsedStreamUrl(
    val url: String,
    val headers: Map<String, String>,
)

private val m3uAttributeRegex = Regex("""([\w-]+)="([^"]*)"""")

private fun parseExtInf(line: String): ParsedM3uMetadata {
    val attributes = parseM3uAttributes(line.substringBeforeLast(',', line))
    val displayName = line.substringAfterLast(',', "").trim()
        .ifBlank { attributes["tvg-name"].orEmpty() }

    return ParsedM3uMetadata(
        name = displayName,
        tvgId = attributes["tvg-id"]?.takeIf(String::isNotBlank),
        logoUrl = attributes["tvg-logo"]?.takeIf { it.isNotBlank() },
        group = attributes["group-title"].orEmpty(),
    )
}

private fun parseM3uAttributes(line: String): Map<String, String> =
    m3uAttributeRegex
        .findAll(line)
        .associate { match -> match.groupValues[1].lowercase() to match.groupValues[2].trim() }

private fun parseStreamUrl(line: String): ParsedStreamUrl {
    val url = line.substringBefore('|').trim()
    val headers = line.substringAfter('|', "")
        .split('&')
        .mapNotNull { entry ->
            val key = entry.substringBefore('=').trim()
            val value = entry.substringAfter('=', "").trim()
            if (key.isBlank() || value.isBlank()) null else key to value
        }
        .toMap()
    return ParsedStreamUrl(url = url, headers = headers)
}

private fun parseExtHttpHeaders(value: String): Map<String, String> {
    val trimmed = value.trim().removePrefix("{").removeSuffix("}")
    return trimmed.split(',')
        .mapNotNull { entry ->
            val key = entry.substringBefore(':').trim().trim('"')
            val headerValue = entry.substringAfter(':', "").trim().trim('"')
            if (key.isBlank() || headerValue.isBlank()) null else key to headerValue
        }
        .toMap()
}

private fun defaultM3uStreamHeaders(url: String): Map<String, String> {
    if (!url.startsWith("http://") && !url.startsWith("https://")) return emptyMap()
    return M3U_STREAM_REQUEST_HEADERS
}

private fun directStreamChannel(url: String): LiveTvChannel =
    LiveTvChannel(
        id = "direct-${url.hashCode()}",
        name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Live stream" },
        streamUrl = url,
        group = "Direct stream",
        headers = defaultM3uStreamHeaders(url),
        streamType = url.inferM3uStreamType(),
    )

private fun String.looksLikeDirectVideoUrl(): Boolean {
    val normalized = substringBefore('#').substringBefore('?').lowercase()
    if (normalized.endsWith(".m3u") || normalized.endsWith(".m3u8")) return false
    return listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".mpeg", ".mpg")
        .any(normalized::endsWith)
}

private fun String.inferM3uStreamType(): String? {
    val normalized = substringBefore('#').substringBefore('?').lowercase()
    return when {
        normalized.endsWith(".m3u8") -> "hls"
        normalized.endsWith(".mkv") -> "matroska"
        normalized.endsWith(".mp4") || normalized.endsWith(".m4v") -> "mp4"
        normalized.endsWith(".webm") -> "webm"
        normalized.endsWith(".ts") || normalized.endsWith(".mts") || normalized.endsWith(".m2ts") -> "mpegts"
        else -> null
    }
}

internal fun String.looksLikeHlsManifest(): Boolean =
    lineSequence().any { it.trim().startsWith("#EXT-X-", ignoreCase = true) }

private val M3U_PLAYLIST_REQUEST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
    "Accept" to "application/x-mpegURL, application/vnd.apple.mpegurl, audio/mpegurl, text/plain, */*",
)

private val M3U_STREAM_REQUEST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
)

internal fun isLikelyCategoryHeading(name: String): Boolean {
    val normalized = name.trim()
    return normalized.length >= 8 && Regex("""^\s*#+\s*.+\s*#+\s*$""").matches(normalized)
}

internal expect object LiveTvClock {
    fun nowEpochMs(): Long
    fun formatLocalTime(epochMs: Long): String
    fun parseXmlTvTimestamp(value: String): Long?
}

internal fun parseCurrentXmlTvProgrammes(
    content: String,
    nowEpochMs: Long = LiveTvClock.nowEpochMs(),
): Map<String, LiveTvProgramme> =
    currentXmlTvProgrammes(
        programmesByChannel = parseXmlTvProgrammeSchedule(content, nowEpochMs),
        nowEpochMs = nowEpochMs,
    )
