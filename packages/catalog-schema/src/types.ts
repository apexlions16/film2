// film2 katalog veri modeli — tüm uygulamalar (desktop-player, desktop-studio,
// android-player, android-studio, GitHub Actions pipeline) bu şekli referans alır.
// Android tarafında bu tipler Kotlin data class olarak paralel tutulur
// (apps/android-player/app/src/main/kotlin/.../catalog/Models.kt).

export type TitleType = "movie" | "series";
export type AssetStatus = "pending" | "processing" | "ready" | "error";

export interface CastMember {
  name: string;
  character: string;
  profileUrl?: string;
}

export interface CrewMember {
  name: string;
  job: string;
  profileUrl?: string;
}

export interface PlayableAsset {
  /** HLS master playlist (.m3u8) mutlak URL — Hugging Face resolve URL */
  masterPlaylistUrl: string;
  durationSeconds?: number;
  audioLanguages: string[];
  subtitleLanguages: string[];
}

export interface Episode {
  episodeNumber: number;
  title: string;
  overview: string;
  airDate?: string;
  stillUrl?: string;
  runtimeMinutes?: number;
  status: AssetStatus;
  /** Bu bölümün medyasını barındıran Hugging Face dataset repo id'si */
  shardId?: string;
  asset?: PlayableAsset;
}

export interface Season {
  seasonNumber: number;
  name: string;
  overview?: string;
  posterUrl?: string;
  episodes: Episode[];
}

export interface Title {
  /** Slug kimlik, örn. "kayip-sehir" — dosya adı da bu olur: catalog/titles/{id}.json */
  id: string;
  type: TitleType;
  imdbId: string;
  tmdbId?: number;
  title: string;
  originalTitle?: string;
  overview: string;
  releaseYear?: number;
  genres: string[];
  /** Sadece film için */
  runtimeMinutes?: number;
  posterUrl?: string;
  backdropUrl?: string;
  logoUrl?: string;
  cast: CastMember[];
  crew: CrewMember[];
  status: AssetStatus;
  /** Manuel giriş: TMDB'de bulunamadıysa true, tüm alanlar elle dolduruldu */
  manualEntry?: boolean;
  createdAt: string;
  updatedAt: string;
  /** Film için medya; dizi için sezonlar altındaki episode.asset kullanılır */
  shardId?: string;
  asset?: PlayableAsset;
  seasons?: Season[];
}

export interface ShardEntry {
  /** "owner/repo-name" formatında Hugging Face dataset repo id'si */
  id: string;
  repoType: "dataset";
  active: boolean;
  usedBytesApprox: number;
  createdAt: string;
}

export interface ShardRegistry {
  namespace: string;
  prefix: string;
  sizeThresholdBytes: number;
  shards: ShardEntry[];
}
