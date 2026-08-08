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
import com.apexlions.film2.studio.dispatch.MediaKind
import com.apexlions.film2.studio.dispatch.PackageMediaRequest
import com.apexlions.film2.studio.dispatch.UploadMode
import com.apexlions.film2.studio.hf.HfAccountEntry
import com.apexlions.film2.studio.hf.HfUploadException
import com.apexlions.film2.studio.hf.HfUploadProgress
import com.apexlions.film2.studio.hf.HfUploadStage
import com.apexlions.film2.studio.hf.uploadFileWithFailover
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
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

/**
 * Long-running foreground upload with UI-observable progress. WorkManager persists the
 * job, so it continues when the screen closes. Every meaningful phase is published in
 * WorkInfo.progress and mirrored in the foreground notification.
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
            publish(
                percent = 100,
                stage = STAGE_COMPLETE,
                message = "Yukleme tamamlandi; paketleme GitHub Actions'ta devam ediyor",
                fileIndex = spec.files.size,
                fileCount = spec.files.size,
            )
            Result.success(
                workDataOf(
                    KEY_PROGRESS_PERCENT to 100,
                    KEY_STAGE to STAGE_COMPLETE,
                    KEY_MESSAGE to "Yukleme tamamlandi; paketleme GitHub Actions'ta devam ediyor",
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
        val hfAccounts = app.settingsRepository.currentHfAccounts()
            .map { HfAccountEntry(namespace = it.namespace, token = it.token) }
        val githubToken = requireNotNull(tokens.githubPat?.takeIf { it.isNotBlank() }) {
            "GitHub PAT ayarlanmamis"
        }
        require(hfAccounts.isNotEmpty()) {
            "Hicbir Hugging Face hesabi eklenmemis. Ayarlar ekranindan en az bir hesap ekleyin."
        }

        publish(1, STAGE_CHECKING, "Depolama hesabi ve shard kontrol ediliyor", 0, spec.files.size)
        val registry = app.githubClient.getShardRegistry()
        val capacityChecked = app.shardRegistryManager.ensureCapacity(registry, hfAccounts)
        var currentRegistry = capacityChecked.registry
        var shard = app.shardRegistryManager.getActiveShard(currentRegistry)

        val incomingPrefix = if (spec.kind == "episode") {
            "incoming/${spec.titleId}/s${spec.seasonNumber}e${spec.episodeNumber}"
        } else {
            "incoming/${spec.titleId}"
        }

        val uploader = app.newHfUploader()
        var totalBytesUploaded = 0L
        val totalFiles = spec.files.size

        spec.files.forEachIndexed { index, file ->
            val repoPath = "$incomingPrefix/${file.fileName}"
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
                        ((hfProgress.totalBytes - hfProgress.bytesProcessed).coerceAtLeast(0L) / bytesPerSecond)
                    } else {
                        -1L
                    }

                    val overallPercent = calculateOverallPercent(
                        fileIndex = index,
                        fileCount = totalFiles,
                        progress = hfProgress,
                    )
                    publishAsync(
                        percent = overallPercent,
                        stage = hfProgress.stage.toUiStage(),
                        message = hfProgress.message,
                        fileIndex = index + 1,
                        fileCount = totalFiles,
                        fileName = file.fileName,
                        bytesProcessed = hfProgress.bytesProcessed,
                        totalBytes = hfProgress.totalBytes,
                        bytesPerSecond = bytesPerSecond,
                        etaSeconds = etaSeconds,
                    )
                },
            )
            currentRegistry = result.registry
            shard = result.shard
            totalBytesUploaded += result.bytes

            publish(
                percent = (((index + 1).toDouble() / totalFiles) * UPLOAD_PHASE_MAX_PERCENT).roundToInt(),
                stage = STAGE_FILE_COMPLETE,
                message = "${file.fileName} yuklendi",
                fileIndex = index + 1,
                fileCount = totalFiles,
                fileName = file.fileName,
                bytesProcessed = result.bytes,
                totalBytes = result.bytes,
            )
        }

        publish(92, STAGE_CATALOG, "Shard kullanim bilgisi GitHub'a yaziliyor", totalFiles, totalFiles)
        val withUsage = app.shardRegistryManager.recordUsage(currentRegistry, shard.id, totalBytesUploaded)
        app.githubClient.putShardRegistry(withUsage)

        val combinedFile = spec.files.firstOrNull { it.role == "combined" }?.fileName
        val videoFile = spec.files.firstOrNull { it.role == "video" }?.fileName
        val audioFiles = spec.files.filter { it.role == "audio" }
            .associate { (it.language ?: "und") to it.fileName }
        val subtitleFiles = spec.files.filter { it.role == "subtitle" }
            .associate { (it.language ?: "und") to it.fileName }

        publish(97, STAGE_DISPATCHING, "Paketleme islemi GitHub Actions'a gonderiliyor", totalFiles, totalFiles)
        val request = PackageMediaRequest(
            titleId = spec.titleId,
            kind = if (spec.kind == "episode") MediaKind.EPISODE else MediaKind.MOVIE,
            seasonNumber = spec.seasonNumber,
            episodeNumber = spec.episodeNumber,
            shardId = shard.id,
            mode = if (spec.mode == "separate") UploadMode.SEPARATE else UploadMode.COMBINED,
            incomingPrefix = incomingPrefix,
            combinedFile = combinedFile,
            videoFile = videoFile,
            audioFiles = audioFiles,
            subtitleFiles = subtitleFiles,
        )
        app.packageMediaDispatcher.dispatch(request, githubToken)
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
        return (filesDoneFraction * UPLOAD_PHASE_MAX_PERCENT).roundToInt().coerceIn(0, UPLOAD_PHASE_MAX_PERCENT)
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
