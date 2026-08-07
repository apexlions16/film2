@file:OptIn(ExperimentalMaterial3Api::class)

package com.apexlions.film2.player.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Color as AndroidColor
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.apexlions.film2.player.Film2PlayerApplication
import com.apexlions.film2.player.ui.common.CatalogErrorState
import com.apexlions.film2.player.userdata.PlaybackAppearanceState
import com.apexlions.film2.player.userdata.SubtitleBackground
import com.apexlions.film2.player.userdata.SubtitleEdge
import com.apexlions.film2.player.userdata.SubtitleTextColor
import com.apexlions.film2.player.userdata.VideoLayoutMode
import kotlinx.coroutines.delay

private val ProgressRed = Color(0xFFE50914)

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
    val activity = remember(context) { context.findActivity() }
    val viewModel: PlayerViewModel = viewModel(
        factory = PlayerViewModel.Factory(
            application = application,
            titleId = titleId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            repository = application.catalogRepository,
            userLibrary = application.userLibraryRepository,
        ),
    )

    val uiState by viewModel.uiState.collectAsState()
    val tracks by viewModel.currentTracks.collectAsState()
    val selectedQualityHeight by viewModel.selectedQualityHeight.collectAsState()
    val runtime by viewModel.runtime.collectAsState()
    val appearance by application.playbackAppearanceRepository.state.collectAsState()
    val appearanceRepository = application.playbackAppearanceRepository

    var trackSheetVisible by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionNonce by remember { mutableIntStateOf(0) }

    fun interact() {
        controlsVisible = true
        interactionNonce++
    }

    fun leavePlayer() {
        viewModel.persistNow()
        onBack()
    }

    BackHandler(onBack = ::leavePlayer)

    DisposableEffect(activity) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        if (window != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        onDispose {
            viewModel.pauseAndPersist()
            if (window != null) {
                WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, false)
            }
            if (activity != null && previousOrientation != null) activity.requestedOrientation = previousOrientation
        }
    }

    LaunchedEffect(controlsVisible, runtime.isPlaying, interactionNonce, trackSheetVisible) {
        if (controlsVisible && runtime.isPlaying && !trackSheetVisible) {
            delay(3_800L)
            controlsVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        controlsVisible = !controlsVisible
                        interactionNonce++
                    },
                    onDoubleTap = { offset ->
                        if (offset.x < size.width / 2f) viewModel.seekBy(-10_000L) else viewModel.seekBy(10_000L)
                        interact()
                    },
                )
            },
    ) {
        when (val state = uiState) {
            is PlaybackUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }

            is PlaybackUiState.Error -> CatalogErrorState(message = state.message, onRetry = ::leavePlayer)

            is PlaybackUiState.Ready -> {
                val videoModifier = when (appearance.videoLayoutMode) {
                    VideoLayoutMode.RATIO_16_9 -> Modifier.align(Alignment.Center).fillMaxWidth(0.96f).aspectRatio(16f / 9f)
                    VideoLayoutMode.RATIO_4_3 -> Modifier.align(Alignment.Center).fillMaxWidth(0.72f).aspectRatio(4f / 3f)
                    VideoLayoutMode.RATIO_21_9 -> Modifier.align(Alignment.Center).fillMaxWidth().aspectRatio(21f / 9f)
                    else -> Modifier.fillMaxSize()
                }
                AndroidView(
                    modifier = videoModifier,
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = viewModel.player
                            useController = false
                            keepScreenOn = true
                            setShowSubtitleButton(false)
                            applyPlayerAppearance(this, appearance)
                        }
                    },
                    update = {
                        it.player = viewModel.player
                        applyPlayerAppearance(it, appearance)
                    },
                )

                if (runtime.isBuffering) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.align(Alignment.Center).size(38.dp),
                    )
                }

                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    CinematicControls(
                        title = state.title.title,
                        episodeLabel = episodeLabel(state.title, seasonNumber, episodeNumber),
                        runtime = runtime,
                        qualityLabel = selectedQualityHeight?.let { "${it}p" } ?: "Kalite",
                        onBack = ::leavePlayer,
                        onTogglePlay = { viewModel.togglePlayPause(); interact() },
                        onRewind = { viewModel.seekBy(-10_000L); interact() },
                        onForward = { viewModel.seekBy(10_000L); interact() },
                        onSeek = { viewModel.seekTo(it); interact() },
                        onOpenSettings = { trackSheetVisible = true; interact() },
                    )
                }

                if (trackSheetVisible) {
                    TrackSelectionSheet(
                        tracks = tracks,
                        videoVariants = state.asset.videoVariants,
                        selectedQualityHeight = selectedQualityHeight,
                        appearance = appearance,
                        onSelectQuality = { variant -> viewModel.selectQuality(variant); interact() },
                        onSelectAudio = { group, index -> viewModel.selectAudioTrack(group, index); interact() },
                        onSelectSubtitle = { group, index -> viewModel.selectSubtitleTrack(group, index); interact() },
                        onDisableSubtitles = { viewModel.disableSubtitles(); interact() },
                        onSubtitleSize = appearanceRepository::setSubtitleSizeScale,
                        onSubtitlePosition = appearanceRepository::setSubtitleBottomPaddingFraction,
                        onSubtitleTextColor = appearanceRepository::setSubtitleTextColor,
                        onSubtitleBackground = appearanceRepository::setSubtitleBackground,
                        onSubtitleEdge = appearanceRepository::setSubtitleEdge,
                        onVideoLayout = appearanceRepository::setVideoLayoutMode,
                        onResetSubtitles = appearanceRepository::resetSubtitles,
                        onDismiss = { trackSheetVisible = false; interact() },
                    )
                }
            }
        }
    }
}

private fun applyPlayerAppearance(view: PlayerView, appearance: PlaybackAppearanceState) {
    view.resizeMode = when (appearance.videoLayoutMode) {
        VideoLayoutMode.SCREEN_CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoLayoutMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    val foreground = when (appearance.subtitleTextColor) {
        SubtitleTextColor.WHITE -> AndroidColor.WHITE
        SubtitleTextColor.YELLOW -> AndroidColor.rgb(255, 235, 59)
        SubtitleTextColor.CYAN -> AndroidColor.rgb(128, 222, 234)
    }
    val background = when (appearance.subtitleBackground) {
        SubtitleBackground.NONE -> AndroidColor.TRANSPARENT
        SubtitleBackground.SOFT -> AndroidColor.argb(145, 0, 0, 0)
        SubtitleBackground.STRONG -> AndroidColor.argb(225, 0, 0, 0)
    }
    val edgeType = when (appearance.subtitleEdge) {
        SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
        SubtitleEdge.SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
    }
    view.subtitleView?.apply {
        setApplyEmbeddedStyles(false)
        setApplyEmbeddedFontSizes(false)
        setFractionalTextSize(
            SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * appearance.subtitleSizeScale.coerceIn(0.65f, 1.75f),
        )
        setBottomPaddingFraction(appearance.subtitleBottomPaddingFraction.coerceIn(0.02f, 0.32f))
        setStyle(
            CaptionStyleCompat(
                foreground,
                background,
                AndroidColor.TRANSPARENT,
                edgeType,
                AndroidColor.BLACK,
                null,
            ),
        )
    }
}

@Composable
private fun CinematicControls(
    title: String,
    episodeLabel: String?,
    runtime: PlayerRuntimeState,
    qualityLabel: String,
    onBack: () -> Unit,
    onTogglePlay: () -> Unit,
    onRewind: () -> Unit,
    onForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.68f),
                    0.28f to Color.Transparent,
                    0.68f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.82f),
                ),
            ),
    ) {
        Row(
            modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerCircleButton(onClick = onBack, size = 44) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
            }
            Column(modifier = Modifier.padding(start = 14.dp).weight(1f)) {
                Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                episodeLabel?.let {
                    Text(it, color = Color.White.copy(alpha = 0.72f), fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        Row(
            modifier = Modifier.align(Alignment.Center),
            horizontalArrangement = Arrangement.spacedBy(34.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PlayerCircleButton(onClick = onRewind, size = 58) {
                Icon(Icons.Filled.Replay10, contentDescription = "10 saniye geri", tint = Color.White, modifier = Modifier.size(34.dp))
            }
            PlayerCircleButton(onClick = onTogglePlay, size = 76, strong = true) {
                Icon(
                    imageVector = if (runtime.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (runtime.isPlaying) "Duraklat" else "Oynat",
                    tint = Color.Black,
                    modifier = Modifier.size(46.dp),
                )
            }
            PlayerCircleButton(onClick = onForward, size = 58) {
                Icon(Icons.Filled.Forward10, contentDescription = "10 saniye ileri", tint = Color.White, modifier = Modifier.size(34.dp))
            }
        }

        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            val max = runtime.durationMs.coerceAtLeast(1L).toFloat()
            Slider(
                value = runtime.positionMs.coerceAtMost(runtime.durationMs.coerceAtLeast(runtime.positionMs)).toFloat(),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..max,
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = ProgressRed,
                    inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                ),
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(runtime.positionMs), color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp)
                Text("  /  ${formatTime(runtime.durationMs)}", color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                Row(
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.38f)).padding(start = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(qualityLabel, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Kalite, ses, altyazı ve ekran", tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerCircleButton(onClick: () -> Unit, size: Int, strong: Boolean = false, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(size.dp).clip(CircleShape).background(if (strong) Color.White else Color.Black.copy(alpha = 0.48f)),
    ) { content() }
}

private fun episodeLabel(title: com.apexlions.film2.player.catalog.Title, season: Int?, episode: Int?): String? {
    if (season == null || episode == null) return null
    val episodeTitle = title.seasons
        ?.firstOrNull { it.seasonNumber == season }
        ?.episodes
        ?.firstOrNull { it.episodeNumber == episode }
        ?.title
    return buildString {
        append("S$season:B$episode")
        if (!episodeTitle.isNullOrBlank()) append("  •  $episodeTitle")
    }
}

private fun formatTime(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
