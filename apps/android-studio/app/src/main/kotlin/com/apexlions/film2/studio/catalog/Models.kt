package com.apexlions.film2.studio.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kotlin port of packages/catalog-schema/src/types.ts.
 * Kept in lockstep by hand — there is no shared module between the JS desktop apps
 * and these Android apps, so any shape change on the JS side must be mirrored here
 * (and in the equivalent Models.kt under apps/android-player).
 */

@Serializable
enum class TitleType {
    @SerialName("movie") MOVIE,
    @SerialName("series") SERIES,
}

@Serializable
enum class AssetStatus {
    @SerialName("pending") PENDING,
    @SerialName("processing") PROCESSING,
    @SerialName("ready") READY,
    @SerialName("error") ERROR,
}

@Serializable
data class CastMember(
    val name: String,
    val character: String,
    val profileUrl: String? = null,
)

@Serializable
data class CrewMember(
    val name: String,
    val job: String,
    val profileUrl: String? = null,
)

@Serializable
data class PlayableAsset(
    /** HLS master playlist (.m3u8) absolute URL — Hugging Face resolve URL. */
    val masterPlaylistUrl: String,
    val durationSeconds: Double? = null,
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
)

@Serializable
data class Episode(
    val episodeNumber: Int,
    val title: String,
    val overview: String,
    val airDate: String? = null,
    val stillUrl: String? = null,
    val runtimeMinutes: Int? = null,
    val status: AssetStatus,
    /** Hugging Face dataset repo id hosting this episode's media. */
    val shardId: String? = null,
    val asset: PlayableAsset? = null,
)

@Serializable
data class Season(
    val seasonNumber: Int,
    val name: String,
    val overview: String? = null,
    val posterUrl: String? = null,
    val episodes: List<Episode> = emptyList(),
)

@Serializable
data class Title(
    /** Slug id, e.g. "kayip-sehir" — also the filename: catalog/titles/{id}.json */
    val id: String,
    val type: TitleType,
    val imdbId: String,
    val tmdbId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    val overview: String,
    val releaseYear: Int? = null,
    val genres: List<String> = emptyList(),
    /** Movie only. */
    val runtimeMinutes: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    val status: AssetStatus,
    /** True when TMDB had nothing and every field was entered by hand. */
    val manualEntry: Boolean? = null,
    val createdAt: String,
    val updatedAt: String,
    /** Movie media; series use episode.asset under seasons instead. */
    val shardId: String? = null,
    val asset: PlayableAsset? = null,
    val seasons: List<Season>? = null,
)

@Serializable
data class ShardEntry(
    /** "owner/repo-name" formatted Hugging Face dataset repo id. */
    val id: String,
    val repoType: String = "dataset",
    val active: Boolean,
    val usedBytesApprox: Long,
    val createdAt: String,
)

@Serializable
data class ShardRegistry(
    val namespace: String,
    val prefix: String,
    val sizeThresholdBytes: Long,
    val shards: List<ShardEntry>,
)
