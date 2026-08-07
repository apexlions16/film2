package com.apexlions.film2.player.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Reads the public Film2 catalog without GitHub REST API directory listings.
 * catalog/version.json stays the tiny hot-reload signal; when it changes, the full
 * catalog is fetched from catalog/index.json as ONE raw CDN request.
 */
class CatalogClient(
    private val repo: String = DEFAULT_REPO,
    private val branch: String = DEFAULT_BRANCH,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CatalogRevision(val revision: String)

    @Serializable
    private data class CatalogSnapshot(
        val revision: String = "",
        val titles: List<Title> = emptyList(),
    )

    private suspend fun getSnapshot(): CatalogSnapshot = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/index.json?v=$nonce"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CatalogException("Katalog snapshot alinamadi: ${response.code}")
            }
            json.decodeFromString(CatalogSnapshot.serializer(), response.body?.string().orEmpty())
        }
    }

    suspend fun listTitleIds(): List<String> = getSnapshot().titles.map { it.id }

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
            json.decodeFromString(Title.serializer(), response.body?.string().orEmpty())
        }
    }

    suspend fun getHomeConfig(): HomeConfig = withContext(Dispatchers.IO) {
        val nonce = System.currentTimeMillis()
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/home.json?v=$nonce"
        val request = Request.Builder()
            .url(url)
            .header("Cache-Control", "no-cache")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return@use HomeConfig.DEFAULT
            if (!response.isSuccessful) throw CatalogException("Ana sayfa ayarlari alinamadi: ${response.code}")
            runCatching { json.decodeFromString(HomeConfig.serializer(), response.body?.string().orEmpty()) }
                .getOrDefault(HomeConfig.DEFAULT)
        }
    }

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

    suspend fun listTitles(): List<Title> = getSnapshot().titles

    companion object {
        const val DEFAULT_REPO = "apexlions16/film2"
        const val DEFAULT_BRANCH = "main"
    }
}

class CatalogException(message: String) : Exception(message)
