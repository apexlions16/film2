package com.apexlions.film2.player.catalog

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
data class PlayableAsset(
    /** Yeni varsayilan: dogrudan MP4/MKV/progressive medya URL'i. */
    val videoUrl: String? = null,
    /** Eski HLS katalog kayitlari icin geriye donuk destek. */
    val masterPlaylistUrl: String? = null,
    val durationSeconds: Double? = null,
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val externalAudioTracks: List<ExternalMediaTrack> = emptyList(),
    val externalSubtitleTracks: List<ExternalMediaTrack> = emptyList(),
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
    val backdropUrl: String? = null,
    val logoUrl: String? = null,
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
