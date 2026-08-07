@file:OptIn(androidx.media3.common.util.UnstableApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apexlions.film2.player.ui.detail

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.apexlions.film2.player.Film2PlayerApplication
import com.apexlions.film2.player.catalog.AssetStatus
import com.apexlions.film2.player.catalog.Episode
import com.apexlions.film2.player.catalog.Season
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.catalog.TitleType
import com.apexlions.film2.player.offline.OfflineDownloadRepository
import com.apexlions.film2.player.ui.browse.StatusBadge
import com.apexlions.film2.player.ui.browse.rotatingBackdrop
import com.apexlions.film2.player.ui.common.CatalogErrorState
import com.apexlions.film2.player.userdata.PlaybackRecord
import com.apexlions.film2.player.userdata.UserLibraryRepository
import com.apexlions.film2.player.userdata.UserLibraryState

private val DetailProgressRed = Color(0xFFE50914)

@Composable
fun TitleDetailScreen(
    titleId: String,
    onBack: () -> Unit,
    onPlay: (titleId: String, season: Int?, episode: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Film2PlayerApplication
    val viewModel: TitleDetailViewModel = viewModel(
        factory = TitleDetailViewModel.Factory(titleId, application.catalogRepository),
    )
    val state by viewModel.state.collectAsState()
    val library by application.userLibraryRepository.state.collectAsState()

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            is TitleDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            is TitleDetailState.Error -> CatalogErrorState(message = s.message, onRetry = onBack)
            is TitleDetailState.Loaded -> TitleDetailContent(
                title = s.title,
                library = library,
                userLibrary = application.userLibraryRepository,
                offlineRepository = application.offlineDownloadRepository,
                onPlayMovie = { onPlay(titleId, null, null) },
                onPlayEpisode = { season, episode -> onPlay(titleId, season, episode) },
            )
        }

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 8.dp, top = 6.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f)),
        ) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Geri", tint = Color.White)
        }
    }
}

@Composable
private fun TitleDetailContent(
    title: Title,
    library: UserLibraryState,
    userLibrary: UserLibraryRepository,
    offlineRepository: OfflineDownloadRepository,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Int, Int) -> Unit,
) {
    var listSheetVisible by remember { mutableStateOf(false) }
    var createListDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }
    val movieProgress = library.record(title.id)
    val inMyList = title.id in library.myListTitleIds

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "trailer-hero") { TrailerHero(title = title) }

        item(key = "main-info") {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusBadge(status = title.status)

                if (!title.logoUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = title.logoUrl,
                        contentDescription = title.title,
                        modifier = Modifier.fillMaxWidth(0.55f).height(74.dp),
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                    )
                } else {
                    Text(
                        title.title,
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                    )
                }

                val meta = buildList {
                    title.releaseYear?.let { add(it.toString()) }
                    title.runtimeMinutes?.let { add("$it dk") }
                    if (title.genres.isNotEmpty()) add(title.genres.take(2).joinToString(" / "))
                    if (title.asset?.videoVariants?.any { it.height >= 1080 } == true) add("FHD")
                    else if (title.asset?.videoVariants?.any { it.height >= 720 } == true) add("HD")
                }
                if (meta.isNotEmpty()) {
                    Text(meta.joinToString("  •  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                if (title.type == TitleType.MOVIE) {
                    val playable = title.status == AssetStatus.READY && title.asset != null
                    Button(
                        onClick = onPlayMovie,
                        enabled = playable,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                    ) {
                        Icon(if (playable) Icons.Filled.PlayArrow else Icons.Filled.Lock, contentDescription = null)
                        Text(
                            when {
                                !playable -> " Henüz hazır değil"
                                movieProgress?.hasMeaningfulProgress == true -> " Devam Et • ${formatResumeTime(movieProgress.positionMs)}"
                                else -> " Oynat"
                            },
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    if (playable) {
                        MovieDownloadControl(title = title, repository = offlineRepository)
                    }
                    movieProgress?.takeIf { it.progressFraction > 0.005f }?.let { progress ->
                        DetailProgressLine(progress.progressFraction)
                        Text("%${(progress.progressFraction * 100).toInt()} izlendi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { userLibrary.toggleMyList(title.id) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(if (inMyList) Icons.Filled.Check else Icons.Filled.Add, contentDescription = null)
                        Text(if (inMyList) " Listemde" else " Listem")
                    }
                    OutlinedButton(onClick = { listSheetVisible = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PlaylistAdd, contentDescription = null)
                        Text(" Listeler")
                    }
                }

                if (title.type == TitleType.MOVIE && movieProgress?.hasMeaningfulProgress == true) {
                    OutlinedButton(
                        onClick = { userLibrary.clearProgress(title.id); onPlayMovie() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.RestartAlt, contentDescription = null)
                        Text(" Baştan Oynat")
                    }
                }

                Text(title.overview, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)

                if (title.cast.isNotEmpty()) {
                    Text(
                        "Başroldekiler: ${title.cast.take(5).joinToString(", ") { it.name }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (title.crew.isNotEmpty()) {
                    Text(
                        "Yapım: ${title.crew.take(4).joinToString(", ") { "${it.name} (${it.job})" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (title.type == TitleType.SERIES) {
            items(title.seasons.orEmpty(), key = { it.seasonNumber }) { season ->
                SeasonBlock(
                    title = title,
                    season = season,
                    library = library,
                    offlineRepository = offlineRepository,
                    onPlayEpisode = onPlayEpisode,
                )
            }
        }
        item { Spacer(modifier = Modifier.height(42.dp)) }
    }

    if (listSheetVisible) {
        ModalBottomSheet(
            onDismissRequest = { listSheetVisible = false },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("Listeye Ekle", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 10.dp))
                library.customLists.forEach { collection ->
                    val selected = title.id in collection.titleIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { userLibrary.toggleTitleInList(collection.id, title.id) }
                            .padding(vertical = 13.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(collection.name, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
                OutlinedButton(
                    onClick = { createListDialog = true },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(" Yeni Liste")
                }
            }
        }
    }

    if (createListDialog) {
        AlertDialog(
            onDismissRequest = { createListDialog = false },
            title = { Text("Yeni liste") },
            text = {
                OutlinedTextField(
                    value = newListName,
                    onValueChange = { newListName = it },
                    label = { Text("Liste adı") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    enabled = newListName.isNotBlank(),
                    onClick = {
                        val created = userLibrary.createList(newListName)
                        created?.let { userLibrary.toggleTitleInList(it.id, title.id) }
                        newListName = ""
                        createListDialog = false
                    },
                ) { Text("Oluştur ve Ekle") }
            },
            dismissButton = { OutlinedButton(onClick = { createListDialog = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun TrailerHero(title: Title) {
    val context = LocalContext.current
    var muted by remember(title.id) { mutableStateOf(true) }
    val trailerUrl = title.trailerUrl?.takeIf { it.isNotBlank() }
    val backdrop = remember(title.id, title.updatedAt) { title.rotatingBackdrop(System.nanoTime()) }
    val player = remember(trailerUrl) {
        trailerUrl?.let { url ->
            ExoPlayer.Builder(context).build().apply {
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
        }
    }

    DisposableEffect(player) { onDispose { player?.release() } }

    Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
        if (backdrop != null) {
            AsyncImage(model = backdrop, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }

        if (player != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    }
                },
                update = { it.player = player },
            )
        }

        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, MaterialTheme.colorScheme.background)),
            ),
        )

        if (player != null) {
            IconButton(
                onClick = { muted = !muted; player.volume = if (muted) 0f else 1f },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 28.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.58f)),
            ) {
                Icon(
                    imageVector = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = if (muted) "Trailer sesini aç" else "Trailer sesini kapat",
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun SeasonBlock(
    title: Title,
    season: Season,
    library: UserLibraryState,
    offlineRepository: OfflineDownloadRepository,
    onPlayEpisode: (Int, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(season.seasonNumber == 1) }

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(season.name, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SeasonDownloadControl(
            title = title,
            season = season,
            repository = offlineRepository,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                season.episodes.forEach { episode ->
                    EpisodeRow(
                        titleId = title.id,
                        seasonNumber = season.seasonNumber,
                        episode = episode,
                        progress = library.record(title.id, season.seasonNumber, episode.episodeNumber),
                        offlineRepository = offlineRepository,
                        onClick = { onPlayEpisode(season.seasonNumber, episode.episodeNumber) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    titleId: String,
    seasonNumber: Int,
    episode: Episode,
    progress: PlaybackRecord?,
    offlineRepository: OfflineDownloadRepository,
    onClick: () -> Unit,
) {
    val playable = episode.status == AssetStatus.READY && episode.asset != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = playable, onClick = onClick)
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!episode.stillUrl.isNullOrBlank()) {
                AsyncImage(
                    model = episode.stillUrl,
                    contentDescription = null,
                    modifier = Modifier.size(width = 108.dp, height = 62.dp).clip(RoundedCornerShape(5.dp)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(modifier = Modifier.size(width = 46.dp, height = 46.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (playable) Icons.Filled.PlayArrow else Icons.Filled.Lock,
                        contentDescription = null,
                        tint = if (playable) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${episode.episodeNumber}. ${episode.title}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    episode.overview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress?.hasMeaningfulProgress == true) {
                    Text(
                        "${formatResumeTime(progress.positionMs)} konumundan devam et",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
            }
        }
        if (playable) {
            EpisodeDownloadControl(
                titleId = titleId,
                seasonNumber = seasonNumber,
                episode = episode,
                repository = offlineRepository,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        progress?.takeIf { it.progressFraction > 0.005f }?.let { DetailProgressLine(it.progressFraction) }
    }
}

@Composable
private fun DetailProgressLine(progress: Float) {
    Box(modifier = Modifier.fillMaxWidth().height(4.dp).background(Color(0xFF333336))) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(DetailProgressRed),
        )
    }
}

private fun formatResumeTime(milliseconds: Long): String {
    val totalMinutes = milliseconds.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}s ${minutes}dk" else "${minutes}dk"
}
