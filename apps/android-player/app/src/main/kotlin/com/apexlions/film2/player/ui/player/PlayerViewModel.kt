@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.apexlions.film2.player.ui.player

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import com.apexlions.film2.player.catalog.CatalogRepository
import com.apexlions.film2.player.catalog.DemoContent
import com.apexlions.film2.player.catalog.PlayableAsset
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.catalog.VideoVariant
import com.apexlions.film2.player.userdata.PlaybackRecord
import com.apexlions.film2.player.userdata.UserLibraryRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface PlaybackUiState {
    data object Loading : PlaybackUiState
    data class Ready(val title: Title, val asset: PlayableAsset) : PlaybackUiState
    data class Error(val message: String) : PlaybackUiState
}

data class PlayerRuntimeState(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
)

/**
 * Tek ExoPlayer + tek MP4 timeline'i. Kalite degisimi ayri MP4 URL'leri arasinda olur;
 * sesler MP4'lerin icindedir ve VTT side-load edilir.
 *
 * Oynatma konumu, kalite, ses ve altyazi tercihi cihazda kalici olarak saklanir.
 */
class PlayerViewModel(
    application: Application,
    private val titleId: String,
    private val seasonNumber: Int?,
    private val episodeNumber: Int?,
    private val repository: CatalogRepository,
    private val userLibrary: UserLibraryRepository,
) : AndroidViewModel(application) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("film2-android-player/1.3.0-cinematic")
        .setAllowCrossProtocolRedirects(true)

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _currentTracks = MutableStateFlow<Tracks>(Tracks.EMPTY)
    val currentTracks: StateFlow<Tracks> = _currentTracks.asStateFlow()

    private val _selectedQualityHeight = MutableStateFlow<Int?>(null)
    val selectedQualityHeight: StateFlow<Int?> = _selectedQualityHeight.asStateFlow()

    private val _runtime = MutableStateFlow(PlayerRuntimeState())
    val runtime: StateFlow<PlayerRuntimeState> = _runtime.asStateFlow()

    private val savedRecord: PlaybackRecord? = userLibrary.record(titleId, seasonNumber, episodeNumber)
    private var activeAsset: PlayableAsset? = null
    private var currentVideoUrl: String? = null
    private var preferredAudioLanguage: String? = savedRecord?.audioLanguage
    private var preferredSubtitleLanguage: String? = savedRecord?.subtitleLanguage
    private var subtitlesDisabled: Boolean = savedRecord?.subtitlesDisabled ?: false
    private var lastPersistAtMs = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                _currentTracks.value = tracks
                updateRuntime()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateRuntime()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateRuntime()
                if (playbackState == Player.STATE_ENDED) persistNow()
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                updateRuntime()
                if (reason == Player.DISCONTINUITY_REASON_SEEK) persistNow()
            }

            override fun onPlayerError(error: PlaybackException) {
                persistNow()
                val cause = error.cause?.message?.takeIf { it.isNotBlank() }
                val detail = cause ?: error.message ?: "Bilinmeyen Media3 hatasi"
                _uiState.value = PlaybackUiState.Error(
                    "Oynatma hatasi (${error.errorCodeName}): $detail",
                )
            }
        })

        viewModelScope.launch {
            while (isActive) {
                updateRuntime()
                val now = System.currentTimeMillis()
                if (now - lastPersistAtMs >= PROGRESS_SAVE_INTERVAL_MS) {
                    persistNow()
                }
                delay(RUNTIME_TICK_MS)
            }
        }

        resolveAndLoad()
    }

    private fun resolveAndLoad() {
        viewModelScope.launch {
            _uiState.value = PlaybackUiState.Loading
            val resolved = resolve()
            if (resolved == null) {
                _uiState.value = PlaybackUiState.Error("Bu icerik oynatilamiyor (medya bulunamadi)")
                return@launch
            }
            val (title, asset) = resolved
            activeAsset = asset
            _uiState.value = PlaybackUiState.Ready(title, asset)
            runCatching { playAsset(asset) }
                .onFailure { t ->
                    _uiState.value = PlaybackUiState.Error(
                        "Oynatici hazirlanamadi: ${t.message ?: t::class.java.simpleName}",
                    )
                }
        }
    }

    private suspend fun resolve(): Pair<Title, PlayableAsset>? {
        val title = if (titleId == DemoContent.DEMO_TITLE_ID) {
            DemoContent.demoTitle
        } else {
            repository.fetchTitle(titleId) ?: return null
        }

        return if (seasonNumber != null && episodeNumber != null) {
            val episode = title.seasons
                ?.firstOrNull { it.seasonNumber == seasonNumber }
                ?.episodes
                ?.firstOrNull { it.episodeNumber == episodeNumber }
            val asset = episode?.asset ?: return null
            title to asset
        } else {
            val asset = title.asset ?: return null
            title to asset
        }
    }

    private fun playAsset(asset: PlayableAsset) {
        val resumePosition = savedRecord?.resumePositionMs() ?: 0L
        val savedQuality = savedRecord?.qualityHeight
        val selectedVariant = asset.videoVariants.firstOrNull { it.height == savedQuality }
            ?: asset.videoVariants.maxByOrNull { it.height }

        if (selectedVariant != null) {
            _selectedQualityHeight.value = selectedVariant.height
            currentVideoUrl = selectedVariant.url
            playDirectAsset(asset, selectedVariant.url, resumePosition, true)
            return
        }

        val directUrl = asset.videoUrl?.takeIf { it.isNotBlank() }
        if (directUrl != null) {
            currentVideoUrl = directUrl
            _selectedQualityHeight.value = savedQuality
            playDirectAsset(asset, directUrl, resumePosition, true)
            return
        }

        currentVideoUrl = null
        _selectedQualityHeight.value = null
        val legacyHls = asset.masterPlaylistUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Asset icinde videoUrl veya masterPlaylistUrl yok")
        val mediaItem = MediaItem.Builder()
            .setUri(legacyHls)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
            .createMediaSource(mediaItem)
        start(mediaSource, resumePosition, true)
    }

    private fun playDirectAsset(
        asset: PlayableAsset,
        videoUrl: String,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        val subtitleConfigurations = asset.externalSubtitleTracks.mapIndexed { index, track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setId("sidecar-sub-$index-${track.language}")
                .setMimeType(track.mimeType ?: MimeTypes.TEXT_VTT)
                .setLanguage(track.language)
                .setLabel(track.label ?: track.language)
                .build()
        }

        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .setSubtitleConfigurations(subtitleConfigurations)
            .build()

        val mediaSource = DefaultMediaSourceFactory(httpDataSourceFactory)
            .createMediaSource(mediaItem)
        start(mediaSource, startPositionMs, playWhenReady)
    }

    private fun start(mediaSource: MediaSource, startPositionMs: Long, shouldPlay: Boolean) {
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        player.setMediaSource(mediaSource)
        applyLanguagePreferences()
        player.prepare()
        if (startPositionMs > 0L) player.seekTo(startPositionMs)
        player.playWhenReady = shouldPlay
        updateRuntime()
    }

    private fun applyLanguagePreferences() {
        var builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitlesDisabled)

        preferredAudioLanguage?.let { builder = builder.setPreferredAudioLanguage(it) }
        if (!subtitlesDisabled) {
            preferredSubtitleLanguage?.let { builder = builder.setPreferredTextLanguage(it) }
        }
        player.trackSelectionParameters = builder.build()
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
        updateRuntime()
    }

    fun seekBy(deltaMs: Long) {
        val duration = safeDuration()
        val target = (player.currentPosition + deltaMs).coerceAtLeast(0L)
        player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
        updateRuntime()
    }

    fun seekTo(positionMs: Long) {
        val duration = safeDuration()
        val target = if (duration > 0L) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
        player.seekTo(target)
        updateRuntime()
    }

    fun selectQuality(variant: VideoVariant) {
        val asset = activeAsset ?: return
        if (variant.url == currentVideoUrl) return

        val position = player.currentPosition.coerceAtLeast(0L)
        val shouldPlay = player.playWhenReady
        currentVideoUrl = variant.url
        _selectedQualityHeight.value = variant.height
        playDirectAsset(asset, variant.url, position, shouldPlay)
        persistNow()
    }

    /** MP4 icindeki ses track'ini native Media3 secimiyle degistirir. */
    fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
        preferredAudioLanguage = group.getTrackFormat(trackIndex).language
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(override)
            .build()
        persistNow()
    }

    fun selectSubtitleTrack(group: Tracks.Group, trackIndex: Int) {
        subtitlesDisabled = false
        preferredSubtitleLanguage = group.getTrackFormat(trackIndex).language
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        persistNow()
    }

    fun disableSubtitles() {
        subtitlesDisabled = true
        preferredSubtitleLanguage = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        persistNow()
    }

    fun setAudioTrackAuto() {
        preferredAudioLanguage = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        persistNow()
    }

    /** Activity arka plana giderken veya kullanici geri donerken cagrilir. */
    fun pauseAndPersist() {
        player.pause()
        updateRuntime()
        persistNow()
    }

    fun persistNow() {
        val duration = safeDuration()
        if (duration <= 0L) return
        val position = player.currentPosition.coerceAtLeast(0L)
        userLibrary.savePlaybackSnapshot(
            titleId = titleId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            positionMs = position,
            durationMs = duration,
            audioLanguage = preferredAudioLanguage,
            subtitleLanguage = preferredSubtitleLanguage,
            subtitlesDisabled = subtitlesDisabled,
            qualityHeight = _selectedQualityHeight.value,
        )
        lastPersistAtMs = System.currentTimeMillis()
    }

    private fun updateRuntime() {
        val duration = safeDuration()
        _runtime.value = PlayerRuntimeState(
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = duration,
            bufferedPositionMs = player.bufferedPosition.coerceAtLeast(0L),
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
        )
    }

    private fun safeDuration(): Long {
        val raw = player.duration
        return if (raw == C.TIME_UNSET || raw < 0L) 0L else raw
    }

    override fun onCleared() {
        persistNow()
        player.release()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val titleId: String,
        private val seasonNumber: Int?,
        private val episodeNumber: Int?,
        private val repository: CatalogRepository,
        private val userLibrary: UserLibraryRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayerViewModel::class.java))
            return PlayerViewModel(
                application = application,
                titleId = titleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                repository = repository,
                userLibrary = userLibrary,
            ) as T
        }
    }

    companion object {
        private const val RUNTIME_TICK_MS = 500L
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
    }
}
