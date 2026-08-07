package com.apexlions.film2.player.catalog

/** Result of a catalog fetch, distinguishing loading / failed / have data. */
sealed interface CatalogResult {
    data object Loading : CatalogResult
    data class Success(val titles: List<Title>) : CatalogResult
    data class Error(val message: String) : CatalogResult
}

class CatalogRepository(
    private val client: CatalogClient = CatalogClient(),
) {
    suspend fun fetchTitles(): CatalogResult = try {
        val titles = client.listTitles()
        CatalogResult.Success(titles)
    } catch (t: Throwable) {
        CatalogResult.Error(t.message ?: "Katalog yuklenemedi (bilinmeyen hata)")
    }

    suspend fun fetchTitle(id: String): Title? = try {
        client.getTitle(id)
    } catch (t: Throwable) {
        null
    }

    suspend fun fetchHomeConfig(): HomeConfig = try {
        client.getHomeConfig()
    } catch (_: Throwable) {
        HomeConfig.DEFAULT
    }

    suspend fun fetchRevision(): String? = try {
        client.getRevision()
    } catch (_: Throwable) {
        null
    }
}
