@file:OptIn(ExperimentalMaterial3Api::class)

package com.apexlions.film2.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.apexlions.film2.player.Film2PlayerApplication
import com.apexlions.film2.player.ui.common.CatalogErrorState

@Composable
fun PlayerScreen(
    titleId: String,
    seasonNumber: Int?,
    episodeNumber: Int?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val application = context.applicationContext as Film2PlayerApplication
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(
            application = application,
            titleId = titleId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            repository = application.catalogRepository,
        ),
    )

    val uiState by viewModel.uiState.collectAsState()
    val tracks by viewModel.currentTracks.collectAsState()
    var trackSheetVisible by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        when (val state = uiState) {
            is PlaybackUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            is PlaybackUiState.Error -> CatalogErrorState(message = state.message, onRetry = onBack)
            is PlaybackUiState.Ready -> {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = viewModel.player
                            useController = true
                            setShowSubtitleButton(false) // custom track sheet replaces the built-in picker
                        }
                    },
                )

                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
                    }
                    IconButton(onClick = { trackSheetVisible = true }) {
                        Icon(Icons.Filled.Subtitles, contentDescription = "Ses ve altyazi", tint = Color.White)
                    }
                }

                if (trackSheetVisible) {
                    TrackSelectionSheet(
                        tracks = tracks,
                        onSelectAudio = { group, index ->
                            viewModel.selectAudioTrack(group, index)
                        },
                        onSelectSubtitle = { group, index ->
                            viewModel.selectSubtitleTrack(group, index)
                        },
                        onDisableSubtitles = {
                            viewModel.disableSubtitles()
                        },
                        onDismiss = { trackSheetVisible = false },
                    )
                }
            }
        }
    }
}
