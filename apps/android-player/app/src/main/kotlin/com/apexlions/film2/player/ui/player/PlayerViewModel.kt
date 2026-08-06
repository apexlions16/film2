package com.apexlions.film2.player.ui.player

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
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
 * Owns the ExoPlayer instance for the lifetime of the player screen. Track switching
 * (audio + subtitle language) goes through Media3's TrackSelectionParameters/Tracks API
 * per the spec — see [selectAudioTrack]/[selectSubtitleTrack]/[disableSubtitles], all of
 * which mutate `player.trackSelectionParameters` and can be called mid-playback.
 */
class PlayerViewModel(
    application: Application,
    private val titleId: String,
    private val seasonNumber: Int?,
    private val episodeNumber: Int?,
    private val repository: CatalogRepository,
) : AndroidViewModel(application) {

    private val httpDataSourceFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("film2-android-player")

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
            playAsset(asset)
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
        val mediaItem = MediaItem.Builder()
            .setUri(asset.masterPlaylistUrl)
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_M3U8)
            .build()
        val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
            .createMediaSource(mediaItem)
        player.setMediaSource(mediaSource)
        player.prepare()
        player.playWhenReady = true
    }

    /** Switches the active audio track. Safe to call mid-playback. */
    fun selectAudioTrack(group: Tracks.Group, trackIndex: Int) {
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(override)
            .build()
    }

    /** Switches the active subtitle track. Safe to call mid-playback. */
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
