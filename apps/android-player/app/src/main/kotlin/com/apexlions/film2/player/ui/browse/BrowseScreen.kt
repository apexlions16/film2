package com.apexlions.film2.player.ui.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexlions.film2.player.Film2PlayerApplication
import com.apexlions.film2.player.catalog.AssetStatus
import com.apexlions.film2.player.catalog.CatalogResult
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.ui.common.BrowseLoadingSkeleton
import com.apexlions.film2.player.ui.common.CatalogEmptyState
import com.apexlions.film2.player.ui.common.CatalogErrorState

@Composable
fun BrowseScreen(
    onTitleSelected: (Title) -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Film2PlayerApplication
    val viewModel: BrowseViewModel = viewModel(
        factory = BrowseViewModel.Factory(application.catalogRepository),
    )
    val state by viewModel.state.collectAsState()

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
                onTitleSelected = onTitleSelected,
            )

            is CatalogResult.Error -> Column(modifier = Modifier.fillMaxSize()) {
                CatalogErrorState(
                    message = result.message,
                    onRetry = viewModel::refresh,
                )
                // Demo row still verifiable even if the real catalog failed to load.
                GenreRow(
                    genre = "Test",
                    titles = listOf(viewModel.demoTitle),
                    onSelect = onTitleSelected,
                )
            }
        }
    }
}

@Composable
private fun BrowseContent(
    titles: List<Title>,
    demoTitle: Title,
    onTitleSelected: (Title) -> Unit,
    modifier: Modifier = Modifier,
) {
    val playableTitles = titles.filter { it.status == AssetStatus.READY }
    val hero = playableTitles.firstOrNull() ?: titles.firstOrNull()
    val genres = playableTitles
        .flatMap { title -> title.genres.map { it to title } }
        .groupBy({ it.first }, { it.second })

    AnimatedVisibility(visible = true, enter = fadeIn(), exit = fadeOut()) {
        LazyColumn(modifier = modifier.fillMaxSize()) {
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

            item(key = "demo-row") {
                GenreRow(
                    genre = "Demo Stream (test)",
                    titles = listOf(demoTitle),
                    onSelect = onTitleSelected,
                )
            }

            items(genres.entries.toList(), key = { it.key }) { (genre, genreTitles) ->
                GenreRow(genre = genre, titles = genreTitles, onSelect = onTitleSelected)
            }

            item(key = "bottom-spacer") {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}
