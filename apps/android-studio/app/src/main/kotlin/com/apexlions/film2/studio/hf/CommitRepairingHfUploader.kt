package com.apexlions.film2.studio.hf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Compatibility layer for the Hub commit endpoint.
 *
 * The first Android uploader build used a String RequestBody with
 * `application/x-ndjson; charset=utf-8`. Real-device testing showed that the current
 * Hub commit endpoint can parse that request incorrectly and return:
 *   Invalid input: expected string, received undefined -> at value.summary
 *
 * The official huggingface_hub client sends the commit as raw UTF-8 bytes with the exact
 * `application/x-ndjson` media type. When the delegate reaches that specific commit-only
 * failure, this wrapper does NOT upload the media again. The LFS object is already on the
 * Hub; we recompute its oid locally and retry only the final commit request in the exact
 * official wire format.
 */
class CommitRepairingHfUploader(
    private val context: Context,
    private val delegate: HfUploader = HuggingFaceUploader(context),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : HfUploader {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun uploadFile(
        token: String,
        shardId: String,
        repoPath: String,
        localUri: Uri,
        onProgress: (HfUploadProgress) -> Unit,
    ): String {
        return try {
            delegate.uploadFile(token, shardId, repoPath, localUri, onProgress)
        } catch (t: HfUploadException) {
            if (!isCommitSummaryParserError(t)) throw t

            withContext(Dispatchers.IO) {
                onProgress(
                    HfUploadProgress(
                        stage = HfUploadStage.FINALIZING,
                        bytesProcessed = 0L,
                        totalBytes = 0L,
                        message = "Dosya yüklendi; Hugging Face commit'i uyumlu biçimde yeniden deneniyor",
                    ),
                )
                repairCommit(
                    token = token,
                    shardId = shardId,
                    repoPath = repoPath,
                    localUri = localUri,
                    onProgress = onProgress,
                )
                onProgress(
                    HfUploadProgress(
                        stage = HfUploadStage.FINALIZING,
                        bytesProcessed = 1L,
                        totalBytes = 1L,
                        message = "Hugging Face commit'i tamamlandı",
                    ),
                )
                resolveHfUrl(shardId, repoPath)
            }
        }
    }

    private fun isCommitSummaryParserError(t: HfUploadException): Boolean {
        val message = t.message.orEmpty()
        return t.statusCode == 400 &&
            message.contains("Hugging Face commit", ignoreCase = true) &&
            message.contains("value.summary", ignoreCase = true)
    }

    private fun repairCommit(
        token: String,
        shardId: String,
        repoPath: String,
        localUri: Uri,
        onProgress: (HfUploadProgress) -> Unit,
    ) {
        val size = resolveSize(localUri)
        if (size < 0L) {
            throw HfUploadException("Dosya boyutu commit onarımı için belirlenemedi")
        }

        // Ask the Hub which commit operation it expects. This is cheap and prevents us
        // from guessing whether a file is regular or LFS.
        val sample = readSample(localUri)
        val escapedPath = jsonString(repoPath)
        val preuploadJson =
            "{\"files\":[{\"path\":$escapedPath,\"sample\":\"${Base64.encodeToString(sample, Base64.NO_WRAP)}\",\"size\":$size}]}"
        val preuploadRequest = Request.Builder()
            .url("$HF_ENDPOINT/api/datasets/$shardId/preupload/main")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .post(preuploadJson.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val uploadMode = httpClient.newCall(preuploadRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HfUploadException(
                    "Commit onarımı preupload başarısız (${response.code}): ${body.take(800)}",
                    statusCode = response.code,
                )
            }
            val root = json.parseToJsonElement(body).jsonObject
            root["files"]?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("uploadMode")?.jsonPrimitive?.content
                ?: throw HfUploadException("Commit onarımı preupload yanıtında uploadMode yok")
        }

        val operation = when (uploadMode) {
            "lfs" -> {
                onProgress(
                    HfUploadProgress(
                        stage = HfUploadStage.FINALIZING,
                        bytesProcessed = 0L,
                        totalBytes = size,
                        message = "Yüklenmiş dosya doğrulanıyor (tekrar upload edilmiyor)",
                    ),
                )
                val oid = sha256(localUri) { done ->
                    onProgress(
                        HfUploadProgress(
                            stage = HfUploadStage.FINALIZING,
                            bytesProcessed = done,
                            totalBytes = size,
                            message = "Yüklenmiş dosya doğrulanıyor (tekrar upload edilmiyor)",
                        ),
                    )
                }
                "{\"key\":\"lfsFile\",\"value\":{\"path\":$escapedPath,\"algo\":\"sha256\",\"oid\":\"$oid\",\"size\":$size}}"
            }

            "regular" -> {
                val bytes = context.contentResolver.openInputStream(localUri)?.use { it.readBytes() }
                    ?: throw HfUploadException("Commit onarımı için dosya okunamadı")
                val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
                "{\"key\":\"file\",\"value\":{\"content\":\"$base64\",\"path\":$escapedPath,\"encoding\":\"base64\"}}"
            }

            else -> throw HfUploadException("Commit onarımı bilinmeyen uploadMode aldı: $uploadMode")
        }

        // Match huggingface_hub's _prepare_commit_payload/create_commit wire format
        // byte-for-byte in structure: header first, then operation, newline after each.
        val header =
            "{\"key\":\"header\",\"value\":{\"summary\":\"upload from android-studio\",\"description\":\"\"}}"
        val ndjsonBytes = "$header\n$operation\n".toByteArray(Charsets.UTF_8)

        val commitRequest = Request.Builder()
            .url("$HF_ENDPOINT/api/datasets/$shardId/commit/main")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .header("Content-Type", "application/x-ndjson")
            .post(ndjsonBytes.toRequestBody(NDJSON_MEDIA_TYPE))
            .build()

        httpClient.newCall(commitRequest).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw HfUploadException(
                    "Hugging Face commit onarımı başarısız (${response.code})${if (body.isBlank()) "" else ": ${body.take(800)}"}",
                    statusCode = response.code,
                )
            }
        }
    }

    /**
     * SAF providers are inconsistent about OpenableColumns.SIZE, and the fast local mux
     * path intentionally uses a file:// Uri. Resolve the size through several cheap
     * metadata paths first; only as a last resort count the stream bytes. The last path
     * is slower, but it runs only after the media object was already uploaded and the
     * Hub commit endpoint needs repair, so it is preferable to failing a completed upload.
     */
    private fun resolveSize(uri: Uri): Long {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    val value = cursor.getLong(index)
                    if (value >= 0L) return value
                }
            }
        } catch (_: Throwable) {
            // Some providers reject metadata queries; continue with the next strategy.
        }

        if (uri.scheme.equals("file", ignoreCase = true)) {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val file = File(path)
                if (file.exists() && file.isFile) return file.length()
            }
        }

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.statSize >= 0L) return descriptor.statSize
            }
        } catch (_: Throwable) {
            // Continue.
        }

        try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                if (descriptor.length >= 0L) return descriptor.length
            }
        } catch (_: Throwable) {
            // Continue.
        }

        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return -1L
            input.use { source ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                }
                total
            }
        } catch (_: Throwable) {
            -1L
        }
    }

    private fun readSample(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw HfUploadException("Commit onarımı için dosya açılamadı")
        return input.use { source ->
            val buffer = ByteArray(512)
            var offset = 0
            while (offset < buffer.size) {
                val read = source.read(buffer, offset, buffer.size - offset)
                if (read < 0) break
                if (read == 0) continue
                offset += read
            }
            buffer.copyOf(offset)
        }
    }

    private fun sha256(uri: Uri, onProgress: (Long) -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw HfUploadException("Commit onarımı için dosya açılamadı")
        var done = 0L
        input.use { source ->
            val buffer = ByteArray(512 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                digest.update(buffer, 0, read)
                done += read
                onProgress(done)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** JSON-quote a string using kotlinx.serialization itself, avoiding hand escaping. */
    private fun jsonString(value: String): String =
        kotlinx.serialization.json.JsonPrimitive(value).toString()

    companion object {
        private const val HF_ENDPOINT = "https://huggingface.co"
        private const val USER_AGENT = "film2-android-studio/1.4.1"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val NDJSON_MEDIA_TYPE = "application/x-ndjson".toMediaType()
    }
}
