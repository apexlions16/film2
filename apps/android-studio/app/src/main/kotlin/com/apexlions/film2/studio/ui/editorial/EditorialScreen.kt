@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apexlions.film2.studio.ui.editorial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.apexlions.film2.studio.Film2StudioApplication
import com.apexlions.film2.studio.catalog.HomeConfig
import com.apexlions.film2.studio.catalog.HomeShelf
import com.apexlions.film2.studio.catalog.Title
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

@Composable
fun EditorialScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as Film2StudioApplication
    val scope = rememberCoroutineScope()
    var titles by remember { mutableStateOf<List<Title>>(emptyList()) }
    var config by remember { mutableStateOf(HomeConfig.DEFAULT) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var artworkTitleId by remember { mutableStateOf<String?>(null) }

    fun reload() {
        scope.launch {
            loading = true
            message = null
            runCatching {
                titles = app.githubClient.listTitles().sortedBy { it.title.lowercase() }
                config = app.githubClient.getHomeConfig()
                if (artworkTitleId == null) artworkTitleId = titles.firstOrNull()?.id
            }.onFailure { message = it.message ?: "Yüklenemedi" }
            loading = false
        }
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Ana Sayfa & Editoryal") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(onClick = ::reload) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Yenile")
                    }
                },
            )
        },
    ) { padding ->
        if (loading) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text(
                    "Hero Döngüsü",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Ana sayfanın büyük kapağında dönmesini istediğin içerikleri seç. Birden fazla seçersen refresh/oturumlarda değişir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(titles, key = { "hero-${it.id}" }) { title ->
                        val selected = title.id in config.heroTitleIds
                        AssistChip(
                            onClick = {
                                config = config.copy(
                                    heroTitleIds = if (selected) config.heroTitleIds - title.id
                                    else (config.heroTitleIds + title.id).distinct(),
                                )
                            },
                            label = { Text(if (selected) "✓ ${title.title}" else title.title) },
                        )
                    }
                }
            }

            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ana Sayfa Rafları", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            config = config.copy(
                                shelves = config.shelves + HomeShelf(
                                    id = "custom-${UUID.randomUUID().toString().take(8)}",
                                    title = "Yeni Liste",
                                ),
                            )
                        },
                    ) { Icon(Icons.Filled.Add, contentDescription = "Raf ekle") }
                }
            }

            items(config.shelves, key = { it.id }) { shelf ->
                ShelfEditor(
                    shelf = shelf,
                    titles = titles,
                    onChange = { changed ->
                        config = config.copy(shelves = config.shelves.map { if (it.id == shelf.id) changed else it })
                    },
                    onDelete = { config = config.copy(shelves = config.shelves.filterNot { it.id == shelf.id }) },
                )
            }

            item {
                Button(
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        scope.launch {
                            saving = true
                            message = null
                            runCatching { app.githubClient.putHomeConfig(config) }
                                .onSuccess { message = "Ana sayfa rafları kaydedildi. Player birkaç saniye içinde yenilenecek." }
                                .onFailure { message = it.message ?: "Kaydedilemedi" }
                            saving = false
                        }
                    },
                ) {
                    if (saving) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text("Rafları Kaydet")
                }
            }

            item {
                Text("Kapak / Arka Plan Havuzu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "Bir içerik için birden fazla URL ekle. Player her giriş/refresh sırasında havuzdan farklı bir görsel seçer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(titles, key = { "art-${it.id}" }) { title ->
                        AssistChip(
                            onClick = { artworkTitleId = title.id },
                            label = { Text(if (artworkTitleId == title.id) "✓ ${title.title}" else title.title) },
                        )
                    }
                }
            }

            artworkTitleId?.let { selectedId ->
                titles.firstOrNull { it.id == selectedId }?.let { title ->
                    item(key = "artwork-editor-${title.id}") {
                        ArtworkEditor(
                            title = title,
                            saving = saving,
                            onSave = { posters, backdrops ->
                                scope.launch {
                                    saving = true
                                    message = null
                                    val updated = title.copy(
                                        posterUrls = posters,
                                        backdropUrls = backdrops,
                                        updatedAt = Instant.now().toString(),
                                    )
                                    runCatching { app.githubClient.putTitle(updated) }
                                        .onSuccess {
                                            titles = titles.map { if (it.id == updated.id) updated else it }
                                            message = "${title.title} görsel havuzu kaydedildi."
                                        }
                                        .onFailure { message = it.message ?: "Görseller kaydedilemedi" }
                                    saving = false
                                }
                            },
                        )
                    }
                }
            }

            message?.let { text ->
                item {
                    Text(
                        text,
                        color = if (text.contains("kaydedildi", ignoreCase = true)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfEditor(
    shelf: HomeShelf,
    titles: List<Title>,
    onChange: (HomeShelf) -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = shelf.title,
                    onValueChange = { onChange(shelf.copy(title = it.take(60))) },
                    label = { Text("Raf adı") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Rafı sil") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Aktif")
                Switch(checked = shelf.enabled, onCheckedChange = { onChange(shelf.copy(enabled = it)) })
                Text("Karıştır")
                Switch(checked = shelf.shuffle, onCheckedChange = { onChange(shelf.copy(shuffle = it)) })
            }
            Text("İçerikler", style = MaterialTheme.typography.labelLarge)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                items(titles, key = { "${shelf.id}-${it.id}" }) { title ->
                    val selected = title.id in shelf.titleIds
                    AssistChip(
                        onClick = {
                            onChange(
                                shelf.copy(
                                    titleIds = if (selected) shelf.titleIds - title.id else (shelf.titleIds + title.id).distinct(),
                                ),
                            )
                        },
                        label = {
                            Text(
                                if (selected) "✓ ${title.title}" else title.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtworkEditor(
    title: Title,
    saving: Boolean,
    onSave: (posterUrls: List<String>, backdropUrls: List<String>) -> Unit,
) {
    var postersText by remember(title.id, title.posterUrls) {
        mutableStateOf(title.posterUrls.joinToString("\n"))
    }
    var backdropsText by remember(title.id, title.backdropUrls) {
        mutableStateOf(title.backdropUrls.joinToString("\n"))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = postersText,
                onValueChange = { postersText = it },
                label = { Text("Alternatif poster URL'leri • satır başına 1") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = backdropsText,
                onValueChange = { backdropsText = it },
                label = { Text("Alternatif arka plan URL'leri • satır başına 1") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                enabled = !saving,
                onClick = {
                    onSave(parseUrls(postersText), parseUrls(backdropsText))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Görsel Havuzunu Kaydet") }
        }
    }
}

private fun parseUrls(value: String): List<String> = value
    .lines()
    .map { it.trim() }
    .filter { it.startsWith("https://") || it.startsWith("http://") }
    .distinct()
    .take(40)
