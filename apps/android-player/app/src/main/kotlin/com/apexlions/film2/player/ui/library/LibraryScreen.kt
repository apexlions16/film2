package com.apexlions.film2.player.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.apexlions.film2.player.Film2PlayerApplication
import com.apexlions.film2.player.catalog.CatalogResult
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.offline.OfflineDownloadStatus
import com.apexlions.film2.player.ui.browse.GenreRow
import com.apexlions.film2.player.ui.common.BottomTab
import com.apexlions.film2.player.ui.common.CatalogErrorState
import com.apexlions.film2.player.ui.common.Film2BottomBar

@Composable
fun LibraryScreen(
    onTitleSelected: (Title) -> Unit,
    onHome: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Film2PlayerApplication
    val library by application.userLibraryRepository.state.collectAsState()
    val offline by application.offlineDownloadRepository.state.collectAsState()
    val catalogResult by produceState<CatalogResult>(CatalogResult.Loading) {
        value = application.catalogRepository.fetchTitles()
    }
    var createDialog by remember { mutableStateOf(false) }
    var newListName by remember { mutableStateOf("") }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (val catalog = catalogResult) {
            CatalogResult.Loading -> Unit
            is CatalogResult.Error -> CatalogErrorState(message = catalog.message, onRetry = onHome)
            is CatalogResult.Success -> {
                val titles = catalog.titles
                val byId = titles.associateBy { it.id }
                val progress = titles.associate { title ->
                    title.id to (library.latestForTitle(title.id)?.progressFraction ?: 0f)
                }
                val myList = library.myListTitleIds.mapNotNull(byId::get)
                val offlineTitleIds = offline.records.values
                    .filter {
                        it.status == OfflineDownloadStatus.COMPLETE ||
                            it.status == OfflineDownloadStatus.DOWNLOADING ||
                            it.status == OfflineDownloadStatus.QUEUED
                    }
                    .map { it.titleId }
                    .distinct()
                val offlineTitles = offlineTitleIds.mapNotNull(byId::get)

                androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Benim Film2'm",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    "İndirdiklerin, kaydettiklerin ve kendi koleksiyonların",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { createDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Yeni liste", tint = MaterialTheme.colorScheme.onBackground)
                            }
                        }
                    }

                    if (offlineTitles.isNotEmpty()) {
                        item(key = "offline") {
                            GenreRow(
                                genre = "İndirilenler",
                                titles = offlineTitles,
                                onSelect = onTitleSelected,
                                progressByTitle = progress,
                            )
                            val active = offline.records.values.filter {
                                it.status == OfflineDownloadStatus.DOWNLOADING || it.status == OfflineDownloadStatus.QUEUED
                            }
                            if (active.isNotEmpty()) {
                                Text(
                                    "${active.size} indirme devam ediyor • ayrıntılı ilerleme için içeriği aç",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 16.dp, bottom = 18.dp),
                                )
                            }
                        }
                    }

                    item(key = "default-my-list") {
                        if (myList.isEmpty()) {
                            EmptyListBlock(
                                title = "Listem",
                                text = "Bir içeriğin detay sayfasındaki + Listem düğmesiyle buraya ekleyebilirsin.",
                            )
                        } else {
                            GenreRow(
                                genre = "Listem",
                                titles = myList,
                                onSelect = onTitleSelected,
                                progressByTitle = progress,
                            )
                        }
                    }

                    library.customLists.forEach { collection ->
                        item(key = "custom-${collection.id}") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = collection.name,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onBackground,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.weight(1f),
                                    )
                                    IconButton(onClick = { application.userLibraryRepository.deleteList(collection.id) }) {
                                        Icon(
                                            Icons.Filled.DeleteOutline,
                                            contentDescription = "Listeyi sil",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                val listTitles = collection.titleIds.mapNotNull(byId::get)
                                if (listTitles.isEmpty()) {
                                    Text(
                                        "Bu liste henüz boş.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(start = 16.dp, bottom = 24.dp),
                                    )
                                } else {
                                    GenreRow(
                                        genre = "",
                                        titles = listTitles,
                                        onSelect = onTitleSelected,
                                        progressByTitle = progress,
                                    )
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(112.dp)) }
                }
            }
        }

        Film2BottomBar(
            selected = BottomTab.LIBRARY,
            onHome = onHome,
            onSearch = onSearch,
            onLibrary = {},
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 10.dp),
        )
    }

    if (createDialog) {
        AlertDialog(
            onDismissRequest = { createDialog = false },
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
                    onClick = {
                        application.userLibraryRepository.createList(newListName)
                        newListName = ""
                        createDialog = false
                    },
                    enabled = newListName.isNotBlank(),
                ) { Text("Oluştur") }
            },
            dismissButton = { OutlinedButton(onClick = { createDialog = false }) { Text("Vazgeç") } },
        )
    }
}

@Composable
private fun EmptyListBlock(title: String, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
