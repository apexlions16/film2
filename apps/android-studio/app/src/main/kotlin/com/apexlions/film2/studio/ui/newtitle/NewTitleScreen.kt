@file:OptIn(ExperimentalMaterial3Api::class)

package com.apexlions.film2.studio.ui.newtitle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.apexlions.film2.studio.Film2StudioApplication
import com.apexlions.film2.studio.catalog.Title
import com.apexlions.film2.studio.catalog.TitleType

@Composable
fun NewTitleScreen(
    onBack: () -> Unit,
    onSaved: (titleId: String, titleType: TitleType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Film2StudioApplication
    val viewModel: NewTitleViewModel = viewModel(
        factory = NewTitleViewModel.Factory(
            tmdbClient = application.tmdbClient,
            githubClient = application.githubClient,
            settingsRepository = application.settingsRepository,
        ),
    )
    val step by viewModel.step.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Yeni Icerik Ekle") }) },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = step) {
                is NewTitleStep.Input -> ImdbInputSection(
                    onFetch = viewModel::fetchFromImdb,
                    onManual = viewModel::startManualEntry,
                    onBack = onBack,
                )
                is NewTitleStep.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                is NewTitleStep.Preview -> PreviewForm(
                    draft = s.draft,
                    onChange = viewModel::updateDraft,
                    onSave = viewModel::save,
                )
                is NewTitleStep.Saving -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
                is NewTitleStep.Saved -> {
                    LaunchedEffect(s.title.id) { onSaved(s.title.id, s.title.type) }
                }
                is NewTitleStep.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Hata", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                        Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = onBack) { Text("Geri don") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImdbInputSection(
    onFetch: (String) -> Unit,
    onManual: () -> Unit,
    onBack: () -> Unit,
) {
    var link by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "IMDb linkini veya tt... ID'sini yapistirin. TMDB'den basliktan sezon/bolume kadar tum " +
                "veri otomatik cekilecek.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("IMDb linki / ID") },
            placeholder = { Text("https://www.imdb.com/title/tt1234567/") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onFetch(link) },
            enabled = link.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("TMDB'den Getir")
        }
        OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) {
            Text("Manuel Giris (TMDB'de yok)")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Iptal")
        }
    }
}

@Composable
private fun PreviewForm(
    draft: Title,
    onChange: (Title) -> Unit,
    onSave: (Title) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (draft.manualEntry == true) {
            Text(
                "TMDB'de bulunamadi — tum alanlari elle doldurun.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = draft.id,
            onValueChange = { onChange(draft.copy(id = it)) },
            label = { Text("Katalog id (dosya adi, orn. kayip-sehir)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.title,
            onValueChange = { onChange(draft.copy(title = it)) },
            label = { Text("Baslik") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.imdbId,
            onValueChange = { onChange(draft.copy(imdbId = it)) },
            label = { Text("IMDb ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        TypeToggle(
            selected = draft.type,
            onSelect = { onChange(draft.copy(type = it)) },
        )

        OutlinedTextField(
            value = draft.overview,
            onValueChange = { onChange(draft.copy(overview = it)) },
            label = { Text("Ozet") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.genres.joinToString(", "),
            onValueChange = { text ->
                onChange(draft.copy(genres = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }))
            },
            label = { Text("Turler (virgulle ayirin)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.releaseYear?.toString().orEmpty(),
            onValueChange = { text -> onChange(draft.copy(releaseYear = text.toIntOrNull())) },
            label = { Text("Yil") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (draft.type == TitleType.MOVIE) {
            OutlinedTextField(
                value = draft.runtimeMinutes?.toString().orEmpty(),
                onValueChange = { text -> onChange(draft.copy(runtimeMinutes = text.toIntOrNull())) },
                label = { Text("Sure (dakika)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (draft.cast.isNotEmpty()) {
            Text("Oyuncular (TMDB)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                draft.cast.take(10).joinToString(", ") { "${it.name} (${it.character})" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.crew.isNotEmpty()) {
            Text("Ekip (TMDB)", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text(
                draft.crew.take(10).joinToString(", ") { "${it.name} (${it.job})" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.type == TitleType.SERIES) {
            val seasonCount = draft.seasons?.size ?: 0
            val episodeCount = draft.seasons?.sumOf { it.episodes.size } ?: 0
            Text(
                "Sezonlar (TMDB): $seasonCount sezon, $episodeCount bolum",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            draft.seasons?.forEach { season ->
                Text(
                    "${season.name}: ${season.episodes.size} bolum",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(
            onClick = { onSave(draft) },
            enabled = draft.id.isNotBlank() && draft.title.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Text("Kataloga Kaydet")
        }
    }
}

@Composable
private fun TypeToggle(
    selected: TitleType,
    onSelect: (TitleType) -> Unit,
) {
    Column {
        Text("Tur", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(TitleType.MOVIE to "Film", TitleType.SERIES to "Dizi").forEach { (type, label) ->
                val isSelected = type == selected
                OutlinedButton(
                    onClick = { onSelect(type) },
                    colors = if (isSelected) {
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        ButtonDefaults.outlinedButtonColors()
                    },
                ) {
                    Text(label)
                }
            }
        }
    }
}
