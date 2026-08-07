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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.apexlions.film2.player.catalog.HomeConfig
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.ui.common.BottomTab
import com.apexlions.film2.player.ui.common.BrowseLoadingSkeleton
import com.apexlions.film2.player.ui.common.CatalogEmptyState
import com.apexlions.film2.player.ui.common.CatalogErrorState
import com.apexlions.film2.player.ui.common.Film2BottomBar
import com.apexlions.film2.player.userdata.PlaybackRecord
import com.apexlions.film2.player.userdata.UserLibraryState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.random.Random

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
    val homeConfig by viewModel.homeConfig.collectAsState()
    val artworkNonce by viewModel.artworkNonce.collectAsState()
    val library by application.userLibraryRepository.state.collectAsState()

    LaunchedEffect(viewModel) {
        while (isActive) {
            delay(CATALOG_POLL_MS)
            viewModel.refreshIfChanged()
        }
    }

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
                homeConfig = homeConfig,
                artworkNonce = artworkNonce,
                library = library,
                onTitleSelected = onTitleSelected,
                onContinuePlay = onContinuePlay,
            )

            is CatalogResult.Error -> Column(modifier = Modifier.fillMaxSize()) {
                CatalogErrorState(message = result.message, onRetry = viewModel::refresh)
                GenreRow(genre = "Test", titles = listOf(viewModel.demoTitle), onSelect = onTitleSelected)
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

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 8.dp),
        ) {
            IconButton(
                onClick = viewModel::refresh,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.background.copy(alpha = 0.58f)),
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = "Katalogu yenile", tint = MaterialTheme.colorScheme.onBackground)
            }
            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.background.copy(alpha = 0.58f)),
            ) {
                Icon(Icons.Filled.Search, contentDescription = "Ara", tint = MaterialTheme.colorScheme.onBackground)
            }
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
    homeConfig: HomeConfig,
    artworkNonce: Long,
    library: UserLibraryState,
    onTitleSelected: (Title) -> Unit,
    onContinuePlay: (titleId: String, season: Int?, episode: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playableTitles = titles.filter { it.status == AssetStatus.READY }
    val processingTitles = titles.filter { it.status == AssetStatus.PROCESSING || it.status == AssetStatus.PENDING }
    val byId = titles.associateBy { it.id }

    val configuredHeroes = homeConfig.heroTitleIds.mapNotNull(byId::get).filter { it.status == AssetStatus.READY }
    val hero = when {
        configuredHeroes.isNotEmpty() -> configuredHeroes[artworkNonce.absoluteIndex(configuredHeroes.size)]
        else -> playableTitles.firstOrNull() ?: titles.firstOrNull()
    }

    val curatedShelves = homeConfig.shelves
        .asSequence()
        .filter { it.enabled }
        .mapNotNull { shelf ->
            var shelfTitles = shelf.titleIds.mapNotNull(byId::get)
            if (shelf.shuffle && shelfTitles.size > 1) {
                val seed = (artworkNonce xor shelf.id.hashCode().toLong()).toInt()
                shelfTitles = shelfTitles.shuffled(Random(seed))
            }
            shelfTitles = shelfTitles.take(shelf.maxItems.coerceIn(1, 100))
            shelf.takeIf { shelfTitles.isNotEmpty() }?.let { it to shelfTitles }
        }
        .toList()

    val genres = playableTitles
        .flatMap { title -> title.genres.map { it to title } }
        .groupBy({ it.first }, { it.second })

    val progressByTitle = titles.associate { title ->
        title.id to (library.latestForTitle(title.id)?.progressFraction ?: 0f)
    }

    val continueItems = library.continueWatching()
        .mapNotNull { record -> buildContinueItem(titles, record) }
        .take(20)
    val myListTitles = library.myListTitleIds.mapNotNull(byId::get)

    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        androidx.compose.foundation.lazy.LazyColumn(modifier = modifier.fillMaxSize()) {
            if (hero != null) {
                item(key = "hero") {
                    HeroBanner(
                        title = hero,
                        onPlay = { onTitleSelected(hero) },
                        onMoreInfo = { onTitleSelected(hero) },
                        artworkNonce = artworkNonce,
                    )
                }
            }

            if (titles.isEmpty()) item(key = "empty") { CatalogEmptyState() }

            if (continueItems.isNotEmpty()) {
                item(key = "continue-watching") {
                    ContinueWatchingRow(
                        items = continueItems,
                        onPlay = { item -> onContinuePlay(item.record.titleId, item.record.seasonNumber, item.record.episodeNumber) },
                    )
                }
            }

            curatedShelves.forEach { (shelf, shelfTitles) ->
                item(key = "curated-${shelf.id}") {
                    GenreRow(
                        genre = shelf.title,
                        titles = shelfTitles,
                        onSelect = onTitleSelected,
                        progressByTitle = progressByTitle,
                        artworkNonce = artworkNonce,
                    )
                }
            }

            if (processingTitles.isNotEmpty()) {
                item(key = "processing") {
                    GenreRow(
                        genre = "Hazırlanıyor",
                        titles = processingTitles,
                        onSelect = onTitleSelected,
                        progressByTitle = progressByTitle,
                        artworkNonce = artworkNonce,
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
                        artworkNonce = artworkNonce,
                    )
                }
            }

            items(genres.entries.toList(), key = { it.key }) { (genre, genreTitles) ->
                GenreRow(
                    genre = genre,
                    titles = genreTitles,
                    onSelect = onTitleSelected,
                    progressByTitle = progressByTitle,
                    artworkNonce = artworkNonce,
                )
            }

            item(key = "demo-row") {
                GenreRow(genre = "Demo Stream (test)", titles = listOf(demoTitle), onSelect = onTitleSelected)
            }

            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(110.dp)) }
        }
    }
}

private fun Long.absoluteIndex(size: Int): Int {
    if (size <= 1) return 0
    val positive = if (this == Long.MIN_VALUE) 0L else kotlin.math.abs(this)
    return (positive % size).toInt()
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

private const val CATALOG_POLL_MS = 5_000L
