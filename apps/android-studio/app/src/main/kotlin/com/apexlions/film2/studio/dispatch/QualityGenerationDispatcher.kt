@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.apexlions.film2.studio.dispatch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** GitHub Actions'taki generate-qualities.yml workflow'una giden is tanimi. */
data class QualityGenerationRequest(
    val titleId: String,
    val kind: MediaKind,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val targets: List<Int> = listOf(720, 480),
)

class QualityGenerationDispatcher(
    private val repo: String = "apexlions16/film2",
    private val defaultBranch: String = "main",
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { encodeDefaults = true; explicitNulls = false }

    @Serializable
    private data class Payload(
        val titleId: String,
        val kind: String,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val targets: List<Int>,
    )

    @Serializable
    private data class Inputs(val payload: String)

    @Serializable
    private data class WorkflowDispatch(
        val ref: String,
        val inputs: Inputs,
    )

    suspend fun dispatch(request: QualityGenerationRequest, githubToken: String) = withContext(Dispatchers.IO) {
        require(request.targets.isNotEmpty()) { "En az bir hedef kalite secilmeli" }
        if (request.kind == MediaKind.EPISODE) {
            require(request.seasonNumber != null && request.episodeNumber != null) {
                "Dizi icin sezon ve bolum numarasi gerekli"
            }
        }

        val payload = Payload(
            titleId = request.titleId,
            kind = request.kind.wireValue,
            seasonNumber = request.seasonNumber,
            episodeNumber = request.episodeNumber,
            targets = request.targets.distinct().sortedDescending(),
        )
        val payloadJson = json.encodeToString(Payload.serializer(), payload)
        val body = WorkflowDispatch(
            ref = defaultBranch,
            inputs = Inputs(payload = payloadJson),
        )
        val bodyJson = json.encodeToString(WorkflowDispatch.serializer(), body)

        val httpRequest = Request.Builder()
            .url("https://api.github.com/repos/$repo/actions/workflows/generate-qualities.yml/dispatches")
            .header("Authorization", "Bearer $githubToken")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .post(bodyJson.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw QualityGenerationDispatchException(
                    "Kalite uretme isi baslatilamadi: ${response.code} ${response.body?.string().orEmpty()}",
                )
            }
        }
    }
}

class QualityGenerationDispatchException(message: String) : Exception(message)
