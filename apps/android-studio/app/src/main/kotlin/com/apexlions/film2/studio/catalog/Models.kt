package com.apexlions.film2.studio.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
data class ExternalMediaTrack(
    val language: String,
    val url: String,
    val label: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class VideoVariant(
    val label: String,
    val height: Int,
    val width: Int? = null,
    val url: String,
    val source: Boolean = false,
)

@Serializable
data class PlayableAsset(
    val videoUrl: String? = null,
    val masterPlaylistUrl: String? = null,
    val durationSeconds: Double? = null,
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val externalAudioTracks: List<ExternalMediaTrack> = emptyList(),
    val externalSubtitleTracks: List<ExternalMediaTrack> = emptyList(),
    val videoVariants: List<VideoVariant> = emptyList(),
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
    val id: String,
    val type: TitleType,
    val imdbId: String,
    val tmdbId: Int? = null,
    val title: String,
    val originalTitle: String? = null,
    val overview: String,
    val releaseYear: Int? = null,
    val genres: List<String> = emptyList(),
    val runtimeMinutes: Int? = null,
    val posterUrl: String? = null,
    val posterUrls: List<String> = emptyList(),
    val backdropUrl: String? = null,
    val backdropUrls: List<String> = emptyList(),
    val logoUrl: String? = null,
    val trailerUrl: String? = null,
    val cast: List<CastMember> = emptyList(),
    val crew: List<CrewMember> = emptyList(),
    val status: AssetStatus,
    val manualEntry: Boolean? = null,
    val createdAt: String,
    val updatedAt: String,
    val shardId: String? = null,
    val asset: PlayableAsset? = null,
    val seasons: List<Season>? = null,
)

@Serializable
data class ShardEntry(
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
