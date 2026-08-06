package com.apexlions.film2.studio.hf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Uploads a local file (picked via Storage Access Framework) into a Hugging Face dataset
 * repo under incoming/{titleId}/... . Isolated behind this interface so the rest of the
 * app (UploadWorker, new-title flow) never talks to Hugging Face's HTTP shape directly.
 *
 * Returns the resolve URL of the uploaded file (same shape used by the JS hf-storage
 * package: https://huggingface.co/datasets/{shardId}/resolve/main/{repoPath}).
 */
interface HfUploader {
    suspend fun uploadFile(
        shardId: String,
        repoPath: String,
        localUri: Uri,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): String
}

fun resolveHfUrl(shardId: String, pathInRepo: String): String =
    "https://huggingface.co/datasets/$shardId/resolve/main/$pathInRepo"

/**
 * Real implementation of the Hugging Face Hub "commit" API, as best-effort reconstructed
 * from the written spec handed down for this app (no live HF_TOKEN was available while
 * building this — nobody could test it against the real API yet).
 *
 * TODO: verify every request/response shape below against the current
 * https://huggingface.co/docs/hub/api docs (and/or the @huggingface/hub JS SDK source,
 * which packages/hf-storage wraps) against a real HF_TOKEN before relying on this in
 * production. Things most likely to be wrong: exact field names in the preupload
 * response, whether LFS uploads go straight to `uploadUrl`(s) from preupload or require
 * a separate git-lfs batch-API round trip, and whether multi-part (chunked) LFS uploads
 * need an explicit "complete multipart upload" call with collected ETags.
 */
class HuggingFaceUploader(
    private val tokenProvider: suspend () -> String?,
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS) // uploads can be many GB; no fixed write timeout
        .readTimeout(120, TimeUnit.SECONDS)
        .build(),
) : HfUploader {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun uploadFile(
        shardId: String,
        repoPath: String,
        localUri: Uri,
        onProgress: (Long, Long) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        require(!token.isNullOrBlank()) { "Hugging Face yazma tokeni ayarlanmamis (Ayarlar ekranindan girin)" }

        val size = querySize(localUri)
        if (size <= SMALL_FILE_THRESHOLD_BYTES) {
            uploadSmall(token, shardId, repoPath, localUri, size, onProgress)
        } else {
            uploadLarge(token, shardId, repoPath, localUri, size, onProgress)
        }
        resolveHfUrl(shardId, repoPath)
    }

    // ---- Small files: inline base64 content in a single commit call ----

    private fun uploadSmall(
        token: String,
        shardId: String,
        repoPath: String,
        localUri: Uri,
        size: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val bytes = context.contentResolver.openInputStream(localUri)?.use { it.readBytes() }
            ?: error("Dosya okunamadi: $localUri")
        val base64 = Base64.getEncoder().encodeToString(bytes)
        onProgress(size, size)

        val headerOp = json.encodeToString(CommitHeaderOp.serializer(), CommitHeaderOp(value = CommitHeader("upload from android-studio")))
        val fileOp = json.encodeToString(
            CommitFileOp.serializer(),
            CommitFileOp(value = CommitFileValue(path = repoPath, content = base64, encoding = "base64")),
        )

        postCommit(token, shardId, listOf(headerOp, fileOp))
    }

    // ---- Large files: preupload -> upload bytes to returned URL(s) -> commit lfsFile op ----

    private fun uploadLarge(
        token: String,
        shardId: String,
        repoPath: String,
        localUri: Uri,
        size: Long,
        onProgress: (Long, Long) -> Unit,
    ) {
        val sha256 = sha256Hex(localUri)

        val preuploadBody = json.encodeToString(
            PreuploadRequest.serializer(),
            PreuploadRequest(files = listOf(PreuploadFileInput(path = repoPath, size = size, sha256 = sha256))),
        )
        val preuploadRequest = Request.Builder()
            .url("https://huggingface.co/api/datasets/$shardId/preupload/main")
            .header("Authorization", "Bearer $token")
            .post(preuploadBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val preuploadResult = httpClient.newCall(preuploadRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw HfUploadException("Hugging Face preupload basarisiz (${response.code}): ${response.body?.string().orEmpty()}")
            }
            json.decodeFromString(PreuploadResponse.serializer(), response.body?.string().orEmpty())
        }

        val fileResult = preuploadResult.files.firstOrNull { it.path == repoPath }
            ?: throw HfUploadException("Preupload yanitinda $repoPath icin sonuc bulunamadi")

        if (fileResult.uploadMode == "lfs" && !fileResult.shouldIgnore) {
            uploadLfsBytes(localUri, size, fileResult, onProgress)
        } else {
            onProgress(size, size)
        }

        val headerOp = json.encodeToString(CommitHeaderOp.serializer(), CommitHeaderOp(value = CommitHeader("upload from android-studio")))
        val lfsOp = json.encodeToString(
            CommitLfsFileOp.serializer(),
            CommitLfsFileOp(value = CommitLfsFileValue(path = repoPath, algo = "sha256", oid = sha256, size = size)),
        )
        postCommit(token, shardId, listOf(headerOp, lfsOp))
    }

    /** Uploads raw bytes to the URL(s) returned by preupload. Supports simple chunked PUT
     *  when multiple upload URLs are returned (very large files); each chunk gets an equal
     *  byte range except the last, which takes the remainder. */
    private fun uploadLfsBytes(
        localUri: Uri,
        size: Long,
        fileResult: PreuploadFileResult,
        onProgress: (Long, Long) -> Unit,
    ) {
        val singleUrl = fileResult.uploadUrl
        val multiUrls = fileResult.uploadUrls

        when {
            !multiUrls.isNullOrEmpty() -> {
                val chunkCount = multiUrls.size
                val chunkSize = (size + chunkCount - 1) / chunkCount
                var uploaded = 0L
                val localFile = materializeToTempFile(localUri)
                try {
                    multiUrls.forEachIndexed { index, uploadUrl ->
                        val start = index * chunkSize
                        val end = minOf(start + chunkSize, size)
                        if (start >= end) return@forEachIndexed
                        val chunkBytes = localFile.inputStream().use { input ->
                            input.skip(start)
                            readExactly(input, (end - start).toInt())
                        }
                        val request = Request.Builder()
                            .url(uploadUrl)
                            .put(chunkBytes.toRequestBody(OCTET_STREAM))
                            .build()
                        httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw HfUploadException("LFS parca yuklemesi basarisiz (${response.code}) [$index]")
                            }
                        }
                        uploaded += (end - start)
                        onProgress(uploaded, size)
                    }
                    // TODO: real S3-style multipart uploads usually need a "complete multipart
                    // upload" call with the collected ETags from each PUT response. That shape
                    // isn't specified here — verify against real HF responses once available.
                } finally {
                    localFile.delete()
                }
            }

            !singleUrl.isNullOrBlank() -> {
                val localFile = materializeToTempFile(localUri)
                try {
                    val request = Request.Builder()
                        .url(singleUrl)
                        .put(localFile.asRequestBody(OCTET_STREAM))
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            throw HfUploadException("LFS yuklemesi basarisiz (${response.code}): ${response.body?.string().orEmpty()}")
                        }
                    }
                    onProgress(size, size)
                } finally {
                    localFile.delete()
                }
            }

            else -> throw HfUploadException("Preupload yaniti LFS icin yukleme URL'i icermiyor")
        }
    }

    private fun postCommit(token: String, shardId: String, ndjsonOps: List<String>) {
        val multipartBuilder = MultipartBody.Builder().setType(MultipartBody.FORM)
        ndjsonOps.forEach { op -> multipartBuilder.addPart(op.toRequestBody(JSON_MEDIA_TYPE)) }

        val request = Request.Builder()
            .url("https://huggingface.co/api/datasets/$shardId/commit/main")
            .header("Authorization", "Bearer $token")
            .post(multipartBuilder.build())
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HfUploadException("Hugging Face commit basarisiz (${response.code}): ${response.body?.string().orEmpty()}")
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (sizeIndex >= 0 && cursor.moveToFirst()) {
                val value = cursor.getLong(sizeIndex)
                if (value > 0) return value
            }
        }
        // Fallback: stream the whole file once just to measure it.
        return context.contentResolver.openInputStream(uri)?.use { input ->
            var total = 0L
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
            }
            total
        } ?: 0L
    }

    private fun sha256Hex(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** SAF content:// Uris can't be re-opened as a java.io.File directly; materialize once
     *  into the app's cache dir so we can PUT it with a known length / re-read for chunking. */
    private fun materializeToTempFile(uri: Uri): File {
        val temp = File.createTempFile("hf_upload_", ".bin", context.cacheDir)
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { output -> input.copyTo(output) }
        }
        return temp
    }

    companion object {
        /** Files at or under this size are sent inline as base64 in the commit call.
         *  Above it, the LFS pre-upload flow is used. Matches the spec's "~10MB" guidance. */
        const val SMALL_FILE_THRESHOLD_BYTES = 10L * 1024 * 1024
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

class HfUploadException(message: String) : Exception(message)

/** minSdk-safe equivalent of InputStream.readNBytes (that method needs API 33+). */
private fun readExactly(input: java.io.InputStream, count: Int): ByteArray {
    val buffer = ByteArray(count)
    var offset = 0
    while (offset < count) {
        val read = input.read(buffer, offset, count - offset)
        if (read < 0) break
        offset += read
    }
    return if (offset == count) buffer else buffer.copyOf(offset)
}

// ---- Commit API operation payloads (NDJSON-style, one JSON object per multipart part) ----

@Serializable
private data class CommitHeader(val summary: String)

@Serializable
private data class CommitHeaderOp(val key: String = "header", val value: CommitHeader)

@Serializable
private data class CommitFileValue(val path: String, val content: String, val encoding: String)

@Serializable
private data class CommitFileOp(val key: String = "file", val value: CommitFileValue)

@Serializable
private data class CommitLfsFileValue(val path: String, val algo: String, val oid: String, val size: Long)

@Serializable
private data class CommitLfsFileOp(val key: String = "lfsFile", val value: CommitLfsFileValue)

// ---- Preupload API payloads ----

@Serializable
private data class PreuploadFileInput(val path: String, val size: Long, val sha256: String)

@Serializable
private data class PreuploadRequest(val files: List<PreuploadFileInput>)

@Serializable
private data class PreuploadFileResult(
    val path: String,
    val uploadMode: String = "regular", // "regular" | "lfs" — TODO verify field name
    val shouldIgnore: Boolean = false,
    val uploadUrl: String? = null, // TODO verify: single-URL LFS upload target
    val uploadUrls: List<String>? = null, // TODO verify: chunked multipart LFS upload targets
)

@Serializable
private data class PreuploadResponse(val files: List<PreuploadFileResult> = emptyList())
