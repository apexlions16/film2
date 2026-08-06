package com.apexlions.film2.studio.tmdb

/** Kotlin port of packages/tmdb-client/src/imageUrls.js. */
object TmdbImageUrls {
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p"

    fun poster(path: String?, size: String = "w780"): String? = path?.let { "$IMAGE_BASE/$size$it" }
    fun backdrop(path: String?, size: String = "w1280"): String? = path?.let { "$IMAGE_BASE/$size$it" }
    fun profile(path: String?, size: String = "w300"): String? = path?.let { "$IMAGE_BASE/$size$it" }
    fun still(path: String?, size: String = "w500"): String? = path?.let { "$IMAGE_BASE/$size$it" }
}
