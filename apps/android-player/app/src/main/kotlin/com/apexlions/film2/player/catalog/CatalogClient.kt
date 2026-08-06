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
 * Kotlin port of packages/catalog-client/src/index.js.
 *
 * Reads the public film2 GitHub repo's catalog straight from GitHub — no local
 * download/cache, always fetched fresh, matching the JS client's behavior. No auth
 * needed for public repo reads. GitHub's unauthenticated Contents API is rate limited
 * to 60 req/hour per IP; fine for personal-scale use, same caveat as the JS version.
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

    /** Lists all title ids under catalog/titles/ (excluding `_`-prefixed example files). */
    suspend fun listTitleIds(): List<String> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$repo/contents/catalog/titles?ref=$branch"
        val request = Request.Builder().url(url).build()
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

    /** Fetches one title's full JSON from the raw content CDN. */
    suspend fun getTitle(id: String): Title = withContext(Dispatchers.IO) {
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/titles/$id.json"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CatalogException("Title bulunamadi: $id (${response.code})")
            }
            val body = response.body?.string().orEmpty()
            json.decodeFromString(Title.serializer(), body)
        }
    }

    /**
     * Fetches the entire catalog (all titles). N+1 requests, same as the JS client —
     * fine for a small personal catalog (a handful of series/films).
     */
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
