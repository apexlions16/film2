@file:OptIn(ExperimentalMaterial3Api::class)

package com.apexlions.film2.studio.ui.newtitle

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.apexlions.film2.studio.catalog.TitleType
import com.apexlions.film2.studio.work.UploadJobFile
import com.apexlions.film2.studio.work.UploadJobSpec
import com.apexlions.film2.studio.work.UploadWorker
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

private enum class AttachMode { COMBINED, SEPARATE }

@Composable
fun AttachFilesScreen(
    titleId: String,
    titleType: TitleType,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val uploadTag = remember(titleId) { UploadWorker.tagForTitle(titleId) }
    val workInfos by workManager.getWorkInfosByTagFlow(uploadTag).collectAsState(initial = emptyList())
    val visibleWork = workInfos.lastOrNull { !it.state.isFinished } ?: workInfos.lastOrNull()
    val uploadRunning = visibleWork?.state?.isFinished == false

    var mode by remember { mutableStateOf(AttachMode.COMBINED) }
    var seasonNumber by remember { mutableStateOf("") }
    var episodeNumber by remember { mutableStateOf("") }

    var combinedUri by remember { mutableStateOf<Uri?>(null) }
    var videoUri by remember { mutableStateOf<Uri?>(null) }
    val audioFiles = remember { mutableStateListOf<Pair<Uri, String>>() }
    val subtitleFiles = remember { mutableStateListOf<Pair<Uri, String>>() }

    fun persist(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some providers don't support persistable grants. WorkManager will surface a
            // clear read-permission error if the provider revokes access before upload.
        }
    }

    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Upload still runs when the user declines; Android may hide the notification. */ }
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
                        enabled = !uploadRunning,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = episodeNumber,
                        onValueChange = { episodeNumber = it.filter(Char::isDigit) },
                        label = { Text("Bolum") },
                        singleLine = true,
                        enabled = !uploadRunning,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeButton("Tek (birlesik) dosya", mode == AttachMode.COMBINED, !uploadRunning) {
                    mode = AttachMode.COMBINED
                }
                ModeButton("Ayri video/ses/altyazi", mode == AttachMode.SEPARATE, !uploadRunning) {
                    mode = AttachMode.SEPARATE
                }
            }

            if (mode == AttachMode.COMBINED) {
                Text(
                    "Dosya uzantisi onemli degil. MP4, MPEG-TS veya .mkv diye adlandirilmis MPEG-TS secilebilir; gercek track'ler cihazda analiz edilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilePickRow(
                    label = "Birlesik dosya (video + coklu ses/altyazi track'leri)",
                    fileName = combinedUri?.let { displayName(context, it) },
                    enabled = !uploadRunning,
                    onPick = { pickCombined.launch(arrayOf("*/*")) },
                )
            } else {
                Text(
                    "Uzantiya bakilmiyor. video.mkv / tr.mkv / en.mkv gercekte MPEG-TS ise MediaExtractor track'leri icerikten tanir ve hizli MP4 yolu kullanilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FilePickRow(
                    label = "Video dosyasi",
                    fileName = videoUri?.let { displayName(context, it) },
                    enabled = !uploadRunning,
                    onPick = { pickVideo.launch(arrayOf("*/*")) },
                )
                LanguageFileList(
                    label = "Ses dosyalari (dil kodu girin: tr, en, ...)",
                    files = audioFiles,
                    enabled = !uploadRunning,
                    onAdd = { pickAudio.launch(arrayOf("*/*")) },
                    onLanguageChange = { index, lang -> audioFiles[index] = audioFiles[index].first to lang },
                    context = context,
                )
                LanguageFileList(
                    label = "Altyazi dosyalari (.srt / .vtt, dil kodu girin)",
                    files = subtitleFiles,
                    enabled = !uploadRunning,
                    onAdd = { pickSubtitle.launch(arrayOf("*/*")) },
                    onLanguageChange = { index, lang -> subtitleFiles[index] = subtitleFiles[index].first to lang },
                    context = context,
                )
            }

            visibleWork?.let { workInfo ->
                UploadStatusCard(
                    workInfo = workInfo,
                    onCancel = { workManager.cancelWorkById(workInfo.id) },
                )
            }

            val readyToQueue = if (mode == AttachMode.COMBINED) {
                combinedUri != null
            } else {
                videoUri != null && audioFiles.isNotEmpty()
            } && (titleType == TitleType.MOVIE || (seasonNumber.isNotBlank() && episodeNumber.isNotBlank()))

            Button(
                onClick = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }

                    val files = buildList {
                        if (mode == AttachMode.COMBINED) {
                            combinedUri?.let {
                                add(UploadJobFile("combined", it.toString(), fileNameFor(context, it, "combined.mkv")))
                            }
                        } else {
                            videoUri?.let {
                                add(UploadJobFile("video", it.toString(), fileNameFor(context, it, "video.mkv")))
                            }
                            audioFiles.forEach { (uri, lang) ->
                                add(
                                    UploadJobFile(
                                        "audio",
                                        uri.toString(),
                                        fileNameFor(context, uri, "audio_$lang"),
                                        language = lang.ifBlank { "und" },
                                    ),
                                )
                            }
                            subtitleFiles.forEach { (uri, lang) ->
                                add(
                                    UploadJobFile(
                                        "subtitle",
                                        uri.toString(),
                                        fileNameFor(context, uri, "subs_$lang"),
                                        language = lang.ifBlank { "und" },
                                    ),
                                )
                            }
                        }
                    }
                    val spec = UploadJobSpec(
                        titleId = titleId,
                        kind = if (titleType == TitleType.SERIES) "episode" else "movie",
                        seasonNumber = seasonNumber.toIntOrNull(),
                        episodeNumber = episodeNumber.toIntOrNull(),
                        mode = if (mode == AttachMode.SEPARATE) "separate" else "combined",
                        files = files,
                    )
                    val specJson = Json.encodeToString(UploadJobSpec.serializer(), spec)
                    val request = OneTimeWorkRequestBuilder<UploadWorker>()
                        .setInputData(workDataOf(UploadWorker.KEY_JOB_SPEC to specJson))
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                        .addTag(UploadWorker.TAG_ALL_UPLOADS)
                        .addTag(uploadTag)
                        .build()
                    workManager.enqueue(request)
                },
                enabled = readyToQueue && !uploadRunning,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Text(if (uploadRunning) "Yukleme devam ediyor" else "Yuklemeyi Baslat")
            }

            OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
                Text(if (uploadRunning) "Ekrandan Cik (arka planda devam eder)" else "Bitir")
            }
        }
    }
}

@Composable
private fun UploadStatusCard(workInfo: WorkInfo, onCancel: () -> Unit) {
    val data = if (workInfo.state.isFinished) workInfo.outputData else workInfo.progress
    val percent = data.getInt(UploadWorker.KEY_PROGRESS_PERCENT, 0).coerceIn(0, 100)
    val stage = data.getString(UploadWorker.KEY_STAGE).orEmpty()
    val message = data.getString(UploadWorker.KEY_MESSAGE)
        ?: data.getString(UploadWorker.KEY_ERROR)
        ?: stateLabel(workInfo.state)
    val fileIndex = data.getInt(UploadWorker.KEY_FILE_INDEX, 0)
    val fileCount = data.getInt(UploadWorker.KEY_FILE_COUNT, 0)
    val fileName = data.getString(UploadWorker.KEY_FILE_NAME).orEmpty()
    val bytesProcessed = data.getLong(UploadWorker.KEY_BYTES_PROCESSED, 0L)
    val totalBytes = data.getLong(UploadWorker.KEY_TOTAL_BYTES, 0L)
    val bytesPerSecond = data.getLong(UploadWorker.KEY_BYTES_PER_SECOND, 0L)
    val etaSeconds = data.getLong(UploadWorker.KEY_ETA_SECONDS, -1L)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (workInfo.state == WorkInfo.State.FAILED) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stageLabel(stage), style = MaterialTheme.typography.titleSmall)
                Text("%$percent", style = MaterialTheme.typography.titleMedium)
            }
            LinearProgressIndicator(
                progress = percent / 100f,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(message, style = MaterialTheme.typography.bodyMedium)
            if (fileName.isNotBlank()) {
                Text(
                    buildString {
                        if (fileCount > 0) append("Dosya $fileIndex/$fileCount - ")
                        append(fileName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (totalBytes > 0L) {
                val detail = buildString {
                    append("${formatBytes(bytesProcessed)} / ${formatBytes(totalBytes)}")
                    if (bytesPerSecond > 0L) append(" - ${formatBytes(bytesPerSecond)}/sn")
                    if (etaSeconds >= 0L && stage == UploadWorker.STAGE_UPLOADING) {
                        append(" - yaklasik ${formatDuration(etaSeconds)} kaldi")
                    }
                }
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!workInfo.state.isFinished) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Yuklemeyi Iptal Et")
                }
            }
        }
    }
}

private fun stageLabel(stage: String): String = when (stage) {
    UploadWorker.STAGE_QUEUED -> "Kuyrukta"
    UploadWorker.STAGE_PREPARING -> "Dosya hazirlaniyor"
    UploadWorker.STAGE_CHECKING -> "Hugging Face kontrolu"
    UploadWorker.STAGE_UPLOADING -> "Hugging Face'e yukleniyor"
    UploadWorker.STAGE_FINALIZING -> "Yukleme sonlandiriliyor"
    UploadWorker.STAGE_FILE_COMPLETE -> "Dosya tamamlandi"
    UploadWorker.STAGE_CATALOG -> "Katalog guncelleniyor"
    UploadWorker.STAGE_DISPATCHING -> "Paketleme baslatiliyor"
    UploadWorker.STAGE_RETRYING -> "Yeniden denenecek"
    UploadWorker.STAGE_COMPLETE -> "Tamamlandi"
    UploadWorker.STAGE_FAILED -> "Hata"
    else -> "Yukleme durumu"
}

private fun stateLabel(state: WorkInfo.State): String = when (state) {
    WorkInfo.State.ENQUEUED -> "Ag baglantisi bekleniyor"
    WorkInfo.State.RUNNING -> "Yukleme calisiyor"
    WorkInfo.State.SUCCEEDED -> "Yukleme tamamlandi"
    WorkInfo.State.FAILED -> "Yukleme basarisiz"
    WorkInfo.State.BLOCKED -> "Yukleme engellenmis durumda"
    WorkInfo.State.CANCELLED -> "Yukleme iptal edildi"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}

private fun formatDuration(seconds: Long): String = when {
    seconds < 60L -> "$seconds sn"
    seconds < 3600L -> "${seconds / 60} dk ${seconds % 60} sn"
    else -> "${seconds / 3600} sa ${(seconds % 3600) / 60} dk"
}

@Composable
private fun ModeButton(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
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
private fun FilePickRow(label: String, fileName: String?, enabled: Boolean, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text(fileName ?: "Dosya sec")
        }
    }
}

@Composable
private fun LanguageFileList(
    label: String,
    files: androidx.compose.runtime.snapshots.SnapshotStateList<Pair<Uri, String>>,
    enabled: Boolean,
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
                    enabled = enabled,
                    modifier = Modifier.weight(0.5f),
                )
            }
        }
        OutlinedButton(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
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
