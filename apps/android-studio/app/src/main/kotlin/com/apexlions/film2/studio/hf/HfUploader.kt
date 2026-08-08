package com.apexlions.film2.studio.hf

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** A visible phase in the Android upload pipeline. */
enum class HfUploadStage {
    PREPARING,
    CHECKING,
    UPLOADING,
    FINALIZING,
}

data class HfUploadProgress(
    val stage: HfUploadStage,
    val bytesProcessed: Long,
    val totalBytes: Long,
    val message: String,
)

/**
 * Uploads one Storage Access Framework Uri into a Hugging Face dataset repository.
 *
 * The implementation follows the same public Hub protocol used by the official
 * huggingface_hub client:
 *  1. preupload (path + first 512 byte sample + size)
 *  2. Git LFS batch API for large files (basic or multipart transfer)
 *  3. optional verify action
 *  4. newline-delimited JSON commit
 *
 * Files are copied and SHA-256 hashed in one pass into app external cache. That makes a
 * potentially long preparation phase visible instead of appearing frozen, and gives the
 * upload body a seekable source required by multipart/retry requests.
 */
interface HfUploader {
    suspend fun uploadFile(
        token: String,
        shardId: String,
        repoPath: String,
        localUri: Uri,
        onProgress: (HfUploadProgress) -> Unit = {},
    ): String
}

fun resolveHfUrl(shardId: String, pathInRepo: String): String =
    "https://huggingface.co/datasets/$shardId/resolve/main/$pathInRepo"

class HuggingFaceUploader(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build(),
) : HfUploader {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun uploadFile(
        token: String,
        shardId: String,
        repoPath: String,
        localUri: Uri,
        onProgress: (HfUploadProgress) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) {
            "Hugging Face write token'i ayarlanmamis. Ayarlar ekranindan bir hesap ekleyin."
        }

        val prepared = prepareFile(localUri, onProgress)
        try {
            val preupload = requestPreupload(
                token = token,
                shardId = shardId,
                repoPath = repoPath,
                prepared = prepared,
                onProgress = onProgress,
            )

            if (preupload.shouldIgnore) {
                throw HfUploadException(
                    "Hugging Face '$repoPath' dosyasini repo kurallari nedeniyle yok saydi. " +
                        "Hedef dosya adini veya repo .gitignore ayarini kontrol edin.",
                )
            }

            when (preupload.uploadMode) {
                "regular" -> uploadRegular(token, shardId, repoPath, prepared, onProgress)
                "lfs" -> uploadLfs(token, shardId, repoPath, prepared, onProgress)
                else -> throw HfUploadException(
                    "Hugging Face bilinmeyen yukleme modu dondurdu: ${preupload.uploadMode}",
                )
            }

            onProgress(
                HfUploadProgress(
                    stage = HfUploadStage.FINALIZING,
                    bytesProcessed = prepared.size,
                    totalBytes = prepared.size,
                    message = "Hugging Face commit'i tamamlandi",
                ),
            )
            resolveHfUrl(shardId, repoPath)
        } finally {
            prepared.file.delete()
        }
    }

    private fun prepareFile(
        uri: Uri,
        onProgress: (HfUploadProgress) -> Unit,
    ): PreparedFile {
        val expectedSize = querySize(uri)
        val cacheRoot = context.externalCacheDir ?: context.cacheDir
        if (expectedSize > 0L && cacheRoot.usableSpace < expectedSize + CACHE_SAFETY_BYTES) {
            throw HfUploadException(
                "Dosyayi yuklemeye hazirlamak icin gecici depolama yetersiz. " +
                    "Gerekli: ${formatBytes(expectedSize + CACHE_SAFETY_BYTES)}, " +
                    "bos: ${formatBytes(cacheRoot.usableSpace)}.",
            )
        }

        val temp = File.createTempFile("film2_hf_", ".upload", cacheRoot)
        val digest = MessageDigest.getInstance("SHA-256")
        val sample = ByteArray(PREUPLOAD_SAMPLE_BYTES)
        var sampleLength = 0
        var copied = 0L

        onProgress(
            HfUploadProgress(
                HfUploadStage.PREPARING,
                0,
                expectedSize,
                "Dosya hazirlaniyor ve dogrulaniyor",
            ),
        )

        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw HfUploadException("Secilen dosya okunamadi: $uri")
            input.use { source ->
                temp.outputStream().buffered(COPY_BUFFER_BYTES).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)

                        if (sampleLength < sample.size) {
                            val sampleBytes = minOf(read, sample.size - sampleLength)
                            buffer.copyInto(sample, sampleLength, 0, sampleBytes)
                            sampleLength += sampleBytes
                        }

                        copied += read
                        onProgress(
                            HfUploadProgress(
                                HfUploadStage.PREPARING,
                                copied,
                                if (expectedSize > 0L) expectedSize else copied,
                                "Dosya hazirlaniyor ve SHA-256 hesaplaniyor",
                            ),
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            temp.delete()
            if (t is HfUploadException) throw t
            throw HfUploadException("Dosya hazirlanamadi: ${t.message}", cause = t)
        }

        if (expectedSize > 0L && copied != expectedSize) {
            temp.delete()
            throw HfUploadException(
                "Dosya boyutu okuma sirasinda degisti (beklenen $expectedSize, okunan $copied bayt).",
            )
        }

        return PreparedFile(
            file = temp,
            size = copied,
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
            sample = sample.copyOf(sampleLength),
        )
    }

    private fun requestPreupload(
        token: String,
        shardId: String,
        repoPath: String,
        prepared: PreparedFile,
        onProgress: (HfUploadProgress) -> Unit,
    ): PreuploadFileResult {
        onProgress(
            HfUploadProgress(
                HfUploadStage.CHECKING,
                prepared.size,
                prepared.size,
                "Hugging Face yukleme turu kontrol ediliyor",
            ),
        )

        val payload = PreuploadRequest(
            files = listOf(
                PreuploadFileInput(
                    path = repoPath,
                    sample = Base64.encodeToString(prepared.sample, Base64.NO_WRAP),
                    size = prepared.size,
                ),
            ),
        )
        val request = Request.Builder()
            .url("$HF_ENDPOINT/api/datasets/$shardId/preupload/main")
            .hfAuthorization(token)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return execute(request, "Hugging Face preupload") { body ->
            val response = json.decodeFromString(PreuploadResponse.serializer(), body)
            response.files.firstOrNull { it.path == repoPath }
                ?: throw HfUploadException("Preupload yanitinda '$repoPath' icin sonuc bulunamadi")
        }
    }

    private fun uploadRegular(
        token: String,
        shardId: String,
        repoPath: String,
        prepared: PreparedFile,
        onProgress: (HfUploadProgress) -> Unit,
    ) {
        if (prepared.size > REGULAR_FILE_MEMORY_LIMIT_BYTES) {
            throw HfUploadException(
                "Hugging Face ${formatBytes(prepared.size)} dosyayi 'regular' olarak isaretledi; " +
                    "bu boyut Android belleginde guvenli bicimde commit edilemez.",
            )
        }

        onProgress(
            HfUploadProgress(
                HfUploadStage.UPLOADING,
                0,
                prepared.size,
                "Kucuk dosya commit icin hazirlaniyor",
            ),
        )
        val base64 = Base64.encodeToString(prepared.file.readBytes(), Base64.NO_WRAP)
        onProgress(
            HfUploadProgress(
                HfUploadStage.UPLOADING,
                prepared.size,
                prepared.size,
                "Kucuk dosya Hugging Face'e gonderiliyor",
            ),
        )
        postCommit(
            token = token,
            shardId = shardId,
            operation = buildJsonObject {
                put("key", "file")
                put("value", buildJsonObject {
                    put("path", repoPath)
                    put("content", base64)
                    put("encoding", "base64")
                })
            },
        )
    }

    private fun uploadLfs(
        token: String,
        shardId: String,
        repoPath: String,
        prepared: PreparedFile,
        onProgress: (HfUploadProgress) -> Unit,
    ) {
        val batch = requestLfsBatch(token, shardId, prepared)
        batch.error?.let { error ->
            throw HfUploadException(
                "Hugging Face LFS batch hatasi (${error.code}): ${error.message}",
                statusCode = error.code,
            )
        }

        val actions = batch.actions
        if (actions?.upload != null) {
            val uploadAction = actions.upload
            val chunkSize = uploadAction.header.orEmpty()["chunk_size"]?.jsonPrimitive?.content?.toLongOrNull()
            if (chunkSize != null) {
                uploadMultipart(prepared, uploadAction, chunkSize, onProgress)
            } else {
                uploadSinglePart(prepared, uploadAction, onProgress)
            }
        } else {
            onProgress(
                HfUploadProgress(
                    HfUploadStage.UPLOADING,
                    prepared.size,
                    prepared.size,
                    "Dosya Hugging Face deposunda zaten mevcut; aktarim atlandi",
                ),
            )
        }

        actions?.verify?.let { verifyAction ->
            verifyLfsUpload(token, verifyAction, prepared)
        }

        onProgress(
            HfUploadProgress(
                HfUploadStage.FINALIZING,
                prepared.size,
                prepared.size,
                "Hugging Face commit'i olusturuluyor",
            ),
        )
        postCommit(
            token = token,
            shardId = shardId,
            operation = buildJsonObject {
                put("key", "lfsFile")
                put("value", buildJsonObject {
                    put("path", repoPath)
                    put("algo", "sha256")
                    put("oid", prepared.sha256)
                    put("size", prepared.size)
                })
            },
        )
    }

    private fun requestLfsBatch(
        token: String,
        shardId: String,
        prepared: PreparedFile,
    ): LfsBatchObject {
        val payload = LfsBatchRequest(
            objects = listOf(LfsObjectInput(oid = prepared.sha256, size = prepared.size)),
            ref = LfsRef(name = "main"),
        )
        val request = Request.Builder()
            .url("$HF_ENDPOINT/datasets/$shardId.git/info/lfs/objects/batch")
            .hfAuthorization(token)
            .header("Accept", LFS_MEDIA_TYPE_STRING)
            .post(json.encodeToString(payload).toRequestBody(LFS_MEDIA_TYPE))
            .build()

        return execute(request, "Hugging Face LFS batch") { body ->
            val response = json.decodeFromString(LfsBatchResponse.serializer(), body)
            response.objects.firstOrNull { it.oid.equals(prepared.sha256, ignoreCase = true) }
                ?: throw HfUploadException("LFS batch yanitinda dosya icin yukleme talimati bulunamadi")
        }
    }

    private fun uploadSinglePart(
        prepared: PreparedFile,
        action: LfsAction,
        onProgress: (HfUploadProgress) -> Unit,
    ) {
        val body = ProgressFileRequestBody(
            file = prepared.file,
            offset = 0,
            byteCount = prepared.size,
            onProgress = { sent ->
                onProgress(
                    HfUploadProgress(
                        HfUploadStage.UPLOADING,
                        sent,
                        prepared.size,
                        "Dosya Hugging Face'e yukleniyor",
                    ),
                )
            },
        )
        val builder = Request.Builder().url(resolveActionUrl(action.href)).put(body)
        action.header.orEmpty().forEach { (name, value) ->
            if (!name.equals("chunk_size", ignoreCase = true) && !name.all(Char::isDigit)) {
                builder.header(name, value.jsonPrimitive.content)
            }
        }
        executeNoBody(builder.build(), "Hugging Face LFS yuklemesi")
    }

    private fun uploadMultipart(
        prepared: PreparedFile,
        action: LfsAction,
        chunkSize: Long,
        onProgress: (HfUploadProgress) -> Unit,
    ) {
        require(chunkSize > 0L) { "Gecersiz LFS parca boyutu: $chunkSize" }
        val partUrls = action.header.orEmpty().entries
            .mapNotNull { (key, value) -> key.toIntOrNull()?.let { it to value.jsonPrimitive.content } }
            .sortedBy { it.first }

        val expectedParts = ((prepared.size + chunkSize - 1L) / chunkSize).toInt()
        if (partUrls.size != expectedParts) {
            throw HfUploadException(
                "Hugging Face multipart yaniti gecersiz: $expectedParts parca bekleniyordu, ${partUrls.size} geldi.",
            )
        }

        val completedParts = mutableListOf<CompletedPart>()
        var uploadedBefore = 0L
        partUrls.forEachIndexed { index, (_, partUrl) ->
            val offset = index * chunkSize
            val length = minOf(chunkSize, prepared.size - offset)
            val body = ProgressFileRequestBody(
                file = prepared.file,
                offset = offset,
                byteCount = length,
                onProgress = { partSent ->
                    onProgress(
                        HfUploadProgress(
                            HfUploadStage.UPLOADING,
                            uploadedBefore + partSent,
                            prepared.size,
                            "Dosya yukleniyor: parca ${index + 1}/$expectedParts",
                        ),
                    )
                },
            )
            val request = Request.Builder().url(resolveActionUrl(partUrl)).put(body).build()
            val etag = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw responseException("LFS parca ${index + 1} yuklemesi", response.code, response.body?.string())
                }
                response.header("ETag")
                    ?: response.header("etag")
                    ?: throw HfUploadException("LFS parca ${index + 1} yanitinda ETag yok")
            }
            completedParts += CompletedPart(partNumber = index + 1, etag = etag)
            uploadedBefore += length
        }

        val completion = MultipartCompletion(oid = prepared.sha256, parts = completedParts)
        val request = Request.Builder()
            .url(resolveActionUrl(action.href))
            .header("Accept", LFS_MEDIA_TYPE_STRING)
            .post(json.encodeToString(completion).toRequestBody(LFS_MEDIA_TYPE))
            .build()
        executeNoBody(request, "Hugging Face multipart tamamlama")
    }

    private fun verifyLfsUpload(token: String, action: LfsAction, prepared: PreparedFile) {
        val payload = LfsVerifyRequest(oid = prepared.sha256, size = prepared.size)
        val builder = Request.Builder()
            .url(resolveActionUrl(action.href))
            .hfAuthorization(token)
            .post(json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE))
        action.header.orEmpty().forEach { (name, value) -> builder.header(name, value.jsonPrimitive.content) }
        executeNoBody(builder.build(), "Hugging Face LFS dogrulama")
    }

    private fun postCommit(token: String, shardId: String, operation: JsonObject) {
        val header = buildJsonObject {
            put("key", "header")
            put("value", buildJsonObject {
                put("summary", "upload from android-studio")
                put("description", "")
            })
        }
        val ndjson = buildString {
            append(json.encodeToString(JsonObject.serializer(), header))
            append('\n')
            append(json.encodeToString(JsonObject.serializer(), operation))
            append('\n')
        }
        val request = Request.Builder()
            .url("$HF_ENDPOINT/api/datasets/$shardId/commit/main")
            .hfAuthorization(token)
            .post(ndjson.toRequestBody(NDJSON_MEDIA_TYPE))
            .build()
        executeNoBody(request, "Hugging Face commit")
    }

    private fun querySize(uri: Uri): Long {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                return cursor.getLong(index).coerceAtLeast(0L)
            }
        }
        return -1L
    }

    private fun Request.Builder.hfAuthorization(token: String): Request.Builder =
        header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)

    private fun resolveActionUrl(raw: String): String = when {
        raw.startsWith("https://") || raw.startsWith("http://") -> raw
        raw.startsWith("/") -> "$HF_ENDPOINT$raw"
        else -> "$HF_ENDPOINT/$raw"
    }

    private fun executeNoBody(request: Request, label: String) {
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw responseException(label, response.code, response.body?.string())
            }
        }
    }

    private fun <T> execute(request: Request, label: String, transform: (String) -> T): T {
        return httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw responseException(label, response.code, body)
            try {
                transform(body)
            } catch (t: HfUploadException) {
                throw t
            } catch (t: Throwable) {
                throw HfUploadException("$label yaniti okunamadi: ${t.message}", cause = t)
            }
        }
    }

    private fun responseException(label: String, statusCode: Int, body: String?): HfUploadException {
        val compact = body.orEmpty().replace('\n', ' ').take(800)
        return HfUploadException(
            "$label basarisiz ($statusCode)${if (compact.isBlank()) "" else ": $compact"}",
            statusCode = statusCode,
        )
    }

    companion object {
        private const val HF_ENDPOINT = "https://huggingface.co"
        private const val USER_AGENT = "film2-android-studio/1.1"
        private const val PREUPLOAD_SAMPLE_BYTES = 512
        private const val COPY_BUFFER_BYTES = 256 * 1024
        private const val CACHE_SAFETY_BYTES = 64L * 1024 * 1024
        private const val REGULAR_FILE_MEMORY_LIMIT_BYTES = 20L * 1024 * 1024
        private const val LFS_MEDIA_TYPE_STRING = "application/vnd.git-lfs+json"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val NDJSON_MEDIA_TYPE = "application/x-ndjson; charset=utf-8".toMediaType()
        private val LFS_MEDIA_TYPE = LFS_MEDIA_TYPE_STRING.toMediaType()
    }
}

class HfUploadException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

private data class PreparedFile(
    val file: File,
    val size: Long,
    val sha256: String,
    val sample: ByteArray,
)

private class ProgressFileRequestBody(
    private val file: File,
    private val offset: Long,
    private val byteCount: Long,
    private val mediaType: MediaType = "application/octet-stream".toMediaType(),
    private val onProgress: (Long) -> Unit,
) : RequestBody() {
    override fun contentType(): MediaType = mediaType
    override fun contentLength(): Long = byteCount

    override fun writeTo(sink: BufferedSink) {
        FileInputStream(file).use { input ->
            skipFully(input, offset)
            var remaining = byteCount
            var sent = 0L
            val buffer = ByteArray(256 * 1024)
            while (remaining > 0L) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw EOFException("Dosya beklenenden erken bitti")
                if (read == 0) continue
                sink.write(buffer, 0, read)
                sent += read
                remaining -= read
                onProgress(sent)
            }
        }
    }
}

private fun skipFully(input: InputStream, byteCount: Long) {
    var remaining = byteCount
    while (remaining > 0L) {
        val skipped = input.skip(remaining)
        if (skipped > 0L) {
            remaining -= skipped
        } else {
            if (input.read() < 0) throw EOFException("Dosya icinde istenen konuma gidilemedi")
            remaining--
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return "%.1f %s".format(value, units[unit])
}

@Serializable
private data class PreuploadFileInput(val path: String, val sample: String, val size: Long)

@Serializable
private data class PreuploadRequest(val files: List<PreuploadFileInput>)

@Serializable
private data class PreuploadFileResult(
    val path: String,
    val uploadMode: String,
    val shouldIgnore: Boolean = false,
    val oid: String? = null,
)

@Serializable
private data class PreuploadResponse(val files: List<PreuploadFileResult> = emptyList())

@Serializable
private data class LfsObjectInput(val oid: String, val size: Long)

@Serializable
private data class LfsRef(val name: String)

@Serializable
private data class LfsBatchRequest(
    val operation: String = "upload",
    val transfers: List<String> = listOf("basic", "multipart"),
    val objects: List<LfsObjectInput>,
    val hash_algo: String = "sha256",
    val ref: LfsRef,
)

@Serializable
private data class LfsBatchResponse(
    val transfer: String? = null,
    val objects: List<LfsBatchObject> = emptyList(),
)

@Serializable
private data class LfsBatchObject(
    val oid: String,
    val size: Long,
    val actions: LfsActions? = null,
    val error: LfsError? = null,
)

@Serializable
private data class LfsActions(
    val upload: LfsAction? = null,
    val verify: LfsAction? = null,
)

@Serializable
private data class LfsAction(
    val href: String,
    val header: JsonObject? = null,
)

@Serializable
private data class LfsError(val code: Int, val message: String)

@Serializable
private data class LfsVerifyRequest(val oid: String, val size: Long)

@Serializable
private data class CompletedPart(val partNumber: Int, val etag: String)

@Serializable
private data class MultipartCompletion(val oid: String, val parts: List<CompletedPart>)
