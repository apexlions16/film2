@file:OptIn(ExperimentalSerializationApi::class)

package com.apexlions.film2.studio.tmdb

import com.apexlions.film2.studio.catalog.AssetStatus
import com.apexlions.film2.studio.catalog.CastMember
import com.apexlions.film2.studio.catalog.CrewMember
import com.apexlions.film2.studio.catalog.Episode
import com.apexlions.film2.studio.catalog.Season
import com.apexlions.film2.studio.catalog.Title
import com.apexlions.film2.studio.catalog.TitleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.time.Instant

/**
 * Kotlin port of packages/tmdb-client/src/index.js. Every network call, endpoint, and
 * field mapping mirrors the JS client 1:1 so the resulting catalog/titles/{id}.json shape
 * matches what desktop-studio would have produced.
 */
class TmdbClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    private suspend fun <T> tmdbGet(
        path: String,
        apiKey: String,
        deserialize: (String) -> T,
        extraParams: Map<String, String> = emptyMap(),
    ): T? = withContext(Dispatchers.IO) {
        val urlBuilder = "$API_BASE$path".toHttpUrl().newBuilder()
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("language", "tr-TR")
        extraParams.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
        val request = Request.Builder().url(urlBuilder.build()).build()
        httpClient.newCall(request).execute().use { response ->
            if (response.code == 404) return@use null
            if (!response.isSuccessful) {
                throw TmdbException("TMDB istegi basarisiz: ${response.code} ($path)")
            }
            deserialize(response.body?.string().orEmpty())
        }
    }

    /** Extracts a tt-id from a raw id, an imdb.com URL, or anything containing one. */
    fun imdbLinkToId(input: String?): String? {
        if (input.isNullOrBlank()) return null
        return IMDB_ID_REGEX.find(input.trim())?.value
    }

    /** Slugifies a title into the id used as the catalog/titles/{id}.json filename. */
    fun slugify(title: String?, imdbId: String): String {
        val base = (title ?: "")
            .lowercase()
            .let { Normalizer.normalize(it, Normalizer.Form.NFD) }
            .replace(COMBINING_DIACRITICS_REGEX, "")
            .replace(NON_ALNUM_REGEX, "-")
            .trim('-')
        return if (base.isNotEmpty()) "$base-${imdbId.substring(2, minOf(6, imdbId.length))}" else imdbId
    }

    private data class FindMatch(val type: TitleType, val tmdbId: Int)

    private suspend fun findByImdbId(imdbId: String, apiKey: String): FindMatch? {
        val result = tmdbGet(
            path = "/find/$imdbId",
            apiKey = apiKey,
            deserialize = { json.decodeFromString(TmdbFindResult.serializer(), it) },
            extraParams = mapOf("external_source" to "imdb_id"),
        ) ?: return null
        result.movieResults.firstOrNull()?.let { return FindMatch(TitleType.MOVIE, it.id) }
        result.tvResults.firstOrNull()?.let { return FindMatch(TitleType.SERIES, it.id) }
        return null
    }

    private val relevantCrewJobs = setOf("Director", "Writer", "Creator", "Executive Producer", "Producer")

    private fun mapCast(credits: TmdbCredits?): List<CastMember> =
        (credits?.cast ?: emptyList()).take(20).map {
            CastMember(name = it.name, character = it.character.orEmpty(), profileUrl = TmdbImageUrls.profile(it.profilePath))
        }

    private fun mapCrew(credits: TmdbCredits?): List<CrewMember> =
        (credits?.crew ?: emptyList())
            .filter { it.job in relevantCrewJobs }
            .map { CrewMember(name = it.name, job = it.job.orEmpty(), profileUrl = TmdbImageUrls.profile(it.profilePath)) }

    private suspend fun fetchMovie(tmdbId: Int, apiKey: String): TmdbMovieDetail? = tmdbGet(
        path = "/movie/$tmdbId",
        apiKey = apiKey,
        deserialize = { json.decodeFromString(TmdbMovieDetail.serializer(), it) },
        extraParams = mapOf("append_to_response" to "credits"),
    )

    private suspend fun fetchTvSeason(tmdbId: Int, seasonNumber: Int, apiKey: String): TmdbSeasonDetail? = tmdbGet(
        path = "/tv/$tmdbId/season/$seasonNumber",
        apiKey = apiKey,
        deserialize = { json.decodeFromString(TmdbSeasonDetail.serializer(), it) },
    )

    private suspend fun fetchTv(tmdbId: Int, apiKey: String): TmdbTvDetail? = tmdbGet(
        path = "/tv/$tmdbId",
        apiKey = apiKey,
        deserialize = { json.decodeFromString(TmdbTvDetail.serializer(), it) },
        extraParams = mapOf("append_to_response" to "credits"),
    )

    /**
     * Starting from an IMDb link/id, fetches TMDB data and returns a fully-formed [Title]
     * ready to preview/edit and save (status=pending, manualEntry=false). For series, every
     * season's episode list is fetched too (season 0 "specials" skipped). Returns null when
     * TMDB has nothing for this IMDb id — the caller should fall back to a blank manual-entry
     * form (manualEntry = true) in that case.
     */
    suspend fun fetchTitleFromImdbLink(imdbLinkOrId: String, apiKey: String): Title? {
        val imdbId = imdbLinkToId(imdbLinkOrId)
            ?: throw TmdbException("Gecerli bir IMDb linki/ID bulunamadi (tt... formatinda olmali)")

        val found = findByImdbId(imdbId, apiKey) ?: return null
        val now = Instant.now().toString()

        return when (found.type) {
            TitleType.MOVIE -> {
                val movie = fetchMovie(found.tmdbId, apiKey) ?: return null
                Title(
                    id = slugify(movie.title, imdbId),
                    type = TitleType.MOVIE,
                    imdbId = imdbId,
                    tmdbId = movie.id,
                    title = movie.title,
                    originalTitle = movie.originalTitle,
                    overview = movie.overview.orEmpty(),
                    releaseYear = movie.releaseDate?.take(4)?.toIntOrNull(),
                    genres = movie.genres.map { it.name },
                    runtimeMinutes = movie.runtime?.takeIf { it > 0 },
                    posterUrl = TmdbImageUrls.poster(movie.posterPath),
                    backdropUrl = TmdbImageUrls.backdrop(movie.backdropPath),
                    cast = mapCast(movie.credits),
                    crew = mapCrew(movie.credits),
                    status = AssetStatus.PENDING,
                    manualEntry = false,
                    createdAt = now,
                    updatedAt = now,
                )
            }

            TitleType.SERIES -> {
                val tv = fetchTv(found.tmdbId, apiKey) ?: return null
                val seasons = mutableListOf<Season>()
                for (summary in tv.seasons.filter { it.seasonNumber > 0 }) {
                    val season = fetchTvSeason(found.tmdbId, summary.seasonNumber, apiKey) ?: continue
                    seasons += Season(
                        seasonNumber = season.seasonNumber,
                        name = season.name,
                        overview = season.overview.orEmpty(),
                        posterUrl = TmdbImageUrls.poster(season.posterPath),
                        episodes = season.episodes.map { ep ->
                            Episode(
                                episodeNumber = ep.episodeNumber,
                                title = ep.name,
                                overview = ep.overview.orEmpty(),
                                airDate = ep.airDate,
                                stillUrl = TmdbImageUrls.still(ep.stillPath),
                                runtimeMinutes = ep.runtime?.takeIf { it > 0 },
                                status = AssetStatus.PENDING,
                            )
                        },
                    )
                }
                Title(
                    id = slugify(tv.name, imdbId),
                    type = TitleType.SERIES,
                    imdbId = imdbId,
                    tmdbId = tv.id,
                    title = tv.name,
                    originalTitle = tv.originalName,
                    overview = tv.overview.orEmpty(),
                    releaseYear = tv.firstAirDate?.take(4)?.toIntOrNull(),
                    genres = tv.genres.map { it.name },
                    posterUrl = TmdbImageUrls.poster(tv.posterPath),
                    backdropUrl = TmdbImageUrls.backdrop(tv.backdropPath),
                    cast = mapCast(tv.credits),
                    crew = mapCrew(tv.credits),
                    status = AssetStatus.PENDING,
                    manualEntry = false,
                    createdAt = now,
                    updatedAt = now,
                    seasons = seasons,
                )
            }
        }
    }

    companion object {
        private const val API_BASE = "https://api.themoviedb.org/3"
        private val IMDB_ID_REGEX = Regex("tt\\d{6,9}")
        private val COMBINING_DIACRITICS_REGEX = Regex("\\p{Mn}+")
        private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    }
}

class TmdbException(message: String) : Exception(message)
