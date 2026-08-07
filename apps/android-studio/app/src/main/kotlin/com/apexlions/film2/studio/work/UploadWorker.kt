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
import java.util.Locale
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
 * Long-running Android upload worker.
 *
 * Preferred path: MP4 + AAC/M4A is muxed on-device without transcoding, the final single
 * MP4 is uploaded once and the catalog becomes ready immediately. GitHub Actions is only
 * a compatibility fallback for containers/codecs the Android platform muxer cannot copy.
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
    private var completionMessage = "Dosyalar yuklendi; tek MP4 hazirlama GitHub Actions'ta devam ediyor"

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
        publish(0, STAGE_QUEUED, "Yukleme baslatiliyor", 0, spec.files.size)

        val app = applicationContext as Film2StudioApplication
        return try {
            runUpload(app, spec)
            val doneMessage = completionMessage
            publish(100, STAGE_COMPLETE, doneMessage, spec.files.size, spec.files.size)
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
                    currentPercent,
                    STAGE_RETRYING,
                    "Baglanti hatasi; otomatik yeniden denenecek (${runAttemptCount + 1}/$MAX_RETRY_COUNT)",
                    currentFileIndex,
                    spec.files.size,
                )
                Result.retry()
            } else {
                publish(currentPercent, STAGE_FAILED, message, currentFileIndex, spec.files.size)
                failure(message)
            }
        }
    }

    private suspend fun runUpload(app: Film2StudioApplication, spec: UploadJobSpec) {
        val tokens = app.settingsRepository.currentTokens()
        val githubToken = tokens.githubPat?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("GitHub PAT ayarlanmamis")

        val hfAccounts = app.settingsRepository.currentHfAccounts()
            .map { HfAccountEntry(namespace = it.namespace, token = it.token) }
        require(hfAccounts.isNotEmpty()) {
            "Hicbir Hugging Face hesabi eklenmemis. Ayarlardan en az bir hesap ekleyin."
        }

        if (spec.kind == "episode") {
            require(spec.seasonNumber != null && spec.episodeNumber != null) {
                "Dizi yuklemesi icin sezon ve bolum numarasi gerekli"
            }
        }

        publish(1, STAGE_CHECKING, "GitHub kimligi dogrulaniyor", 0, spec.files.size)
        app.githubClient.verifyAuthentication()

        // Perceived performance: the title/episode is visible in Player as 'processing'
        // before any multi-GB transfer begins.
        val fastCoordinator = FastPublishCoordinator(applicationContext)
        publish(2, STAGE_CATALOG, "Icerik Player'a hazirlaniyor olarak ekleniyor", 0, spec.files.size)
        fastCoordinator.markProcessing(app, spec)

        // Zero-transcode fast path. If it succeeds there is no Actions queue, no HF ->
        // runner download and no runner -> HF second full upload.
        val fastResult = fastCoordinator.tryPublish(
            app = app,
            spec = spec,
            accounts = hfAccounts,
            onProgress = { percent, message, bytes, total ->
                val stage = when {
                    message.contains("birlestiriliyor", ignoreCase = true) -> STAGE_FAST_MUX
                    percent >= 95 -> STAGE_CATALOG
                    else -> STAGE_UPLOADING
                }
                publishAsync(
                    percent = percent,
                    stage = stage,
                    message = message,
                    fileIndex = if (percent >= 29) 1 else 0,
                    fileCount = spec.files.size,
                    fileName = if (percent >= 29) "final.mp4" else "",
                    bytesProcessed = bytes,
                    totalBytes = total,
                    bytesPerSecond = 0,
                    etaSeconds = -1,
                )
            },
        )
        if (fastResult != null) {
            completionMessage = fastResult.message
            return
        }

        // Compatibility fallback: old proven path stays intact for MKV/unsupported codec
        // or low temporary device storage.
        publish(6, STAGE_DISPATCHING, "Hizli yol uygun degil; guvenli sunucu fallback'i kullaniliyor", 0, spec.files.size)
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
                    publishAsync(
                        percent = calculateOverallPercent(index, totalFiles, hfProgress),
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

        val video = uploaded.firstOrNull { it.role == "combined" || it.role == "video" }
            ?: throw IllegalStateException("Video dosyasi yuklenmedi")

        val shardIds = uploaded.map { it.shardId }.distinct()
        require(shardIds.size == 1) {
            "Bu yuklemedeki dosyalar farkli Hugging Face shard'larina dagildi. Tek MP4 remux icin tekrar deneyin."
        }

        val audioFiles = uploaded
            .filter { it.role == "audio" }
            .associate { normalizedLanguage(it.language) to it.repoPath.substringAfterLast('/') }
        val subtitleFiles = uploaded
            .filter { it.role == "subtitle" }
            .associate { normalizedLanguage(it.language) to it.repoPath.substringAfterLast('/') }

        publish(96, STAGE_DISPATCHING, "Sesler tek MP4'e mux edilmek uzere gonderiliyor", totalFiles, totalFiles)
        val request = PackageMediaRequest(
            titleId = spec.titleId,
            kind = if (spec.kind == "episode") MediaKind.EPISODE else MediaKind.MOVIE,
            seasonNumber = spec.seasonNumber,
            episodeNumber = spec.episodeNumber,
            shardId = video.shardId,
            mode = if (spec.mode == "separate") UploadMode.SEPARATE else UploadMode.COMBINED,
            incomingPrefix = mediaPrefix,
            combinedFile = if (spec.mode == "combined") video.repoPath.substringAfterLast('/') else null,
            videoFile = if (spec.mode == "separate") video.repoPath.substringAfterLast('/') else null,
            audioFiles = audioFiles,
            subtitleFiles = subtitleFiles,
        )
        app.packageMediaDispatcher.dispatch(request, githubToken)
        publish(99, STAGE_DISPATCHING, "Tek MP4 remux islemi baslatildi", totalFiles, totalFiles)
    }

    private fun directFileName(file: UploadJobFile, index: Int): String {
        val ext = file.fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
        val lang = normalizedLanguage(file.language)
        return when (file.role) {
            "combined", "video" -> "video.${ext.ifBlank { "mp4" }}"
            "audio" -> "audio_${lang}_${index + 1}.${ext.ifBlank { "m4a" }}"
            "subtitle" -> "subs_${lang}_${index + 1}.${ext.ifBlank { "vtt" }}"
            else -> "file_${index + 1}.${ext.ifBlank { "bin" }}"
        }
    }

    /** Kullanici ister tr/en ister Turkce/Ingilizce yazsin; katalog/FFmpeg icin ISO kodu uret. */
    private fun normalizedLanguage(value: String?): String {
        val raw = value?.trim().orEmpty()
        val key = raw
            .lowercase(Locale("tr", "TR"))
            .replace('ı', 'i')
            .replace('ğ', 'g')
            .replace('ü', 'u')
            .replace('ş', 's')
            .replace('ö', 'o')
            .replace('ç', 'c')
            .replace(Regex("[^a-z0-9]"), "")
        return when (key) {
            "en", "eng", "english", "ingilizce" -> "eng"
            "tr", "tur", "turkish", "turkce", "trke" -> "tur"
            "de", "deu", "ger", "german", "almanca" -> "deu"
            "fr", "fra", "fre", "french", "fransizca" -> "fra"
            "es", "spa", "spanish", "ispanyolca" -> "spa"
            else -> key.takeIf { it.length == 3 } ?: "und"
        }
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
            percent, stage, message, fileIndex, fileCount, fileName,
            bytesProcessed, totalBytes, bytesPerSecond, etaSeconds,
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
            percent, stage, message, fileIndex, fileCount, fileName,
            bytesProcessed, totalBytes, bytesPerSecond, etaSeconds,
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
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Medya yukleme", NotificationManager.IMPORTANCE_LOW),
            )
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
        const val STAGE_FAST_MUX = "fast_mux"
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
