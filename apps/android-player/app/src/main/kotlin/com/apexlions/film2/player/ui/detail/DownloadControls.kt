package com.apexlions.film2.player.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.apexlions.film2.player.catalog.Episode
import com.apexlions.film2.player.catalog.Season
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.offline.OfflineDownloadRepository
import com.apexlions.film2.player.offline.OfflineDownloadStatus

@Composable
fun MovieDownloadControl(
    title: Title,
    repository: OfflineDownloadRepository,
    modifier: Modifier = Modifier,
) {
    val state by repository.state.collectAsState()
    val record = state.record(title.id)
    val asset = title.asset
    if (asset == null) return

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                when (record?.status) {
                    OfflineDownloadStatus.COMPLETE,
                    OfflineDownloadStatus.DOWNLOADING,
                    OfflineDownloadStatus.QUEUED -> repository.delete(title.id)
                    else -> repository.enqueue(title.id, null, null, title.title, asset)
                }
            },
        ) {
            Icon(
                imageVector = when (record?.status) {
                    OfflineDownloadStatus.COMPLETE -> Icons.Filled.CheckCircle
                    OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.QUEUED -> Icons.Filled.Downloading
                    else -> Icons.Filled.Download
                },
                contentDescription = null,
            )
            Text(
                when (record?.status) {
                    OfflineDownloadStatus.COMPLETE -> " İndirildi • Kaldır"
                    OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.QUEUED -> " İndiriliyor • İptal"
                    OfflineDownloadStatus.FAILED -> " Tekrar İndir"
                    null -> " İndir"
                },
            )
        }
        DownloadProgress(record?.progressFraction, record?.status)
    }
}

@Composable
fun SeasonDownloadControl(
    title: Title,
    season: Season,
    repository: OfflineDownloadRepository,
    modifier: Modifier = Modifier,
) {
    val state by repository.state.collectAsState()
    val downloadable = season.episodes.filter { it.asset != null }
    if (downloadable.isEmpty()) return
    val records = downloadable.mapNotNull { state.record(title.id, season.seasonNumber, it.episodeNumber) }
    val allComplete = records.size == downloadable.size && records.all { it.status == OfflineDownloadStatus.COMPLETE }
    val anyActive = records.any { it.status == OfflineDownloadStatus.DOWNLOADING || it.status == OfflineDownloadStatus.QUEUED }
    val progress = if (records.isEmpty()) 0f else records.map { it.progressFraction }.average().toFloat()

    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = {
                if (allComplete || anyActive) repository.deleteSeason(title.id, season.seasonNumber)
                else repository.enqueueSeason(title, season.seasonNumber)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = when {
                    allComplete -> Icons.Filled.CheckCircle
                    anyActive -> Icons.Filled.Downloading
                    else -> Icons.Filled.Download
                },
                contentDescription = null,
            )
            Text(
                when {
                    allComplete -> " Sezon ${season.seasonNumber} indirildi • Kaldır"
                    anyActive -> " Sezon indiriliyor • İptal"
                    else -> " Sezon ${season.seasonNumber}'u İndir"
                },
            )
        }
        if (anyActive) {
            LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth().padding(top = 5.dp))
            Text(
                "%${(progress * 100).toInt()} • ${records.count { it.status == OfflineDownloadStatus.COMPLETE }}/${downloadable.size} bölüm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun EpisodeDownloadControl(
    titleId: String,
    seasonNumber: Int,
    episode: Episode,
    repository: OfflineDownloadRepository,
    modifier: Modifier = Modifier,
) {
    val state by repository.state.collectAsState()
    val record = state.record(titleId, seasonNumber, episode.episodeNumber)
    val asset = episode.asset ?: return
    val active = record?.status == OfflineDownloadStatus.DOWNLOADING || record?.status == OfflineDownloadStatus.QUEUED

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (record?.status) {
                    OfflineDownloadStatus.COMPLETE -> "Cihazda hazır"
                    OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.QUEUED -> "İndiriliyor • %${(record.progressFraction * 100).toInt()}"
                    OfflineDownloadStatus.FAILED -> "İndirme başarısız"
                    null -> "Çevrimdışı izle"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (record != null && record.status != OfflineDownloadStatus.FAILED) {
                        repository.delete(titleId, seasonNumber, episode.episodeNumber)
                    } else {
                        repository.enqueue(
                            titleId = titleId,
                            seasonNumber = seasonNumber,
                            episodeNumber = episode.episodeNumber,
                            displayName = "S$seasonNumber:B${episode.episodeNumber} ${episode.title}",
                            asset = asset,
                        )
                    }
                },
            ) {
                Icon(
                    imageVector = when (record?.status) {
                        OfflineDownloadStatus.COMPLETE -> Icons.Filled.Delete
                        OfflineDownloadStatus.DOWNLOADING, OfflineDownloadStatus.QUEUED -> Icons.Filled.Downloading
                        else -> Icons.Filled.Download
                    },
                    contentDescription = "Bölümü indir / kaldır",
                )
            }
        }
        if (active) {
            LinearProgressIndicator(progress = record?.progressFraction ?: 0f, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DownloadProgress(progress: Float?, status: OfflineDownloadStatus?) {
    if (status != OfflineDownloadStatus.DOWNLOADING && status != OfflineDownloadStatus.QUEUED) return
    val value = progress ?: 0f
    LinearProgressIndicator(progress = value, modifier = Modifier.fillMaxWidth())
    Text(
        "%${(value * 100).toInt()} indirildi",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
