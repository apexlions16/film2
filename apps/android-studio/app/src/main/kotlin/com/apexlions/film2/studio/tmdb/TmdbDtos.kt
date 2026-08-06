package com.apexlions.film2.studio.tmdb

import kotlinx.serialization.Serializable

/**
 * Raw TMDB API response shapes. Property names are camelCase and mapped to TMDB's
 * snake_case JSON via JsonNamingStrategy.SnakeCase (configured on the Json instance in
 * TmdbClient) rather than individual @SerialName annotations.
 */

@Serializable
data class TmdbFindResult(
    val movieResults: List<TmdbIdOnly> = emptyList(),
    val tvResults: List<TmdbIdOnly> = emptyList(),
)

@Serializable
data class TmdbIdOnly(val id: Int)

@Serializable
data class TmdbGenre(val id: Int, val name: String)

@Serializable
data class TmdbCastMemberDto(
    val name: String,
    val character: String? = null,
    val profilePath: String? = null,
)

@Serializable
data class TmdbCrewMemberDto(
    val name: String,
    val job: String? = null,
    val profilePath: String? = null,
)

@Serializable
data class TmdbCredits(
    val cast: List<TmdbCastMemberDto> = emptyList(),
    val crew: List<TmdbCrewMemberDto> = emptyList(),
)

@Serializable
data class TmdbMovieDetail(
    val id: Int,
    val title: String,
    val originalTitle: String? = null,
    val overview: String? = null,
    val releaseDate: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val runtime: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val credits: TmdbCredits? = null,
)

@Serializable
data class TmdbSeasonSummary(val seasonNumber: Int)

@Serializable
data class TmdbTvDetail(
    val id: Int,
    val name: String,
    val originalName: String? = null,
    val overview: String? = null,
    val firstAirDate: String? = null,
    val genres: List<TmdbGenre> = emptyList(),
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val credits: TmdbCredits? = null,
    val seasons: List<TmdbSeasonSummary> = emptyList(),
)

@Serializable
data class TmdbEpisodeDto(
    val episodeNumber: Int,
    val name: String,
    val overview: String? = null,
    val airDate: String? = null,
    val stillPath: String? = null,
    val runtime: Int? = null,
)

@Serializable
data class TmdbSeasonDetail(
    val seasonNumber: Int,
    val name: String,
    val overview: String? = null,
    val posterPath: String? = null,
    val episodes: List<TmdbEpisodeDto> = emptyList(),
)
