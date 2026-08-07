package com.apexlions.film2.player.catalog

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Public film2 katalogunu GitHub raw CDN uzerinden okur.
 *
 * GitHub Contents API burada bilerek KULLANILMAZ: anonymous Contents API
 * 60 istek/saat/IP sinirina sahiptir. Tum katalog GitHub Actions'in urettiği
 * catalog/index.json snapshot'indan tek istekte gelir.
 */
class CatalogClient(
    private val repo: String = DEFAULT_REPO,
    private val branch: String = DEFAULT_BRANCH,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class CatalogSnapshot(
        val revision: String = "",
        val titles: List<Title> = emptyList(),
    )

    private suspend fun getSnapshot(): CatalogSnapshot = withContext(Dispatchers.IO) {
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/index.json?v=${System.currentTimeMillis()}"
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

    /** Tek bir title'i raw CDN'den okur; GitHub REST API kotasi tuketmez. */
    suspend fun getTitle(id: String): Title = withContext(Dispatchers.IO) {
        val url = "https://raw.githubusercontent.com/$repo/$branch/catalog/titles/$id.json?v=${System.currentTimeMillis()}"
        val request = Request.Builder().url(url).header("Cache-Control", "no-cache").build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw CatalogException("Title bulunamadi: $id (${response.code})")
            }
            json.decodeFromString(Title.serializer(), response.body?.string().orEmpty())
        }
    }

    /** Tum katalog tek raw snapshot isteginde gelir; N+1 ve Contents API yoktur. */
    suspend fun listTitles(): List<Title> = getSnapshot().titles

    suspend fun getRevision(): String? = try {
        getSnapshot().revision.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    companion object {
        const val DEFAULT_REPO = "apexlions16/film2"
        const val DEFAULT_BRANCH = "main"
    }
}

class CatalogException(message: String) : Exception(message)
