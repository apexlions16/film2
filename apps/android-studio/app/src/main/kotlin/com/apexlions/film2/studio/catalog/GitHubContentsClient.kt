@file:OptIn(ExperimentalSerializationApi::class)

package com.apexlions.film2.studio.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.util.Base64

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
    private data class ContentsFile(val sha: String? = null, val content: String? = null, val encoding: String? = null)

    @Serializable
    private data class PutFileRequest(
        val message: String,
        val content: String,
        val branch: String,
        val sha: String? = null,
    )

    @Serializable
    private data class CatalogRevision(val revision: String)

    @Serializable
    private data class CatalogSnapshot(
        val revision: String = "",
        val titles: List<Title> = emptyList(),
    )

    private suspend fun normalizedToken(): String? {
        var value = tokenProvider()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        value = value.removeSurrounding("\"").trim()
        value = when {
            value.startsWith("Bearer ", ignoreCase = true) -> value.substringAfter(' ').trim()
            value.startsWith("token ", ignoreCase = true) -> value.substringAfter(' ').trim()
            else -> value
        }
        return value.takeIf { it.isNotBlank() }
    }

    private suspend fun authHeader(builder: Request.Builder, requireAuthentication: Boolean = false): Request.Builder {
        val token = normalizedToken()
        if (requireAuthentication && token == null) {
            throw GitHubApiException("GitHub PAT Studio'da kayitli degil. Ayarlar > GitHub PAT alanina token'i girip Kaydet'e basin.")
        }
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.header("Accept", "application/vnd.github+json")
        builder.header("X-GitHub-Api-Version", "2022-11-28")
        return builder
    }

    suspend fun verifyAuthentication() = withContext(Dispatchers.IO) {
        val request = authHeader(Request.Builder().url("https://api.github.com/user"), requireAuthentication = true).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val detail = response.body?.string().orEmpty()
                throw GitHubApiException("GitHub PAT dogrulanamadi (${response.code}). $detail")
            }
        }
    }

    private suspend fun getCatalogSnapshot(): CatalogSnapshot = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/index.json?v=$nonce"
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException("Katalog snapshot alinamadi: ${response.code}")
            json.decodeFromString(CatalogSnapshot.serializer(), response.body?.string().orEmpty())
        }
    }

    suspend fun listTitleIds(): List<String> = getCatalogSnapshot().titles.map { it.id }

    suspend fun getTitle(id: String): Title? = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/titles/$id.json?v=$nonce"
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            json.decodeFromString(Title.serializer(), response.body?.string().orEmpty())
        }
    }

    suspend fun listTitles(): List<Title> = getCatalogSnapshot().titles

    suspend fun getHomeConfig(): HomeConfig = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/home.json?v=$nonce"
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return@use HomeConfig.DEFAULT
            if (!response.isSuccessful) throw GitHubApiException("home.json okunamadi: ${response.code}")
            runCatching { json.decodeFromString(HomeConfig.serializer(), response.body?.string().orEmpty()) }
                .getOrDefault(HomeConfig.DEFAULT)
        }
    }

    suspend fun getShardRegistry(): ShardRegistry = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/shards.json?v=$nonce"
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw GitHubApiException("shards.json okunamadi: ${response.code}")
            json.decodeFromString(ShardRegistry.serializer(), response.body?.string().orEmpty())
        }
    }

    private suspend fun getFileSha(path: String): String? = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/contents/$path?ref=$branch"
        val request = authHeader(Request.Builder().url(url), requireAuthentication = true).build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) {
                throw GitHubApiException("Dosya bilgisi alinamadi ($path): ${response.code} ${response.body?.string().orEmpty()}")
            }
            json.decodeFromString(ContentsFile.serializer(), response.body?.string().orEmpty()).sha
        }
    }

    private suspend fun putFile(path: String, contentBytes: ByteArray, commitMessage: String) = withContext(Dispatchers.IO) {
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
            Request.Builder().url(url).put(bodyJson.toRequestBody("application/json".toMediaType())),
            requireAuthentication = true,
        ).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw GitHubApiException("GitHub'a yazilamadi ($path): ${response.code} ${response.body?.string().orEmpty()}")
            }
        }
    }

    private suspend fun touchCatalogVersion() {
        val body = json.encodeToString(
            CatalogRevision.serializer(),
            CatalogRevision(revision = Instant.now().toString()),
        ) + "\n"
        putFile(
            path = "catalog/version.json",
            contentBytes = body.toByteArray(Charsets.UTF_8),
            commitMessage = "chore(catalog): player revision",
        )
    }

    suspend fun putTitle(title: Title) {
        val body = json.encodeToString(Title.serializer(), title)
        putFile(
            path = "catalog/titles/${title.id}.json",
            contentBytes = body.toByteArray(Charsets.UTF_8),
            commitMessage = "chore(catalog): ${title.id} eklendi/guncellendi (android-studio)",
        )
        touchCatalogVersion()
    }

    suspend fun putHomeConfig(config: HomeConfig) {
        val normalized = config.copy(updatedAt = Instant.now().toString())
        val body = json.encodeToString(HomeConfig.serializer(), normalized) + "\n"
        putFile(
            path = "catalog/home.json",
            contentBytes = body.toByteArray(Charsets.UTF_8),
            commitMessage = "chore(catalog): ana sayfa raflari guncellendi",
        )
        touchCatalogVersion()
    }

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
