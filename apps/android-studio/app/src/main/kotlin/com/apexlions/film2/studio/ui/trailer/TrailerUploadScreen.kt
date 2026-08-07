package com.apexlions.film2.studio.ui.trailer

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.apexlions.film2.studio.work.TrailerUploadWorker

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailerUploadScreen(
    titleId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workManager = remember(context) { WorkManager.getInstance(context) }
    val tag = remember(titleId) { TrailerUploadWorker.tagForTitle(titleId) }
    val workInfos by workManager.getWorkInfosByTagFlow(tag).collectAsState(initial = emptyList())
    val visibleWork = workInfos.lastOrNull { !it.state.isFinished } ?: workInfos.lastOrNull()
    val running = visibleWork?.state?.isFinished == false
    var selectedUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            selectedUri = it
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Trailer / Onizleme") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Geri")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(titleId, style = MaterialTheme.typography.titleMedium)
            Text(
                "Detay sayfasinda arka planda sessiz otomatik oynatilacak kisa bir MP4 onerilir. " +
                    "Bu islem ana filme ve ses track'lerine dokunmaz.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                onClick = { picker.launch(arrayOf("video/*")) },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(selectedUri?.let { displayName(context, it) } ?: "Trailer dosyasi sec")
            }

            visibleWork?.let { info -> TrailerWorkStatus(info) }

            Button(
                onClick = {
                    val uri = selectedUri ?: return@Button
                    val fileName = displayName(context, uri)
                    val ext = fileName.substringAfterLast('.', "mp4")
                    val request = OneTimeWorkRequestBuilder<TrailerUploadWorker>()
                        .setInputData(
                            workDataOf(
                                TrailerUploadWorker.KEY_TITLE_ID to titleId,
                                TrailerUploadWorker.KEY_URI to uri.toString(),
                                TrailerUploadWorker.KEY_EXTENSION to ext,
                            ),
                        )
                        .setConstraints(
                            Constraints.Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build(),
                        )
                        .addTag(tag)
                        .build()
                    workManager.enqueue(request)
                },
                enabled = selectedUri != null && !running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Yukleniyor..." else "Trailer'i Yukle")
            }

            if (running) {
                OutlinedButton(
                    onClick = { visibleWork?.let { workManager.cancelWorkById(it.id) } },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Iptal Et")
                }
            }
        }
    }
}

@Composable
private fun TrailerWorkStatus(info: WorkInfo) {
    val data = if (info.state.isFinished) info.outputData else info.progress
    val percent = data.getInt(TrailerUploadWorker.KEY_PROGRESS, 0).coerceIn(0, 100)
    val message = data.getString(TrailerUploadWorker.KEY_MESSAGE)
        ?: data.getString(TrailerUploadWorker.KEY_ERROR)
        ?: info.state.name
    val bytes = data.getLong(TrailerUploadWorker.KEY_BYTES, 0L)
    val total = data.getLong(TrailerUploadWorker.KEY_TOTAL_BYTES, 0L)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!info.state.isFinished) {
            LinearProgressIndicator(progress = percent / 100f, modifier = Modifier.fillMaxWidth())
        } else if (info.state == WorkInfo.State.SUCCEEDED) {
            Text("Hazir", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
        }
        Text("%$percent • $message", style = MaterialTheme.typography.bodyMedium)
        if (total > 0L) {
            Text(
                "${formatBytes(bytes)} / ${formatBytes(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (info.state == WorkInfo.State.RUNNING && percent == 0) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) return cursor.getString(index) ?: "trailer.mp4"
    }
    return uri.lastPathSegment ?: "trailer.mp4"
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return "%.1f %s".format(value, units[index])
}
