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
import androidx.media3.common.PlaybackParameters
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
import com.apexlions.film2.player.catalog.ExternalMediaTrack
import com.apexlions.film2.player.catalog.PlayableAsset
import com.apexlions.film2.player.catalog.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

sealed interface PlaybackUiState {
    data object Loading : PlaybackUiState
    data class Ready(val title: Title, val asset: PlayableAsset) : PlaybackUiState
    data class Error(val message: String) : PlaybackUiState
}

/**
 * Direct progressive player.
 *
 * Ana video tek ExoPlayer'da kalir. Sidecar ses ikinci, audio-only ExoPlayer'da oynar.
 * Iki kaynak MergingMediaSource ile birlestirilmez; dolayisiyla progressive MP4/AAC seek
 * noktalarinin farkli olmasi ana oynaticiyi cokertmez.
 *
 * Sidecar audio normal oynatma boyunca periyodik olarak seek EDILMEZ. Konum yalnizca
 * kullanici gercek bir seek yaptiginda ve ses ilk hazirlandiginda cok buyuk bir fark
 * olusmussa tek sefer duzeltilir. Boylece ses decoder/buffer'i tekrar tekrar sifirlanmaz.
 */
class PlayerViewModel(
    application: Application,
    private val titleId: String,
    private val seasonNumber: Int?,
    private val episodeNumber: Int?,
    private val repository: CatalogRepository,
) : AndroidViewModel(application) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("film2-android-player/1.1.2-direct")
        .setAllowCrossProtocolRedirects(true)

    val player: ExoPlayer = ExoPlayer.Builder(application).build()
    private val externalAudioPlayer: ExoPlayer = ExoPlayer.Builder(application).build().apply {
        volume = 1f
    }

    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _currentTracks = MutableStateFlow<Tracks>(Tracks.EMPTY)
    val currentTracks: StateFlow<Tracks> = _currentTracks.asStateFlow()

    private val _selectedExternalAudioIndex = MutableStateFlow<Int?>(null)
    val selectedExternalAudioIndex: StateFlow<Int?> = _selectedExternalAudioIndex.asStateFlow()

    private var activeAsset: PlayableAsset? = null
    private var directPlayback = false
    private var autoExternalSelectionDone = false

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                _currentTracks.value = tracks
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                syncExternalAudioPlayback()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        maybeAutoSelectExternalAudio()
                        syncExternalAudioPlayback()
                    }
                    Player.STATE_ENDED -> externalAudioPlayer.pause()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (_selectedExternalAudioIndex.value != null) {
                    seekExternalToMain()
                }
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                externalAudioPlayer.playbackParameters = playbackParameters
            }

            override fun onPlayerError(error: PlaybackException) {
                showPlaybackError(error)
            }
        })

        externalAudioPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                showPlaybackError(error, prefix = "Harici ses hatasi")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Ses dosyasi ilk hazirlanirken ana video cok ilerlediyse BIR KEZ
                    // yakala. Seek'in kendisinin olusturdugu BUFFERING -> READY dongusunde
                    // fark artik kucuk olacagi icin ikinci bir seek tetiklenmez.
                    if (abs(externalAudioPlayer.currentPosition - player.currentPosition) > STARTUP_DRIFT_CORRECTION_MS) {
                        seekExternalToMain()
                    }
                    player.volume = 0f
                    externalAudioPlayer.volume = 1f
                    syncExternalAudioPlayback()
                }
            }
        })

        resolveAndLoad()
    }

    private fun showPlaybackError(error: PlaybackException, prefix: String = "Oynatma hatasi") {
        val cause = error.cause?.message?.takeIf { it.isNotBlank() }
        val detail = cause ?: error.message ?: "Bilinmeyen Media3 hatasi"
        _uiState.value = PlaybackUiState.Error("$prefix (${error.errorCodeName}): $detail")
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
        deactivateExternalAudio(restoreMainAudio = true)
        autoExternalSelectionDone = false

        val directUrl = asset.videoUrl?.takeIf { it.isNotBlank() }
        if (directUrl != null) {
            directPlayback = true
            playDirectAsset(asset, directUrl)
            return
        }

        directPlayback = false
        val legacyHls = asset.masterPlaylistUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Asset icinde videoUrl veya masterPlaylistUrl yok")
        val mediaItem = MediaItem.Builder()
            .setUri(legacyHls)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
            .createMediaSource(mediaItem)
        startMain(mediaSource)
    }

    private fun playDirectAsset(asset: PlayableAsset, videoUrl: String) {
        val subtitleConfigurations = asset.externalSubtitleTracks.mapIndexed { index, track ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(track.url))
                .setId("sidecar-sub-$index-${track.language}")
                .setMimeType(track.mimeType ?: MimeTypes.TEXT_VTT)
                .setLanguage(track.language)
                .setLabel(track.label ?: track.language)
                .build()
        }

        val videoItem = MediaItem.Builder()
            .setUri(videoUrl)
            .setSubtitleConfigurations(subtitleConfigurations)
            .build()

        val baseSource = DefaultMediaSourceFactory(httpDataSourceFactory)
            .createMediaSource(videoItem)
        startMain(baseSource)
    }

    private fun startMain(mediaSource: MediaSource) {
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
        deactivateExternalAudio(restoreMainAudio = true)
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(override)
            .build()
    }

    fun selectExternalAudio(index: Int) {
        val asset = activeAsset ?: return
        val track = asset.externalAudioTracks.getOrNull(index) ?: return
        activateExternalAudio(index, track)
    }

    private fun activateExternalAudio(index: Int, track: ExternalMediaTrack) {
        if (!directPlayback) return

        _selectedExternalAudioIndex.value = index

        // Katalogda onceki Studio surumlerinden kalmis yanlis MIME olabilir. Ozellikle
        // ham .aac bir MP4 container degildir. MIME'i zorlamak yerine Media3'e URL/container
        // uzerinden extractor secimi birakiliyor; mevcut yukleme yeniden gerekmez.
        val item = MediaItem.Builder()
            .setUri(track.url)
            .build()

        externalAudioPlayer.playWhenReady = false
        externalAudioPlayer.stop()
        externalAudioPlayer.clearMediaItems()
        externalAudioPlayer.setMediaItem(item)
        externalAudioPlayer.playbackParameters = player.playbackParameters
        externalAudioPlayer.volume = 1f
        externalAudioPlayer.seekTo(player.currentPosition.coerceAtLeast(0L))
        externalAudioPlayer.prepare()

        // Ana videoda zaten ses varsa sidecar hazir olana kadar onu birak. Sidecar
        // STATE_READY oldugunda ana player mute edilip secilen ses baslatilir.
        if (player.currentTracks.groups.none { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }) {
            player.volume = 0f
        }
    }

    private fun deactivateExternalAudio(restoreMainAudio: Boolean) {
        _selectedExternalAudioIndex.value = null
        externalAudioPlayer.playWhenReady = false
        externalAudioPlayer.pause()
        externalAudioPlayer.stop()
        externalAudioPlayer.clearMediaItems()
        if (restoreMainAudio) player.volume = 1f
    }

    private fun maybeAutoSelectExternalAudio() {
        if (autoExternalSelectionDone || !directPlayback || _selectedExternalAudioIndex.value != null) return
        val asset = activeAsset ?: return
        if (asset.externalAudioTracks.isEmpty()) return

        val hasInternalAudio = player.currentTracks.groups.any { group ->
            group.type == C.TRACK_TYPE_AUDIO && group.length > 0
        }
        if (!hasInternalAudio) {
            autoExternalSelectionDone = true
            activateExternalAudio(0, asset.externalAudioTracks[0])
        }
    }

    private fun syncExternalAudioPlayback() {
        if (_selectedExternalAudioIndex.value == null) return
        if (externalAudioPlayer.mediaItemCount == 0) return

        if (player.isPlaying) {
            if (externalAudioPlayer.playbackState == Player.STATE_READY) {
                externalAudioPlayer.play()
            } else {
                externalAudioPlayer.playWhenReady = true
            }
        } else {
            externalAudioPlayer.pause()
        }
    }

    private fun seekExternalToMain() {
        if (_selectedExternalAudioIndex.value == null) return
        if (externalAudioPlayer.mediaItemCount == 0) return
        externalAudioPlayer.seekTo(player.currentPosition.coerceAtLeast(0L))
    }

    fun selectSubtitleTrack(group: Tracks.Group, trackIndex: Int) {
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }

    fun disableSubtitles() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun setAudioTrackAuto() {
        deactivateExternalAudio(restoreMainAudio = true)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    override fun onCleared() {
        externalAudioPlayer.release()
        player.release()
        super.onCleared()
    }

    class Factory(
        private val application: Application,
        private val titleId: String,
        private val seasonNumber: Int?,
        private val episodeNumber: Int?,
        private val repository: CatalogRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(PlayerViewModel::class.java))
            return PlayerViewModel(application, titleId, seasonNumber, episodeNumber, repository) as T
        }
    }

    companion object {
        private const val STARTUP_DRIFT_CORRECTION_MS = 1_500L
    }
}
