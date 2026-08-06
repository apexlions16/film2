package com.apexlions.film2.player.navigation

/** Route definitions for the player app's small nav graph (browse -> detail -> player). */
object Destinations {
    const val BROWSE = "browse"
    const val SEARCH = "search"

    const val TITLE_DETAIL_ROUTE = "title/{titleId}"
    fun titleDetail(titleId: String) = "title/$titleId"

    const val PLAYER_ROUTE = "player/{titleId}?season={season}&episode={episode}"
    fun player(titleId: String, season: Int? = null, episode: Int? = null): String {
        val s = season?.toString() ?: "-1"
        val e = episode?.toString() ?: "-1"
        return "player/$titleId?season=$s&episode=$e"
    }

    const val ARG_TITLE_ID = "titleId"
    const val ARG_SEASON = "season"
    const val ARG_EPISODE = "episode"
}
