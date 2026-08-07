package com.apexlions.film2.player.offline

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import com.apexlions.film2.player.catalog.ExternalMediaTrack
import com.apexlions.film2.player.catalog.PlayableAsset
import com.apexlions.film2.player.catalog.Title
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Locale

@Serializable
enum class OfflineDownloadStatus {
    @SerialName("queued") QUEUED,
    @SerialName("downloading") DOWNLOADING,
    @SerialName("complete") COMPLETE,
    @SerialName("failed") FAILED,
}

@Serializable
data class OfflineSubtitleFile(
    val language: String,
    val label: String? = null,
    val mimeType: String? = null,
    val remoteUrl: String,
    val relativePath: String,
    val downloadId: Long,
)

@Serializable
data class OfflineMediaRecord(
    val key: String,
    val titleId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val displayName: String,
    val remoteVideoUrl: String,
    val videoRelativePath: String,
    val videoDownloadId: Long,
    val subtitles: List<OfflineSubtitleFile> = emptyList(),
    val audioLanguages: List<String> = emptyList(),
    val durationSeconds: Double? = null,
    val qualityHeight: Int? = null,
    val status: OfflineDownloadStatus = OfflineDownloadStatus.QUEUED,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) {
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
        } else if (status == OfflineDownloadStatus.COMPLETE) 1f else 0f
}

@Serializable
data class OfflineLibraryState(
    val records: Map<String, OfflineMediaRecord> = emptyMap(),
) {
    fun record(titleId: String, seasonNumber: Int? = null, episodeNumber: Int? = null): OfflineMediaRecord? =
        records[offlineKey(titleId, seasonNumber, episodeNumber)]

    fun forTitle(titleId: String): List<OfflineMediaRecord> = records.values
        .filter { it.titleId == titleId }
        .sortedWith(compareBy({ it.seasonNumber ?: -1 }, { it.episodeNumber ?: -1 }))
}

fun offlineKey(titleId: String, seasonNumber: Int?, episodeNumber: Int?): String =
    "$titleId|${seasonNumber ?: -1}|${episodeNumber ?: -1}"

class OfflineDownloadRepository(private val context: Context) {
    private val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<OfflineLibraryState> = _state.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refreshProgress()
                delay(POLL_MS)
            }
        }
    }

    fun record(titleId: String, seasonNumber: Int? = null, episodeNumber: Int? = null): OfflineMediaRecord? =
        _state.value.record(titleId, seasonNumber, episodeNumber)

    fun enqueue(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        displayName: String,
        asset: PlayableAsset,
        preferredQualityHeight: Int? = null,
    ): OfflineMediaRecord? {
        val key = offlineKey(titleId, seasonNumber, episodeNumber)
        val existing = _state.value.records[key]
        if (existing != null && existing.status != OfflineDownloadStatus.FAILED) return existing

        val selectedVariant = preferredQualityHeight?.let { preferred ->
            asset.videoVariants.firstOrNull { it.height == preferred }
        } ?: asset.videoVariants.maxByOrNull { it.height }
        val videoUrl = selectedVariant?.url ?: asset.videoUrl ?: return null
        if (!videoUrl.startsWith("http://") && !videoUrl.startsWith("https://")) return null

        existing?.let { removeInternal(it) }

        val folder = safeSegment(key)
        val videoRelative = "film2/$folder/video.mp4"
        val videoFile = absoluteMovieFile(videoRelative)
        videoFile.parentFile?.mkdirs()
        videoFile.delete()

        val videoRequest = DownloadManager.Request(Uri.parse(videoUrl))
            .setTitle(displayName)
            .setDescription("Film2 çevrimdışı medya")
            .setMimeType("video/mp4")
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, videoRelative)
            .addRequestHeader("User-Agent", USER_AGENT)
        val videoId = manager.enqueue(videoRequest)

        val subtitleDownloads = asset.externalSubtitleTracks.mapIndexedNotNull { index, track ->
            if (!track.url.startsWith("http://") && !track.url.startsWith("https://")) return@mapIndexedNotNull null
            val ext = when {
                track.mimeType?.contains("subrip", ignoreCase = true) == true -> "srt"
                else -> track.url.substringBefore('?').substringAfterLast('.', "vtt").takeIf { it.length in 2..5 } ?: "vtt"
            }
            val relative = "film2/$folder/sub_${safeSegment(track.language)}_${index + 1}.$ext"
            absoluteMovieFile(relative).apply { parentFile?.mkdirs(); delete() }
            val id = manager.enqueue(
                DownloadManager.Request(Uri.parse(track.url))
                    .setTitle("$displayName • ${track.label ?: track.language}")
                    .setDescription("Film2 altyazı")
                    .setAllowedOverMetered(true)
                    .setAllowedOverRoaming(false)
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MOVIES, relative)
                    .addRequestHeader("User-Agent", USER_AGENT),
            )
            OfflineSubtitleFile(
                language = track.language,
                label = track.label,
                mimeType = track.mimeType,
                remoteUrl = track.url,
                relativePath = relative,
                downloadId = id,
            )
        }

        val record = OfflineMediaRecord(
            key = key,
            titleId = titleId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            displayName = displayName,
            remoteVideoUrl = videoUrl,
            videoRelativePath = videoRelative,
            videoDownloadId = videoId,
            subtitles = subtitleDownloads,
            audioLanguages = asset.audioLanguages,
            durationSeconds = asset.durationSeconds,
            qualityHeight = selectedVariant?.height,
            status = OfflineDownloadStatus.QUEUED,
        )
        mutate { it.copy(records = it.records + (key to record)) }
        return record
    }

    fun enqueueSeason(title: Title, seasonNumber: Int, preferredQualityHeight: Int? = null) {
        val season = title.seasons?.firstOrNull { it.seasonNumber == seasonNumber } ?: return
        season.episodes.forEach { episode ->
            val asset = episode.asset ?: return@forEach
            enqueue(
                titleId = title.id,
                seasonNumber = seasonNumber,
                episodeNumber = episode.episodeNumber,
                displayName = "${title.title} • S$seasonNumber:B${episode.episodeNumber} ${episode.title}",
                asset = asset,
                preferredQualityHeight = preferredQualityHeight,
            )
        }
    }

    fun delete(titleId: String, seasonNumber: Int? = null, episodeNumber: Int? = null) {
        val key = offlineKey(titleId, seasonNumber, episodeNumber)
        val record = _state.value.records[key] ?: return
        removeInternal(record)
        mutate { it.copy(records = it.records - key) }
    }

    fun deleteSeason(titleId: String, seasonNumber: Int) {
        val records = _state.value.records.values.filter {
            it.titleId == titleId && it.seasonNumber == seasonNumber
        }
        records.forEach(::removeInternal)
        val keys = records.map { it.key }.toSet()
        mutate { state -> state.copy(records = state.records.filterKeys { it !in keys }) }
    }

    fun localAsset(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        fallback: PlayableAsset,
    ): PlayableAsset? {
        val record = record(titleId, seasonNumber, episodeNumber) ?: return null
        if (record.status != OfflineDownloadStatus.COMPLETE) return null
        val video = absoluteMovieFile(record.videoRelativePath)
        if (!video.isFile || video.length() <= 0L) return null

        val localSubtitles = record.subtitles.mapNotNull { subtitle ->
            val file = absoluteMovieFile(subtitle.relativePath)
            if (!file.isFile || file.length() <= 0L) return@mapNotNull null
            ExternalMediaTrack(
                language = subtitle.language,
                label = subtitle.label,
                mimeType = subtitle.mimeType,
                url = Uri.fromFile(file).toString(),
            )
        }
        return fallback.copy(
            videoUrl = Uri.fromFile(video).toString(),
            masterPlaylistUrl = null,
            externalSubtitleTracks = localSubtitles,
            videoVariants = emptyList(),
        )
    }

    private fun removeInternal(record: OfflineMediaRecord) {
        manager.remove(record.videoDownloadId)
        if (record.subtitles.isNotEmpty()) {
            manager.remove(*record.subtitles.map { it.downloadId }.toLongArray())
        }
        absoluteMovieFile(record.videoRelativePath).delete()
        record.subtitles.forEach { absoluteMovieFile(it.relativePath).delete() }
        absoluteMovieFile(record.videoRelativePath).parentFile?.deleteRecursively()
    }

    private fun refreshProgress() {
        val current = _state.value
        if (current.records.isEmpty()) return
        var changed = false
        val updated = current.records.mapValues { (_, record) ->
            if (record.status == OfflineDownloadStatus.COMPLETE || record.status == OfflineDownloadStatus.FAILED) {
                return@mapValues record
            }
            val ids = listOf(record.videoDownloadId) + record.subtitles.map { it.downloadId }
            val snapshots = ids.map(::queryDownload)
            val failed = snapshots.any { it.status == DownloadManager.STATUS_FAILED }
            val complete = snapshots.isNotEmpty() && snapshots.all { it.status == DownloadManager.STATUS_SUCCESSFUL }
            val active = snapshots.any {
                it.status == DownloadManager.STATUS_RUNNING ||
                    it.status == DownloadManager.STATUS_PENDING ||
                    it.status == DownloadManager.STATUS_PAUSED
            }
            val downloaded = snapshots.sumOf { it.downloaded.coerceAtLeast(0L) }
            val total = snapshots.map { it.total }.filter { it > 0L }.sum()
            val nextStatus = when {
                failed -> OfflineDownloadStatus.FAILED
                complete -> OfflineDownloadStatus.COMPLETE
                active -> OfflineDownloadStatus.DOWNLOADING
                else -> record.status
            }
            val next = record.copy(
                status = nextStatus,
                downloadedBytes = downloaded,
                totalBytes = total,
                updatedAtEpochMs = if (nextStatus != record.status || downloaded != record.downloadedBytes) {
                    System.currentTimeMillis()
                } else record.updatedAtEpochMs,
            )
            if (next != record) changed = true
            next
        }
        if (changed) mutate { it.copy(records = updated) }
    }

    private data class DownloadSnapshot(val status: Int, val downloaded: Long, val total: Long)

    private fun queryDownload(id: Long): DownloadSnapshot {
        val cursor: Cursor = manager.query(DownloadManager.Query().setFilterById(id))
        cursor.use {
            if (!it.moveToFirst()) return DownloadSnapshot(DownloadManager.STATUS_FAILED, 0L, 0L)
            val status = it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = it.getLong(it.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            return DownloadSnapshot(status, downloaded, total)
        }
    }

    private fun absoluteMovieFile(relativePath: String): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(root, relativePath)
    }

    private fun mutate(transform: (OfflineLibraryState) -> OfflineLibraryState) {
        synchronized(lock) {
            val next = transform(_state.value)
            if (next == _state.value) return
            _state.value = next
            preferences.edit()
                .putString(KEY_STATE, json.encodeToString(OfflineLibraryState.serializer(), next))
                .apply()
        }
    }

    private fun readState(): OfflineLibraryState {
        val raw = preferences.getString(KEY_STATE, null) ?: return OfflineLibraryState()
        return runCatching { json.decodeFromString(OfflineLibraryState.serializer(), raw) }
            .getOrDefault(OfflineLibraryState())
    }

    private fun safeSegment(value: String): String = value
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9._-]+"), "_")
        .trim('_')
        .take(96)
        .ifBlank { "media" }

    companion object {
        private const val PREFS_NAME = "film2_offline_downloads"
        private const val KEY_STATE = "state_v1"
        private const val POLL_MS = 900L
        private const val USER_AGENT = "film2-android-player/1.4-offline"
    }
}
