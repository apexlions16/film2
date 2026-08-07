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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface PlaybackUiState {
    data object Loading : PlaybackUiState
    data class Ready(val title: Title, val asset: PlayableAsset) : PlaybackUiState
    data class Error(val message: String) : PlaybackUiState
}

/**
 * Tek oynaticili medya modeli.
 *
 * Yeni medya pipeline'i video + tum sesleri FFmpeg stream-copy ile tek MP4'e mux eder.
 * Bu nedenle Player tarafinda ikinci ExoPlayer, MergingMediaSource veya sidecar AAC
 * senkronizasyonu YOKTUR. Seek ve ses degistirme ayni MP4 timeline'i icinde Media3'ün
 * native track secimiyle yapilir. VTT/SRT altyazilar side-load edilmeye devam eder.
 */
class PlayerViewModel(
    application: Application,
    private val titleId: String,
    private val seasonNumber: Int?,
    private val episodeNumber: Int?,
    private val repository: CatalogRepository,
) : AndroidViewModel(application) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("film2-android-player/1.1.3-single-mp4")
        .setAllowCrossProtocolRedirects(true)

    val player: ExoPlayer = ExoPlayer.Builder(application).build()

    private val _uiState = MutableStateFlow<PlaybackUiState>(PlaybackUiState.Loading)
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private val _currentTracks = MutableStateFlow<Tracks>(Tracks.EMPTY)
    val currentTracks: StateFlow<Tracks> = _currentTracks.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                _currentTracks.value = tracks
            }

            override fun onPlayerError(error: PlaybackException) {
                val cause = error.cause?.message?.takeIf { it.isNotBlank() }
                val detail = cause ?: error.message ?: "Bilinmeyen Media3 hatasi"
                _uiState.value = PlaybackUiState.Error(
                    "Oynatma hatasi (${error.errorCodeName}): $detail",
                )
            }
        })
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
        val directUrl = asset.videoUrl?.takeIf { it.isNotBlank() }
        if (directUrl != null) {
            playDirectAsset(asset, directUrl)
            return
        }

        val legacyHls = asset.masterPlaylistUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Asset icinde videoUrl veya masterPlaylistUrl yok")
        val mediaItem = MediaItem.Builder()
            .setUri(legacyHls)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .build()
        val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
            .createMediaSource(mediaItem)
        start(mediaSource)
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

        val mediaItem = MediaItem.Builder()
            .setUri(videoUrl)
            .setSubtitleConfigurations(subtitleConfigurations)
            .build()

        val mediaSource = DefaultMediaSourceFactory(httpDataSourceFactory)
            .createMediaSource(mediaItem)
        start(mediaSource)
    }

    private fun start(mediaSource: MediaSource) {
        player.stop()
        player.clearMediaItems()
        player.volume = 1f
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    /** MP4 icindeki ses track'ini native Media3 secimiyle degistirir. */
    fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setOverrideForType(override)
            .build()
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
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
    }

    override fun onCleared() {
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
}
