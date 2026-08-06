@file:OptIn(ExperimentalMaterial3Api::class)

package com.apexlions.film2.studio.ui.newtitle

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.apexlions.film2.studio.catalog.TitleType
import com.apexlions.film2.studio.work.UploadJobFile
import com.apexlions.film2.studio.work.UploadJobSpec
import com.apexlions.film2.studio.work.UploadWorker
import kotlinx.serialization.json.Json

private enum class AttachMode { COMBINED, SEPARATE }

/**
 * Lets the user pick local media files (Storage Access Framework) for one movie or one
 * episode and queues them for background upload + package-media dispatch. Uploading is a
 * WorkManager job (see UploadWorker) so this screen only ever *enqueues* work — it never
 * blocks on the actual upload, and the job keeps running even if the user navigates away
 * or the app is killed and later restarted.
 */
@Composable
fun AttachFilesScreen(
    titleId: String,
    titleType: TitleType,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(AttachMode.COMBINED) }
    var seasonNumber by remember { mutableStateOf("") }
    var episodeNumber by remember { mutableStateOf("") }

    var combinedUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    val audioFiles = remember { mutableStateListOf<Pair<Uri, String>>() }
    val subtitleFiles = remember { mutableStateListOf<Pair<Uri, String>>() }
    var queuedMessage by remember { mutableStateOf<String?>(null) }

    fun persist(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't support persistable permissions; the upload will still
            // work as long as it runs before the app process is killed.
        }
    }

    val pickCombined = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persist(it); combinedUri = it }
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { persist(it); videoUri = it }
    }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { persist(it); audioFiles.add(it to "") }
    }
    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { persist(it); subtitleFiles.add(it to "") }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Medya Ekle") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                titleId,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (titleType == TitleType.SERIES) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = seasonNumber,
                        onValueChange = { seasonNumber = it.filter(Char::isDigit) },
                        label = { Text("Sezon") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = episodeNumber,
                        onValueChange = { episodeNumber = it.filter(Char::isDigit) },
                        label = { Text("Bolum") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Tek (birlesik) dosya", mode == AttachMode.COMBINED) { mode = AttachMode.COMBINED }
                ModeButton("Ayri video/ses/altyazi", mode == AttachMode.SEPARATE) { mode = AttachMode.SEPARATE }
            }

            if (mode == AttachMode.COMBINED) {
                FilePickRow(
                    label = "Birlesik dosya (video + coklu ses/altyazi track'leri)",
                    fileName = combinedUri?.let { displayName(context, it) },
                    onPick = { pickCombined.launch(arrayOf("*/*")) },
                )
            } else {
                FilePickRow(
                    label = "Video dosyasi",
                    fileName = videoUri?.let { displayName(context, it) },
                    onPick = { pickVideo.launch(arrayOf("video/*")) },
                )
                LanguageFileList(
                    label = "Ses dosyalari (dil kodu girin: tr, en, ...)",
                    files = audioFiles,
                    onAdd = { pickAudio.launch(arrayOf("audio/*")) },
                    onLanguageChange = { index, lang -> audioFiles[index] = audioFiles[index].first to lang },
                    context = context,
                )
                LanguageFileList(
                    label = "Altyazi dosyalari (.srt / .vtt, dil kodu girin)",
                    files = subtitleFiles,
                    onAdd = { pickSubtitle.launch(arrayOf("*/*")) },
                    onLanguageChange = { index, lang -> subtitleFiles[index] = subtitleFiles[index].first to lang },
                    context = context,
                )
            }

            if (queuedMessage != null) {
                Text(queuedMessage!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }

            val readyToQueue = if (mode == AttachMode.COMBINED) {
                combinedUri != null
            } else {
                videoUri != null && audioFiles.isNotEmpty()
            } && (titleType == TitleType.MOVIE || (seasonNumber.isNotBlank() && episodeNumber.isNotBlank()))

            Button(
                onClick = {
                    val files = buildList {
                        if (mode == AttachMode.COMBINED) {
                            combinedUri?.let { add(UploadJobFile("combined", it.toString(), fileNameFor(context, it, "combined.mkv"))) }
                        } else {
                            videoUri?.let { add(UploadJobFile("video", it.toString(), fileNameFor(context, it, "video.mkv"))) }
                            audioFiles.forEach { (uri, lang) ->
                                add(UploadJobFile("audio", uri.toString(), fileNameFor(context, uri, "audio_$lang"), language = lang.ifBlank { "und" }))
                            }
                            subtitleFiles.forEach { (uri, lang) ->
                                add(UploadJobFile("subtitle", uri.toString(), fileNameFor(context, uri, "subs_$lang"), language = lang.ifBlank { "und" }))
                            }
                        }
                    }
                    val spec = UploadJobSpec(
                        titleId = titleId,
                        kind = if (titleType == TitleType.SERIES) "episode" else "movie",
                        seasonNumber = seasonNumber.toIntOrNull(),
                        episodeNumber = episodeNumber.toIntOrNull(),
                        mode = if (mode == AttachMode.COMBINED) "combined" else "separate",
                        files = files,
                    )
                    val specJson = Json.encodeToString(UploadJobSpec.serializer(), spec)
                    val request = OneTimeWorkRequestBuilder<UploadWorker>()
                        .setInputData(workDataOf(UploadWorker.KEY_JOB_SPEC to specJson))
                        .build()
                    WorkManager.getInstance(context).enqueue(request)
                    queuedMessage = "Yukleme kuyruga alindi — bu ekrandan cikabilirsiniz, arka planda devam eder."
                },
                enabled = readyToQueue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text("Yuklemeyi Baslat (arka planda)")
            }

            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text("Bitir")
            }
        }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = if (selected) {
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

@Composable
private fun FilePickRow(label: String, fileName: String?, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth()) {
            Text(fileName ?: "Dosya sec")
        }
    }
}

@Composable
private fun LanguageFileList(
    label: String,
    files: androidx.compose.runtime.snapshots.SnapshotStateList<Pair<Uri, String>>,
    onAdd: () -> Unit,
    onLanguageChange: (Int, String) -> Unit,
    context: android.content.Context,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        files.forEachIndexed { index, (uri, lang) ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    displayName(context, uri),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(top = 14.dp),
                )
                OutlinedTextField(
                    value = lang,
                    onValueChange = { onLanguageChange(index, it) },
                    label = { Text("dil") },
                    singleLine = true,
                    modifier = Modifier.weight(0.5f),
                )
            }
        }
        OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
            Text("+ Dosya ekle")
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index) ?: uri.lastPathSegment.orEmpty()
    }
    return uri.lastPathSegment ?: "dosya"
}

private fun fileNameFor(context: android.content.Context, uri: Uri, fallback: String): String {
    val name = displayName(context, uri)
    return name.ifBlank { fallback }
}
