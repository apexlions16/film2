package com.apexlions.film2.player.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexlions.film2.player.Film2PlayerApplication
import com.apexlions.film2.player.catalog.AssetStatus
import com.apexlions.film2.player.catalog.CatalogResult
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.ui.common.BottomTab
import com.apexlions.film2.player.ui.common.BrowseLoadingSkeleton
import com.apexlions.film2.player.ui.common.CatalogEmptyState
import com.apexlions.film2.player.ui.common.CatalogErrorState
import com.apexlions.film2.player.ui.common.Film2BottomBar
import com.apexlions.film2.player.userdata.PlaybackRecord
import com.apexlions.film2.player.userdata.UserLibraryState

@Composable
fun BrowseScreen(
    onTitleSelected: (Title) -> Unit,
    onContinuePlay: (titleId: String, season: Int?, episode: Int?) -> Unit,
    onSearchClick: () -> Unit,
    onLibraryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Film2PlayerApplication
    val viewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModel.Factory(application.catalogRepository),
    )
    val state by viewModel.state.collectAsState()
    val library by application.userLibraryRepository.state.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val result = state) {
            is CatalogResult.Loading -> BrowseLoadingSkeleton(modifier = Modifier.fillMaxSize())

            is CatalogResult.Success -> BrowseContent(
                titles = result.titles,
                demoTitle = viewModel.demoTitle,
                library = library,
                onTitleSelected = onTitleSelected,
                onContinuePlay = onContinuePlay,
            )

            is CatalogResult.Error -> Column(modifier = Modifier.fillMaxSize()) {
                CatalogErrorState(
                    message = result.message,
                    onRetry = viewModel::refresh,
                )
                GenreRow(
                    genre = "Test",
                    titles = listOf(viewModel.demoTitle),
                    onSelect = onTitleSelected,
                )
            }
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "FILM2",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
        }

        IconButton(
            onClick = onSearchClick,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.58f)),
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Ara",
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }

        Film2BottomBar(
            selected = BottomTab.HOME,
            onHome = {},
            onSearch = onSearchClick,
            onLibrary = onLibraryClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
        )
    }
}

@Composable
private fun BrowseContent(
    titles: List<Title>,
    demoTitle: Title,
    library: UserLibraryState,
    onTitleSelected: (Title) -> Unit,
    onContinuePlay: (titleId: String, season: Int?, episode: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playableTitles = titles.filter { it.status == AssetStatus.READY }
    val hero = playableTitles.firstOrNull() ?: titles.firstOrNull()
    val genres = playableTitles
        .flatMap { title -> title.genres.map { it to title } }
        .groupBy({ it.first }, { it.second })

    val progressByTitle = titles.associate { title ->
        title.id to (library.latestForTitle(title.id)?.progressFraction ?: 0f)
    }

    val continueItems = library.continueWatching()
        .mapNotNull { record -> buildContinueItem(titles, record) }
        .take(20)

    val myListTitles = library.myListTitleIds.mapNotNull { id -> titles.firstOrNull { it.id == id } }

    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = modifier.fillMaxSize()) {
            if (hero != null) {
                item(key = "hero") {
                    HeroBanner(
                        title = hero,
                        onPlay = { onTitleSelected(hero) },
                        onMoreInfo = { onTitleSelected(hero) },
                    )
                }
            }

            if (titles.isEmpty()) {
                item(key = "empty") { CatalogEmptyState() }
            }

            if (continueItems.isNotEmpty()) {
                item(key = "continue-watching") {
                    ContinueWatchingRow(
                        items = continueItems,
                        onPlay = { item ->
                            onContinuePlay(
                                item.record.titleId,
                                item.record.seasonNumber,
                                item.record.episodeNumber,
                            )
                        },
                    )
                }
            }

            if (myListTitles.isNotEmpty()) {
                item(key = "my-list") {
                    GenreRow(
                        genre = "Listem",
                        titles = myListTitles,
                        onSelect = onTitleSelected,
                        progressByTitle = progressByTitle,
                    )
                }
            }

            items(genres.entries.toList(), key = { it.key }) { (genre, genreTitles) ->
                GenreRow(
                    genre = genre,
                    titles = genreTitles,
                    onSelect = onTitleSelected,
                    progressByTitle = progressByTitle,
                )
            }

            item(key = "demo-row") {
                GenreRow(
                    genre = "Demo Stream (test)",
                    titles = listOf(demoTitle),
                    onSelect = onTitleSelected,
                )
            }

            item(key = "bottom-spacer") {
                Spacer(modifier = Modifier.height(110.dp))
            }
        }
    }
}

private fun buildContinueItem(titles: List<Title>, record: PlaybackRecord): ContinueWatchingUiItem? {
    val title = titles.firstOrNull { it.id == record.titleId } ?: return null
    if (record.seasonNumber == null || record.episodeNumber == null) {
        return ContinueWatchingUiItem(
            title = title,
            record = record,
            imageUrl = title.backdropUrl,
            subtitle = "${(record.progressFraction * 100).toInt()}% izlendi",
        )
    }

    val episode = title.seasons
        ?.firstOrNull { it.seasonNumber == record.seasonNumber }
        ?.episodes
        ?.firstOrNull { it.episodeNumber == record.episodeNumber }
    val subtitle = buildString {
        append("S${record.seasonNumber}:B${record.episodeNumber}")
        episode?.title?.takeIf { it.isNotBlank() }?.let { append(" • $it") }
    }
    return ContinueWatchingUiItem(
        title = title,
        record = record,
        imageUrl = episode?.stillUrl ?: title.backdropUrl,
        subtitle = subtitle,
    )
}
