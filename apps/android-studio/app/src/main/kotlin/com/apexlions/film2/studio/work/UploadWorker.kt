package com.apexlions.film2.studio.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.apexlions.film2.studio.Film2StudioApplication
import com.apexlions.film2.studio.R
import com.apexlions.film2.studio.catalog.AssetStatus
import com.apexlions.film2.studio.catalog.Episode
import com.apexlions.film2.studio.catalog.ExternalMediaTrack
import com.apexlions.film2.studio.catalog.PlayableAsset
import com.apexlions.film2.studio.catalog.Season
import com.apexlions.film2.studio.catalog.Title
import com.apexlions.film2.studio.hf.HfAccountEntry
import com.apexlions.film2.studio.hf.HfUploadException
import com.apexlions.film2.studio.hf.HfUploadProgress
import com.apexlions.film2.studio.hf.HfUploadStage
import com.apexlions.film2.studio.hf.uploadFileWithFailover
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.Instant
import kotlin.math.roundToInt

@Serializable
data class UploadJobFile(
    /** "combined" | "video" | "audio" | "subtitle" */
    val role: String,
    val uri: String,
    val fileName: String,
    val language: String? = null,
)

@Serializable
data class UploadJobSpec(
    val titleId: String,
    /** "movie" | "episode" */
    val kind: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    /** "combined" | "separate" */
    val mode: String,
    val files: List<UploadJobFile>,
)

private data class UploadedDirectFile(
    val role: String,
    val language: String?,
    val url: String,
    val shardId: String,
    val repoPath: String,
    val bytes: Long,
)

/**
 * Long-running foreground direct-media upload.
 *
 * HLS paketleme yoktur. Kullanici bir video verdiyse tek video dosyasi olarak kalir;
 * harici ses ve altyazilar ayri sidecar dosyalaridir. Yukleme tamamlaninca katalog direkt
 * bu resolve URL'lere baglanir; GitHub Actions'ta yuzlerce segment uretilmez.
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val json = Json { ignoreUnknownKeys = true }
    private var lastNotificationPercent = -1
    private var lastNotificationAtMs = 0L
    private var lastProgressAtMs = 0L
    private var lastProgressStage = ""
    private var currentPercent = 0
    private var currentFileIndex = 0

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(0, "Yukleme bekliyor")

    override suspend fun doWork(): Result {
        val specJson = inputData.getString(KEY_JOB_SPEC)
            ?: return failure("Is verisi (job spec) eksik")
        val spec = try {
            json.decodeFromString(UploadJobSpec.serializer(), specJson)
        } catch (t: Throwable) {
            return failure("Is verisi okunamadi: ${t.message}")
        }
        if (spec.files.isEmpty()) return failure("Yuklenecek dosya bulunamadi")

        setForeground(buildForegroundInfo(0, "Yukleme baslatiliyor"))
        publish(
            percent = 0,
            stage = STAGE_QUEUED,
            message = "Yukleme baslatiliyor",
            fileIndex = 0,
            fileCount = spec.files.size,
        )

        val app = applicationContext as Film2StudioApplication
        return try {
            runUpload(app, spec)
            val doneMessage = "Yukleme tamamlandi; medya Player'da hazir"
            publish(
                percent = 100,
                stage = STAGE_COMPLETE,
                message = doneMessage,
                fileIndex = spec.files.size,
                fileCount = spec.files.size,
            )
            Result.success(
                workDataOf(
                    KEY_PROGRESS_PERCENT to 100,
                    KEY_STAGE to STAGE_COMPLETE,
                    KEY_MESSAGE to doneMessage,
                    KEY_FILE_INDEX to spec.files.size,
                    KEY_FILE_COUNT to spec.files.size,
                ),
            )
        } catch (t: Throwable) {
            val message = t.message ?: "Bilinmeyen yukleme hatasi"
            if (shouldRetry(t) && runAttemptCount < MAX_RETRY_COUNT) {
                publish(
                    percent = currentPercent,
                    stage = STAGE_RETRYING,
                    message = "Baglanti hatasi; otomatik yeniden denenecek (${runAttemptCount + 1}/$MAX_RETRY_COUNT)",
                    fileIndex = currentFileIndex,
                    fileCount = spec.files.size,
                )
                Result.retry()
            } else {
                publish(
                    percent = currentPercent,
                    stage = STAGE_FAILED,
                    message = message,
                    fileIndex = currentFileIndex,
                    fileCount = spec.files.size,
                )
                failure(message)
            }
        }
    }

    private suspend fun runUpload(app: Film2StudioApplication, spec: UploadJobSpec) {
        val tokens = app.settingsRepository.currentTokens()
        require(!tokens.githubPat.isNullOrBlank()) { "GitHub PAT ayarlanmamis" }

        val hfAccounts = app.settingsRepository.currentHfAccounts()
            .map { HfAccountEntry(namespace = it.namespace, token = it.token) }
        require(hfAccounts.isNotEmpty()) {
            "Hicbir Hugging Face hesabi eklenmemis. Ayarlar ekranindan en az bir hesap ekleyin."
        }

        if (spec.kind == "episode") {
            require(spec.seasonNumber != null && spec.episodeNumber != null) {
                "Dizi yuklemesi icin sezon ve bolum numarasi gerekli"
            }
        }

        publish(1, STAGE_CHECKING, "Depolama hesabi ve shard kontrol ediliyor", 0, spec.files.size)
        val registry = app.githubClient.getShardRegistry()
        val capacityChecked = app.shardRegistryManager.ensureCapacity(registry, hfAccounts)
        var currentRegistry = capacityChecked.registry

        val mediaPrefix = if (spec.kind == "episode") {
            "media/${spec.titleId}/s${spec.seasonNumber}e${spec.episodeNumber}"
        } else {
            "media/${spec.titleId}"
        }

        val uploader = app.newHfUploader()
        val uploaded = mutableListOf<UploadedDirectFile>()
        val totalFiles = spec.files.size

        spec.files.forEachIndexed { index, file ->
            val targetName = directFileName(file, index)
            val repoPath = "$mediaPrefix/$targetName"
            var speedStage: HfUploadStage? = null
            var speedStartedAtMs = 0L
            var speedStartedBytes = 0L

            val result = uploadFileWithFailover(
                uploader = uploader,
                shardRegistryManager = app.shardRegistryManager,
                localUri = Uri.parse(file.uri),
                repoPath = repoPath,
                registry = currentRegistry,
                accounts = hfAccounts,
                onProgress = { hfProgress ->
                    if (speedStage != hfProgress.stage) {
                        speedStage = hfProgress.stage
                        speedStartedAtMs = System.currentTimeMillis()
                        speedStartedBytes = hfProgress.bytesProcessed
                    }
                    val elapsedMs = (System.currentTimeMillis() - speedStartedAtMs).coerceAtLeast(1L)
                    val processedSinceStage = (hfProgress.bytesProcessed - speedStartedBytes).coerceAtLeast(0L)
                    val bytesPerSecond = processedSinceStage * 1000L / elapsedMs
                    val etaSeconds = if (bytesPerSecond > 0L && hfProgress.totalBytes > 0L) {
                        (hfProgress.totalBytes - hfProgress.bytesProcessed).coerceAtLeast(0L) / bytesPerSecond
                    } else {
                        -1L
                    }

                    val overallPercent = calculateOverallPercent(index, totalFiles, hfProgress)
                    publishAsync(
                        percent = overallPercent,
                        stage = hfProgress.stage.toUiStage(),
                        message = hfProgress.message,
                        fileIndex = index + 1,
                        fileCount = totalFiles,
                        fileName = targetName,
                        bytesProcessed = hfProgress.bytesProcessed,
                        totalBytes = hfProgress.totalBytes,
                        bytesPerSecond = bytesPerSecond,
                        etaSeconds = etaSeconds,
                    )
                },
            )

            currentRegistry = app.shardRegistryManager.recordUsage(
                result.registry,
                result.shard.id,
                result.bytes,
            )
            uploaded += UploadedDirectFile(
                role = file.role,
                language = file.language,
                url = result.url,
                shardId = result.shard.id,
                repoPath = repoPath,
                bytes = result.bytes,
            )

            publish(
                percent = (((index + 1).toDouble() / totalFiles) * UPLOAD_PHASE_MAX_PERCENT).roundToInt(),
                stage = STAGE_FILE_COMPLETE,
                message = "$targetName yuklendi",
                fileIndex = index + 1,
                fileCount = totalFiles,
                fileName = targetName,
                bytesProcessed = result.bytes,
                totalBytes = result.bytes,
            )
        }

        publish(92, STAGE_CATALOG, "Shard kullanim bilgisi GitHub'a yaziliyor", totalFiles, totalFiles)
        app.githubClient.putShardRegistry(currentRegistry)

        publish(96, STAGE_CATALOG, "Video ve track bilgileri kataloga baglaniyor", totalFiles, totalFiles)
        val video = uploaded.firstOrNull { it.role == "combined" || it.role == "video" }
            ?: throw IllegalStateException("Video dosyasi yuklenmedi")
        val asset = buildDirectAsset(video, uploaded)
        val existingTitle = app.githubClient.getTitle(spec.titleId)
            ?: throw IllegalStateException("Katalog basligi bulunamadi: ${spec.titleId}")
        val updatedTitle = attachAsset(existingTitle, spec, asset, video.shardId)
        app.githubClient.putTitle(updatedTitle)

        publish(99, STAGE_CATALOG, "Katalog hazir; Player MP4'u dogrudan acacak", totalFiles, totalFiles)
    }

    private fun buildDirectAsset(
        video: UploadedDirectFile,
        uploaded: List<UploadedDirectFile>,
    ): PlayableAsset {
        val audioTracks = uploaded.filter { it.role == "audio" }.map { file ->
            val lang = normalizedLanguage(file.language)
            ExternalMediaTrack(
                language = lang,
                label = lang,
                url = file.url,
                mimeType = audioMime(file.repoPath),
            )
        }
        val subtitleTracks = uploaded.filter { it.role == "subtitle" }.map { file ->
            val lang = normalizedLanguage(file.language)
            ExternalMediaTrack(
                language = lang,
                label = lang,
                url = file.url,
                mimeType = subtitleMime(file.repoPath),
            )
        }
        return PlayableAsset(
            videoUrl = video.url,
            masterPlaylistUrl = null,
            audioLanguages = audioTracks.map { it.language },
            subtitleLanguages = subtitleTracks.map { it.language },
            externalAudioTracks = audioTracks,
            externalSubtitleTracks = subtitleTracks,
        )
    }

    private fun attachAsset(
        title: Title,
        spec: UploadJobSpec,
        asset: PlayableAsset,
        videoShardId: String,
    ): Title {
        val now = Instant.now().toString()
        if (spec.kind != "episode") {
            return title.copy(
                status = AssetStatus.READY,
                updatedAt = now,
                shardId = videoShardId,
                asset = asset,
            )
        }

        val seasonNumber = requireNotNull(spec.seasonNumber)
        val episodeNumber = requireNotNull(spec.episodeNumber)
        val seasons = title.seasons.orEmpty().toMutableList()
        val seasonIndex = seasons.indexOfFirst { it.seasonNumber == seasonNumber }

        if (seasonIndex < 0) {
            seasons += Season(
                seasonNumber = seasonNumber,
                name = "Sezon $seasonNumber",
                overview = "",
                episodes = listOf(
                    Episode(
                        episodeNumber = episodeNumber,
                        title = "$episodeNumber. Bolum",
                        overview = "",
                        status = AssetStatus.READY,
                        shardId = videoShardId,
                        asset = asset,
                    ),
                ),
            )
        } else {
            val season = seasons[seasonIndex]
            val episodes = season.episodes.toMutableList()
            val episodeIndex = episodes.indexOfFirst { it.episodeNumber == episodeNumber }
            if (episodeIndex < 0) {
                episodes += Episode(
                    episodeNumber = episodeNumber,
                    title = "$episodeNumber. Bolum",
                    overview = "",
                    status = AssetStatus.READY,
                    shardId = videoShardId,
                    asset = asset,
                )
            } else {
                episodes[episodeIndex] = episodes[episodeIndex].copy(
                    status = AssetStatus.READY,
                    shardId = videoShardId,
                    asset = asset,
                )
            }
            seasons[seasonIndex] = season.copy(episodes = episodes.sortedBy { it.episodeNumber })
        }

        return title.copy(
            status = AssetStatus.READY,
            updatedAt = now,
            seasons = seasons.sortedBy { it.seasonNumber },
        )
    }

    private fun directFileName(file: UploadJobFile, index: Int): String {
        val ext = file.fileName.substringAfterLast('.', "").lowercase()
            .replace(Regex("[^a-z0-9]"), "")
        val lang = normalizedLanguage(file.language)
        return when (file.role) {
            "combined", "video" -> "video.${ext.ifBlank { "mp4" }}"
            "audio" -> "audio_${lang}_${index + 1}.${ext.ifBlank { "m4a" }}"
            "subtitle" -> "subs_${lang}_${index + 1}.${ext.ifBlank { "vtt" }}"
            else -> "file_${index + 1}.${ext.ifBlank { "bin" }}"
        }
    }

    private fun normalizedLanguage(value: String?): String = value
        ?.trim()
        ?.lowercase()
        ?.replace(Regex("[^a-z0-9-]"), "")
        ?.takeIf { it.isNotBlank() }
        ?: "und"

    private fun audioMime(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
        "m4a", "mp4" -> "audio/mp4"
        // Ham ADTS AAC bir MP4 container degildir. Onceki audio/mp4 degeri Media3'e
        // yanlis extractor sectirip sessizlik/glitch uretebiliyordu.
        "aac" -> "audio/mp4a-latm"
        "mp3" -> "audio/mpeg"
        "ogg", "opus" -> "audio/ogg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        else -> null
    }

    private fun subtitleMime(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "srt" -> "application/x-subrip"
        else -> "text/vtt"
    }

    private fun calculateOverallPercent(
        fileIndex: Int,
        fileCount: Int,
        progress: HfUploadProgress,
    ): Int {
        val ratio = if (progress.totalBytes > 0L) {
            (progress.bytesProcessed.toDouble() / progress.totalBytes).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val withinFile = when (progress.stage) {
            HfUploadStage.PREPARING -> ratio * 0.18
            HfUploadStage.CHECKING -> 0.20
            HfUploadStage.UPLOADING -> 0.20 + ratio * 0.72
            HfUploadStage.FINALIZING -> 0.96
        }
        val filesDoneFraction = (fileIndex + withinFile) / fileCount.toDouble()
        return (filesDoneFraction * UPLOAD_PHASE_MAX_PERCENT).roundToInt()
            .coerceIn(0, UPLOAD_PHASE_MAX_PERCENT)
    }

    private fun HfUploadStage.toUiStage(): String = when (this) {
        HfUploadStage.PREPARING -> STAGE_PREPARING
        HfUploadStage.CHECKING -> STAGE_CHECKING
        HfUploadStage.UPLOADING -> STAGE_UPLOADING
        HfUploadStage.FINALIZING -> STAGE_FINALIZING
    }

    private suspend fun publish(
        percent: Int,
        stage: String,
        message: String,
        fileIndex: Int,
        fileCount: Int,
        fileName: String = "",
        bytesProcessed: Long = 0L,
        totalBytes: Long = 0L,
        bytesPerSecond: Long = 0L,
        etaSeconds: Long = -1L,
    ) {
        val data = progressData(
            percent,
            stage,
            message,
            fileIndex,
            fileCount,
            fileName,
            bytesProcessed,
            totalBytes,
            bytesPerSecond,
            etaSeconds,
        )
        currentPercent = percent.coerceIn(0, 100)
        currentFileIndex = fileIndex
        lastProgressStage = stage
        lastProgressAtMs = System.currentTimeMillis()
        setProgress(data)
        setForeground(buildForegroundInfo(percent, message))
        lastNotificationPercent = percent
        lastNotificationAtMs = System.currentTimeMillis()
    }

    private fun publishAsync(
        percent: Int,
        stage: String,
        message: String,
        fileIndex: Int,
        fileCount: Int,
        fileName: String,
        bytesProcessed: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        etaSeconds: Long,
    ) {
        val now = System.currentTimeMillis()
        val stageChanged = stage != lastProgressStage
        if (!stageChanged && percent == currentPercent && now - lastProgressAtMs < PROGRESS_THROTTLE_MS) return

        currentPercent = percent.coerceIn(0, 100)
        currentFileIndex = fileIndex
        lastProgressStage = stage
        lastProgressAtMs = now
        val data = progressData(
            percent,
            stage,
            message,
            fileIndex,
            fileCount,
            fileName,
            bytesProcessed,
            totalBytes,
            bytesPerSecond,
            etaSeconds,
        )
        setProgressAsync(data)

        if (percent != lastNotificationPercent || now - lastNotificationAtMs >= NOTIFICATION_THROTTLE_MS) {
            setForegroundAsync(buildForegroundInfo(percent, message))
            lastNotificationPercent = percent
            lastNotificationAtMs = now
        }
    }

    private fun progressData(
        percent: Int,
        stage: String,
        message: String,
        fileIndex: Int,
        fileCount: Int,
        fileName: String,
        bytesProcessed: Long,
        totalBytes: Long,
        bytesPerSecond: Long,
        etaSeconds: Long,
    ) = workDataOf(
        KEY_PROGRESS_PERCENT to percent.coerceIn(0, 100),
        KEY_STAGE to stage,
        KEY_MESSAGE to message,
        KEY_FILE_INDEX to fileIndex,
        KEY_FILE_COUNT to fileCount,
        KEY_FILE_NAME to fileName,
        KEY_BYTES_PROCESSED to bytesProcessed,
        KEY_TOTAL_BYTES to totalBytes,
        KEY_BYTES_PER_SECOND to bytesPerSecond,
        KEY_ETA_SECONDS to etaSeconds,
    )

    private fun failure(message: String): Result = Result.failure(
        workDataOf(
            KEY_ERROR to message,
            KEY_PROGRESS_PERCENT to currentPercent,
            KEY_STAGE to STAGE_FAILED,
            KEY_MESSAGE to message,
            KEY_FILE_INDEX to currentFileIndex,
        ),
    )

    private fun shouldRetry(t: Throwable): Boolean {
        if (generateSequence(t) { it.cause }.any { it is IOException }) return true
        val hfError = generateSequence(t) { it.cause }.filterIsInstance<HfUploadException>().firstOrNull()
            ?: return false
        val code = hfError.statusCode ?: return false
        return code == 408 || code == 425 || code == 429 || code in 500..599
    }

    private fun buildForegroundInfo(progressPercent: Int, message: String): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Medya yukleme", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Film2 Studio - %$progressPercent")
            .setContentText(message.take(80))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(progressPercent < 100)
            .setOnlyAlertOnce(true)
            .setProgress(100, progressPercent, false)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_JOB_SPEC = "job_spec"
        const val KEY_ERROR = "error"
        const val KEY_PROGRESS_PERCENT = "progress_percent"
        const val KEY_STAGE = "stage"
        const val KEY_MESSAGE = "message"
        const val KEY_FILE_INDEX = "file_index"
        const val KEY_FILE_COUNT = "file_count"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_BYTES_PROCESSED = "bytes_processed"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_BYTES_PER_SECOND = "bytes_per_second"
        const val KEY_ETA_SECONDS = "eta_seconds"

        const val STAGE_QUEUED = "queued"
        const val STAGE_PREPARING = "preparing"
        const val STAGE_CHECKING = "checking"
        const val STAGE_UPLOADING = "uploading"
        const val STAGE_FINALIZING = "finalizing"
        const val STAGE_FILE_COMPLETE = "file_complete"
        const val STAGE_CATALOG = "catalog"
        const val STAGE_DISPATCHING = "dispatching"
        const val STAGE_RETRYING = "retrying"
        const val STAGE_COMPLETE = "complete"
        const val STAGE_FAILED = "failed"

        const val TAG_ALL_UPLOADS = "film2_media_upload"
        fun tagForTitle(titleId: String): String = "film2_media_upload:$titleId"

        private const val UPLOAD_PHASE_MAX_PERCENT = 90
        private const val MAX_RETRY_COUNT = 3
        private const val PROGRESS_THROTTLE_MS = 300L
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val CHANNEL_ID = "media_upload"
        private const val NOTIFICATION_ID = 4201
    }
}
