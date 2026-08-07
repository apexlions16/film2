// film2 katalog veri modeli — tüm uygulamalar (desktop-player, desktop-studio,
// android-player, android-studio) bu şekli referans alır.

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

/** MP4'ün yanında duran harici ses / altyazı dosyası. */
export interface ExternalMediaTrack {
  language: string;
  url: string;
  label?: string;
  mimeType?: string;
}

export interface PlayableAsset {
  /** Yeni varsayılan: doğrudan MP4/MKV/progressive medya URL'i. */
  videoUrl?: string;
  /** Eski katalog kayıtları için geriye dönük HLS desteği. Yeni yüklemeler bunu üretmez. */
  masterPlaylistUrl?: string;
  durationSeconds?: number;
  audioLanguages: string[];
  subtitleLanguages: string[];
  /** Video dosyasına gömülü olmayan ilave ses dosyaları. */
  externalAudioTracks?: ExternalMediaTrack[];
  /** WebVTT/SRT gibi sidecar altyazılar. */
  externalSubtitleTracks?: ExternalMediaTrack[];
}

export interface Episode {
  episodeNumber: number;
  title: string;
  overview: string;
  airDate?: string;
  stillUrl?: string;
  runtimeMinutes?: number;
  status: AssetStatus;
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
  id: string;
  type: TitleType;
  imdbId: string;
  tmdbId?: number;
  title: string;
  originalTitle?: string;
  overview: string;
  releaseYear?: number;
  genres: string[];
  runtimeMinutes?: number;
  posterUrl?: string;
  backdropUrl?: string;
  logoUrl?: string;
  cast: CastMember[];
  crew: CrewMember[];
  status: AssetStatus;
  manualEntry?: boolean;
  createdAt: string;
  updatedAt: string;
  shardId?: string;
  asset?: PlayableAsset;
  seasons?: Season[];
}

export interface ShardEntry {
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
