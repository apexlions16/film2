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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * Background upload job: pushes attached media files to the active Hugging Face shard,
 * updates catalog/shards.json usage, then triggers the package-media GitHub Action. Runs
 * as a CoroutineWorker with an attached foreground notification so it survives the user
 * navigating away (or the file being several GB and taking a long time) — the explicit
 * "upload must be async/non-blocking" requirement from the spec.
 *
 * Enqueue via WorkManager, e.g.:
 *   val spec = Json.encodeToString(UploadJobSpec.serializer(), jobSpec)
 *   val request = OneTimeWorkRequestBuilder<UploadWorker>()
 *       .setInputData(workDataOf(UploadWorker.KEY_JOB_SPEC to spec))
 *       .build()
 *   WorkManager.getInstance(context).enqueue(request)
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getForegroundInfo(): ForegroundInfo = buildForegroundInfo(progressPercent = 0)

    override suspend fun doWork(): Result {
        val specJson = inputData.getString(KEY_JOB_SPEC)
            ?: return Result.failure(workDataOf(KEY_ERROR to "Is verisi (job spec) eksik"))
        val spec = try {
            json.decodeFromString(UploadJobSpec.serializer(), specJson)
        } catch (t: Throwable) {
            return Result.failure(workDataOf(KEY_ERROR to "Is verisi okunamadi: ${t.message}"))
        }

        setForegroundAsync(buildForegroundInfo(0))

        val app = applicationContext as Film2StudioApplication
        return try {
            runUpload(app, spec)
            Result.success()
        } catch (t: Throwable) {
            Result.failure(workDataOf(KEY_ERROR to (t.message ?: "Bilinmeyen yukleme hatasi")))
        }
    }

    private suspend fun runUpload(app: Film2StudioApplication, spec: UploadJobSpec) {
        val tokens = app.settingsRepository.currentTokens()
        val hfToken = tokens.huggingFaceToken
        val githubToken = tokens.githubPat
        requireNotNull(hfToken) { "Hugging Face tokeni ayarlanmamis" }
        requireNotNull(githubToken) { "GitHub PAT ayarlanmamis" }

        val registry = app.githubClient.getShardRegistry()
        val capacityChecked = app.shardRegistryManager.ensureCapacity(registry, hfToken)
        val shard = app.shardRegistryManager.getActiveShard(capacityChecked)

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
            var lastKnownTotal = 0L
            uploader.uploadFile(
                shardId = shard.id,
                repoPath = repoPath,
                localUri = Uri.parse(file.uri),
                onProgress = { sent, total ->
                    lastKnownTotal = total
                    val overallPercent = (((index * 100) + if (total > 0) (sent * 100 / total).toInt() else 0) / totalFiles)
                    setForegroundAsync(buildForegroundInfo(overallPercent))
                    setProgressAsync(workDataOf(KEY_PROGRESS_PERCENT to overallPercent))
                },
            )
            totalBytesUploaded += lastKnownTotal
        }

        val withUsage = app.shardRegistryManager.recordUsage(capacityChecked, shard.id, totalBytesUploaded)
        app.githubClient.putShardRegistry(withUsage)

        val combinedFile = spec.files.firstOrNull { it.role == "combined" }?.fileName
        val videoFile = spec.files.firstOrNull { it.role == "video" }?.fileName
        val audioFiles = spec.files.filter { it.role == "audio" }
            .associate { (it.language ?: "und") to it.fileName }
        val subtitleFiles = spec.files.filter { it.role == "subtitle" }
            .associate { (it.language ?: "und") to it.fileName }

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

    private fun buildForegroundInfo(progressPercent: Int): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Medya yukleme", NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Film2 Studio")
            .setContentText("Medya yukleniyor... %$progressPercent")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
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
        private const val CHANNEL_ID = "media_upload"
        private const val NOTIFICATION_ID = 4201
    }
}
