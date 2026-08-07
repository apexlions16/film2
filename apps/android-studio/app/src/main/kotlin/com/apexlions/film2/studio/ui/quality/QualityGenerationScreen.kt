@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.apexlions.film2.studio.ui.quality

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.apexlions.film2.studio.Film2StudioApplication
import com.apexlions.film2.studio.catalog.TitleType
import com.apexlions.film2.studio.dispatch.MediaKind
import com.apexlions.film2.studio.dispatch.QualityGenerationRequest
import kotlinx.coroutines.launch

@Composable
fun QualityGenerationScreen(
    titleId: String,
    titleType: TitleType,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val application = LocalContext.current.applicationContext as Film2StudioApplication
    val scope = rememberCoroutineScope()

    var seasonNumber by remember { mutableStateOf("") }
    var episodeNumber by remember { mutableStateOf("") }
    var generate720 by remember { mutableStateOf(true) }
    var generate480 by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var isError by remember { mutableStateOf(false) }

    val seriesCoordinatesValid = titleType != TitleType.SERIES ||
        (seasonNumber.toIntOrNull() != null && episodeNumber.toIntOrNull() != null)
    val hasTarget = generate720 || generate480

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Kalite Uret") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(titleId, style = MaterialTheme.typography.titleMedium)

            Text(
                "Kaynak MP4 degistirilmez. GitHub Actions kaynak videoyu Hugging Face'ten gecici runner diskine alir, secilen kaliteleri tek MP4 olarak uretir ve tekrar Hugging Face'e yukler. Job bitince runner diski otomatik silinir.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (titleType == TitleType.SERIES) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = seasonNumber,
                        onValueChange = { seasonNumber = it.filter(Char::isDigit) },
                        label = { Text("Sezon") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = episodeNumber,
                        onValueChange = { episodeNumber = it.filter(Char::isDigit) },
                        label = { Text("Bolum") },
                        singleLine = true,
                        enabled = !running,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            QualityCheckRow(
                label = "720p olustur",
                checked = generate720,
                enabled = !running,
                onCheckedChange = { generate720 = it },
            )
            QualityCheckRow(
                label = "480p olustur",
                checked = generate480,
                enabled = !running,
                onCheckedChange = { generate480 = it },
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp,
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("Ses ve altyazi", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "MP4 icindeki Ingilizce/Turkce ses track'leri korunur. Ses yeniden encode edilmez. VTT altyazilar mevcut sidecar dosyalar olarak kullanilmaya devam eder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            statusMessage?.let { message ->
                Text(
                    message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
            }

            Button(
                onClick = {
                    scope.launch {
                        running = true
                        isError = false
                        statusMessage = "GitHub Actions isi baslatiliyor..."
                        try {
                            val token = application.settingsRepository.currentTokens().githubPat
                                ?.takeIf { it.isNotBlank() }
                                ?: error("GitHub PAT ayarlanmamis")
                            val targets = buildList {
                                if (generate720) add(720)
                                if (generate480) add(480)
                            }
                            application.qualityGenerationDispatcher.dispatch(
                                QualityGenerationRequest(
                                    titleId = titleId,
                                    kind = if (titleType == TitleType.SERIES) MediaKind.EPISODE else MediaKind.MOVIE,
                                    seasonNumber = seasonNumber.toIntOrNull(),
                                    episodeNumber = episodeNumber.toIntOrNull(),
                                    targets = targets,
                                ),
                                githubToken = token,
                            )
                            statusMessage = "Kalite isi baslatildi. Islem GitHub Actions'ta arka planda devam edecek; bitince Player kalite seceneklerini katalogdan gorecek."
                        } catch (t: Throwable) {
                            isError = true
                            statusMessage = t.message ?: "Kalite isi baslatilamadi"
                        } finally {
                            running = false
                        }
                    }
                },
                enabled = !running && hasTarget && seriesCoordinatesValid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (running) "Baslatiliyor..." else "Kaliteleri Olustur")
            }

            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth(), enabled = !running) {
                Text("Geri")
            }
        }
    }
}

@Composable
private fun QualityCheckRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
