@file:OptIn(ExperimentalSerializationApi::class)

package com.apexlions.film2.studio.dispatch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** "movie" | "episode" — matches package-media.mjs's `kind` field exactly. */
enum class MediaKind(val wireValue: String) {
    MOVIE("movie"),
    EPISODE("episode"),
}

/** "combined" | "separate" — matches package-media.mjs's `mode` field exactly. */
enum class UploadMode(val wireValue: String) {
    COMBINED("combined"),
    SEPARATE("separate"),
}

/**
 * Everything needed to trigger .github/workflows/package-media.yml via repository_dispatch.
 * Field names and shape are load-bearing — .github/scripts/package-media.mjs destructures
 * this exact object out of `github.event.client_payload`.
 */
data class PackageMediaRequest(
    val titleId: String,
    val kind: MediaKind,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val shardId: String,
    val mode: UploadMode,
    val incomingPrefix: String,
    val combinedFile: String? = null,
    val videoFile: String? = null,
    val audioFiles: Map<String, String> = emptyMap(),
    val subtitleFiles: Map<String, String> = emptyMap(),
)

/** Posts the repository_dispatch event that kicks off HLS packaging for one movie/episode. */
class PackageMediaDispatcher(
    private val repo: String = "apexlions16/film2",
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Serializable
    private data class ClientPayloadWire(
        val titleId: String,
        val kind: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val shardId: String,
        val mode: String,
        val incomingPrefix: String,
        val combinedFile: String? = null,
        val videoFile: String? = null,
        val audioFiles: Map<String, String> = emptyMap(),
        val subtitleFiles: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class DispatchWire(
        @SerialName("event_type") val eventType: String = "package-media",
        @SerialName("client_payload") val clientPayload: ClientPayloadWire,
    )

    suspend fun dispatch(request: PackageMediaRequest, githubToken: String) = withContext(Dispatchers.IO) {
        val wire = DispatchWire(
            clientPayload = ClientPayloadWire(
                titleId = request.titleId,
                kind = request.kind.wireValue,
                seasonNumber = request.seasonNumber,
                episodeNumber = request.episodeNumber,
                shardId = request.shardId,
                mode = request.mode.wireValue,
                incomingPrefix = request.incomingPrefix,
                combinedFile = request.combinedFile,
                videoFile = request.videoFile,
                audioFiles = request.audioFiles,
                subtitleFiles = request.subtitleFiles,
            ),
        )
        // GitHub's dispatches API expects "event_type"/"client_payload" (snake_case) at the
        // top level, matching the workflow's `on: repository_dispatch: types: [package-media]`.
        val bodyJson = json.encodeToString(DispatchWire.serializer(), wire)

        val request2 = Request.Builder()
            .url("https://api.github.com/repos/$repo/dispatches")
            .header("Authorization", "Bearer $githubToken")
            .header("Accept", "application/vnd.github+json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request2).execute().use { response ->
            if (!response.isSuccessful) {
                throw PackageMediaDispatchException(
                    "package-media dispatch basarisiz: ${response.code} ${response.body?.string().orEmpty()}",
                )
            }
        }
    }
}

class PackageMediaDispatchException(message: String) : Exception(message)
