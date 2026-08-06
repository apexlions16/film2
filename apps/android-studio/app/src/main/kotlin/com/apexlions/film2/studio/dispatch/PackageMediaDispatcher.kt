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
 * Everything needed to trigger .github/workflows/package-media.yml via workflow_dispatch.
 * Field names and shape are load-bearing — .github/scripts/package-media.mjs destructures
 * this exact object out of the `payload` input (JSON string).
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

/**
 * Triggers package-media.yml via workflow_dispatch (kicks off HLS packaging for one
 * movie/episode).
 *
 * NOTE: this used to POST to `/dispatches` (repository_dispatch). That call DOES
 * eventually work, but was confirmed live (on the desktop side, same repo) to be
 * delivered by GitHub with a 20-30 MINUTE delay — a real, reproducible delay, not a
 * one-off fluke. `workflow_dispatch` (POST to
 * `/actions/workflows/{file}/dispatches`, the same endpoint `gh workflow run` uses)
 * fired instantly and reliably every time it was tested. So this now uses that endpoint
 * instead, passing the payload as a single JSON-string `inputs.payload` (GitHub
 * workflow_dispatch inputs must be simple types, not nested JSON).
 */
class PackageMediaDispatcher(
    private val repo: String = "apexlions16/film2",
    private val defaultBranch: String = "main",
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
    private data class WorkflowDispatchInputs(val payload: String)

    @Serializable
    private data class WorkflowDispatchWire(
        val ref: String,
        val inputs: WorkflowDispatchInputs,
    )

    suspend fun dispatch(request: PackageMediaRequest, githubToken: String) = withContext(Dispatchers.IO) {
        val payloadWire = ClientPayloadWire(
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
        )
        val payloadJson = json.encodeToString(ClientPayloadWire.serializer(), payloadWire)
        val wire = WorkflowDispatchWire(ref = defaultBranch, inputs = WorkflowDispatchInputs(payload = payloadJson))
        val bodyJson = json.encodeToString(WorkflowDispatchWire.serializer(), wire)

        val httpRequest = Request.Builder()
            .url("https://api.github.com/repos/$repo/actions/workflows/package-media.yml/dispatches")
            .header("Authorization", "Bearer $githubToken")
            .header("Accept", "application/vnd.github+json")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw PackageMediaDispatchException(
                    "package-media dispatch basarisiz: ${response.code} ${response.body?.string().orEmpty()}",
                )
            }
        }
    }
}

class PackageMediaDispatchException(message: String) : Exception(message)
