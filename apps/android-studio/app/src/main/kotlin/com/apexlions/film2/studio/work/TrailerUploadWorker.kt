package com.apexlions.film2.studio.work

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.apexlions.film2.studio.Film2StudioApplication
import com.apexlions.film2.studio.hf.HfAccountEntry
import com.apexlions.film2.studio.hf.HfUploadProgress
import com.apexlions.film2.studio.hf.HfUploadStage
import com.apexlions.film2.studio.hf.uploadFileWithFailover
import java.time.Instant

/**
 * Mevcut bir basliga sonradan trailer/preview ekler.
 * Ana film veya bolum medyasina dokunmaz; sadece media/{titleId}/trailer.<ext> yukler
 * ve katalogdaki Title.trailerUrl alanini gunceller.
 */
class TrailerUploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val titleId = inputData.getString(KEY_TITLE_ID)?.takeIf { it.isNotBlank() }
            ?: return failure("Icerik kimligi eksik")
        val uriText = inputData.getString(KEY_URI)?.takeIf { it.isNotBlank() }
            ?: return failure("Trailer dosyasi secilmedi")
        val extension = inputData.getString(KEY_EXTENSION)
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.isNotBlank() }
            ?: "mp4"

        val app = applicationContext as Film2StudioApplication
        return try {
            publish(1, "Depolama hesabi kontrol ediliyor")
            val accounts = app.settingsRepository.currentHfAccounts()
                .map { HfAccountEntry(namespace = it.namespace, token = it.token) }
            require(accounts.isNotEmpty()) { "Hugging Face hesabi eklenmemis" }

            val registry = app.githubClient.getShardRegistry()
            val capacity = app.shardRegistryManager.ensureCapacity(registry, accounts)
            val repoPath = "media/$titleId/trailer.$extension"

            val upload = uploadFileWithFailover(
                uploader = app.newHfUploader(),
                shardRegistryManager = app.shardRegistryManager,
                localUri = Uri.parse(uriText),
                repoPath = repoPath,
                registry = capacity.registry,
                accounts = accounts,
                onProgress = { progress -> publishProgress(progress) },
            )

            publish(94, "Katalog guncelleniyor")
            val updatedRegistry = app.shardRegistryManager.recordUsage(
                upload.registry,
                upload.shard.id,
                upload.bytes,
            )
            app.githubClient.putShardRegistry(updatedRegistry)

            val title = app.githubClient.getTitle(titleId)
                ?: throw IllegalStateException("Katalogda $titleId bulunamadi")
            app.githubClient.putTitle(
                title.copy(
                    trailerUrl = upload.url,
                    updatedAt = Instant.now().toString(),
                ),
            )

            publish(100, "Trailer hazir")
            Result.success(
                workDataOf(
                    KEY_PROGRESS to 100,
                    KEY_MESSAGE to "Trailer yuklendi ve katalog guncellendi",
                    KEY_URL to upload.url,
                ),
            )
        } catch (t: Throwable) {
            failure(t.message ?: "Trailer yuklenemedi")
        }
    }

    private fun publishProgress(progress: HfUploadProgress) {
        val ratio = if (progress.totalBytes > 0L) {
            progress.bytesProcessed.toDouble() / progress.totalBytes.toDouble()
        } else 0.0
        val percent = when (progress.stage) {
            HfUploadStage.PREPARING -> (2 + ratio * 12).toInt()
            HfUploadStage.CHECKING -> 15
            HfUploadStage.UPLOADING -> (15 + ratio * 75).toInt()
            HfUploadStage.FINALIZING -> 92
        }.coerceIn(0, 93)
        setProgressAsync(
            workDataOf(
                KEY_PROGRESS to percent,
                KEY_MESSAGE to progress.message,
                KEY_BYTES to progress.bytesProcessed,
                KEY_TOTAL_BYTES to progress.totalBytes,
            ),
        )
    }

    private suspend fun publish(percent: Int, message: String) {
        setProgress(
            workDataOf(
                KEY_PROGRESS to percent,
                KEY_MESSAGE to message,
            ),
        )
    }

    private fun failure(message: String): Result = Result.failure(
        workDataOf(
            KEY_PROGRESS to 0,
            KEY_MESSAGE to message,
            KEY_ERROR to message,
        ),
    )

    companion object {
        const val KEY_TITLE_ID = "trailer_title_id"
        const val KEY_URI = "trailer_uri"
        const val KEY_EXTENSION = "trailer_extension"
        const val KEY_PROGRESS = "trailer_progress"
        const val KEY_MESSAGE = "trailer_message"
        const val KEY_ERROR = "trailer_error"
        const val KEY_URL = "trailer_url"
        const val KEY_BYTES = "trailer_bytes"
        const val KEY_TOTAL_BYTES = "trailer_total_bytes"

        fun tagForTitle(titleId: String) = "film2_trailer_$titleId"
    }
}
