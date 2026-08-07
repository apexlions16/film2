package com.apexlions.film2.player.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the public Film2 catalog. Full directory listing is only used on initial load or
 * when catalog/version.json changes; the tiny revision file can be polled frequently
 * without burning GitHub Contents API requests.
 */
class CatalogClient(
    private val repo: String = DEFAULT_REPO,
    private val branch: String = DEFAULT_BRANCH,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class ContentsEntry(
        val name: String,
        val type: String,
        @SerialName("path") val path: String? = null,
    )

    @Serializable
    private data class CatalogRevision(val revision: String)

    /** Lists all title ids under catalog/titles/ (excluding `_`-prefixed example files). */
    suspend fun listTitleIds(): List<String> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/contents/catalog/titles?ref=$branch"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CatalogException("Katalog listelenemedi: ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            val entries = json.decodeFromString<List<ContentsEntry>>(body)
            entries
                .filter { it.type == "file" && it.name.endsWith(".json") && !it.name.startsWith("_") }
                .map { it.name.removeSuffix(".json") }
        }
    }

    /** Fetches one title fresh; the nonce prevents raw.githubusercontent CDN staleness. */
    suspend fun getTitle(id: String): Title = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/titles/$id.json?v=$nonce"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CatalogException("Title bulunamadi: $id (${response.code})")
            }
            val body = response.body?.string().orEmpty()
            json.decodeFromString(Title.serializer(), body)
        }
    }

    /**
     * Tiny, cache-busted signal. Player can ask every few seconds; only a changed revision
     * triggers the more expensive catalog directory + title fetches.
     */
    suspend fun getRevision(): String? = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/version.json?v=$nonce"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            runCatching {
                json.decodeFromString(CatalogRevision.serializer(), response.body?.string().orEmpty()).revision
            }.getOrNull()
        }
    }

    suspend fun listTitles(): List<Title> = withContext(Dispatchers.IO) {
        val ids = listTitleIds()
        ids.map { id -> async { getTitle(id) } }.awaitAll()
    }

    companion object {
        const val DEFAULT_REPO = "apexlions16/film2"
        const val DEFAULT_BRANCH = "main"
    }
}

class CatalogException(message: String) : Exception(message)
