package com.apexlions.film2.player.catalog

import kotlinx.serialization.Serializable

@Serializable
data class HomeShelf(
    val id: String,
    val title: String,
    val titleIds: List<String> = emptyList(),
    val enabled: Boolean = true,
    val shuffle: Boolean = false,
    val maxItems: Int = 30,
)

@Serializable
data class HomeConfig(
    val heroTitleIds: List<String> = emptyList(),
    val shelves: List<HomeShelf> = emptyList(),
    val updatedAt: String? = null,
) {
    companion object {
        val DEFAULT = HomeConfig(
            shelves = listOf(
                HomeShelf(id = "today-popular", title = "Bugün Popüler"),
                HomeShelf(id = "week-popular", title = "Bu Hafta Popüler"),
                HomeShelf(id = "editors-picks", title = "Editörün Seçtikleri"),
            ),
        )
    }
}
