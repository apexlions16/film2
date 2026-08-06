@file:OptIn(ExperimentalSerializationApi::class)

package com.apexlions.film2.studio.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Reads AND writes the film2 GitHub catalog via the Contents API.
 *
 * Reads mirror packages/catalog-client/src/index.js (same endpoints as android-player's
 * CatalogClient). Writes (used only by Studio) go through the authenticated Contents API:
 * GET a file first to learn its `sha` (needed to update vs. create), then PUT base64
 * content. A GitHub PAT with `repo` contents write scope is required for writes; reads
 * work unauthenticated too but we attach the token when present to raise the (very low,
 * 60/hr) unauthenticated rate limit.
 */
class GitHubContentsClient(
    private val tokenProvider: suspend () -> String?,
    private val repo: String = DEFAULT_REPO,
    private val branch: String = DEFAULT_BRANCH,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    @Serializable
    private data class ContentsEntry(
        val name: String,
        val type: String,
        val sha: String? = null,
    )

    @Serializable
    private data class ContentsFile(
        val sha: String? = null,
        val content: String? = null,
        val encoding: String? = null,
    )

    @Serializable
    private data class PutFileRequest(
        val message: String,
        val content: String,
        val branch: String,
        val sha: String? = null,
    )

    private suspend fun authHeader(builder: Request.Builder): Request.Builder {
        val token = tokenProvider()
        if (!token.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $token")
        }
        builder.header("Accept", "application/vnd.github+json")
        return builder
    }

    // ---- Reads (same shape as the JS catalog-client / android-player's CatalogClient) ----

    suspend fun listTitleIds(): List<String> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/contents/catalog/titles?ref=$branch"
        val request = authHeader(Request.Builder().url(url)).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException("Katalog listelenemedi: ${response.code}")
            val entries = json.decodeFromString<List<ContentsEntry>>(response.body?.string().orEmpty())
            entries
                .filter { it.type == "file" && it.name.endsWith(".json") && !it.name.startsWith("_") }
                .map { it.name.removeSuffix(".json") }
        }
    }

    suspend fun getTitle(id: String): Title? = withContext(Dispatchers.IO) {
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/titles/$id.json"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            json.decodeFromString(Title.serializer(), response.body?.string().orEmpty())
        }
    }

    suspend fun listTitles(): List<Title> = withContext(Dispatchers.IO) {
        val ids = listTitleIds()
        ids.map { id -> async { getTitle(id) } }.awaitAll().filterNotNull()
    }

    suspend fun getShardRegistry(): ShardRegistry = withContext(Dispatchers.IO) {
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/shards.json"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException("shards.json okunamadi: ${response.code}")
            json.decodeFromString(ShardRegistry.serializer(), response.body?.string().orEmpty())
        }
    }

    // ---- Writes (Studio only, requires a GitHub PAT with contents:write) ----

    private suspend fun getFileSha(path: String): String? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$branch"
        val request = authHeader(Request.Builder().url(url)).build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) throw GitHubApiException("Dosya bilgisi alinamadi ($path): ${response.code}")
            json.decodeFromString(ContentsFile.serializer(), response.body?.string().orEmpty()).sha
        }
    }

    private suspend fun putFile(path: String, contentBytes: ByteArray, commitMessage: String) =
        withContext(Dispatchers.IO) {
            val existingSha = getFileSha(path)
            val base64Content = Base64.getEncoder().encodeToString(contentBytes)
            val requestPayload = PutFileRequest(
                message = commitMessage,
                content = base64Content,
                branch = branch,
                sha = existingSha,
            )
            val bodyJson = json.encodeToString(PutFileRequest.serializer(), requestPayload)
            val url = "https://api.github.com/repos/$repo/contents/$path"
            val request = authHeader(
                Request.Builder()
                    .url(url)
                    .put(bodyJson.toRequestBody("application/json".toMediaType())),
            ).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw GitHubApiException(
                        "GitHub'a yazilamadi ($path): ${response.code} ${response.body?.string().orEmpty()}",
                    )
                }
            }
        }

    /** Commits catalog/titles/{id}.json (create or update — sha is resolved automatically). */
    suspend fun putTitle(title: Title) {
        val body = json.encodeToString(Title.serializer(), title)
        putFile(
            path = "catalog/titles/${title.id}.json",
            contentBytes = body.toByteArray(Charsets.UTF_8),
            commitMessage = "chore(catalog): ${title.id} eklendi/guncellendi (android-studio)",
        )
    }

    /** Commits the updated shard registry after an upload / new shard creation. */
    suspend fun putShardRegistry(registry: ShardRegistry) {
        val body = json.encodeToString(ShardRegistry.serializer(), registry)
        putFile(
            path = "catalog/shards.json",
            contentBytes = body.toByteArray(Charsets.UTF_8),
            commitMessage = "chore(catalog): shards.json guncellendi (android-studio)",
        )
    }

    companion object {
        const val DEFAULT_REPO = "apexlions16/film2"
        const val DEFAULT_BRANCH = "main"
    }
}

class GitHubApiException(message: String) : Exception(message)
