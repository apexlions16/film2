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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.apexlions.film2.player.Film2PlayerApplication
import com.apexlions.film2.player.catalog.AssetStatus
import com.apexlions.film2.player.catalog.Episode
import com.apexlions.film2.player.catalog.Season
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.catalog.TitleType
import com.apexlions.film2.player.ui.browse.StatusBadge
import com.apexlions.film2.player.ui.common.CatalogErrorState

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

    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when (val s = state) {
            is TitleDetailState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            is TitleDetailState.Error -> CatalogErrorState(message = s.message, onRetry = onBack)
            is TitleDetailState.Loaded -> TitleDetailContent(
                title = s.title,
                onPlayMovie = { onPlay(titleId, null, null) },
                onPlayEpisode = { season, episode -> onPlay(titleId, season, episode) },
            )
        }

        IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Geri", tint = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun TitleDetailContent(
    title: Title,
    onPlayMovie: () -> Unit,
    onPlayEpisode: (Int, Int) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
                if (title.backdropUrl != null) {
                    AsyncImage(
                        model = title.backdropUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }

        item {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusBadge(status = title.status)
                Text(title.title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onBackground)

                val meta = buildList {
                    title.releaseYear?.let { add(it.toString()) }
                    title.runtimeMinutes?.let { add("$it dk") }
                    if (title.genres.isNotEmpty()) add(title.genres.joinToString(" / "))
                }
                if (meta.isNotEmpty()) {
                    Text(meta.joinToString("  •  "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text(title.overview, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onBackground)

                if (title.type == TitleType.MOVIE) {
                    val playable = title.status == AssetStatus.READY && title.asset != null
                    Button(
                        onClick = onPlayMovie,
                        enabled = playable,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(if (playable) Icons.Filled.PlayArrow else Icons.Filled.Lock, contentDescription = null)
                        Text(if (playable) " Oynat" else " Henuz hazir degil", fontWeight = FontWeight.SemiBold)
                    }
                }

                if (title.cast.isNotEmpty()) {
                    Text("Oyuncular", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        title.cast.take(8).joinToString(", ") { it.name },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (title.crew.isNotEmpty()) {
                    Text("Ekip", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        title.crew.take(6).joinToString(", ") { "${it.name} (${it.job})" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (title.type == TitleType.SERIES) {
            val seasons = title.seasons.orEmpty()
            items(seasons, key = { it.seasonNumber }) { season ->
                SeasonBlock(season = season, onPlayEpisode = onPlayEpisode)
            }
        }

        item { androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(32.dp)) }
    }
}

@Composable
private fun SeasonBlock(
    season: Season,
    onPlayEpisode: (Int, Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(season.seasonNumber == 1) }

    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(season.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                season.episodes.forEach { episode ->
                    EpisodeRow(
                        episode = episode,
                        onClick = { onPlayEpisode(season.seasonNumber, episode.episodeNumber) },
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(
    episode: Episode,
    onClick: () -> Unit,
) {
    val playable = episode.status == AssetStatus.READY && episode.asset != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(enabled = playable, onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (playable) Icons.Filled.PlayArrow else Icons.Filled.Lock,
                contentDescription = null,
                tint = if (playable) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "${episode.episodeNumber}. ${episode.title}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                episode.overview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        StatusBadge(status = episode.status)
    }
}
