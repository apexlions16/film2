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

/** Aynı içeriğin farklı çözünürlükteki tek-MP4 varyantı. */
export interface VideoVariant {
  label: string;
  height: number;
  width?: number | null;
  url: string;
  source?: boolean;
}

export interface PlayableAsset {
  /** Yeni varsayılan: doğrudan tek MP4 / progressive medya URL'i. */
  videoUrl?: string;
  /** Eski katalog kayıtları için geriye dönük HLS desteği. Yeni yüklemeler bunu üretmez. */
  masterPlaylistUrl?: string;
  durationSeconds?: number;
  audioLanguages: string[];
  subtitleLanguages: string[];
  /** Video dosyasına gömülü olmayan ilave ses dosyaları (legacy). */
  externalAudioTracks?: ExternalMediaTrack[];
  /** WebVTT/SRT gibi sidecar altyazılar. */
  externalSubtitleTracks?: ExternalMediaTrack[];
  /** 1080p/720p/480p gibi her biri tek MP4 olan kalite seçenekleri. */
  videoVariants?: VideoVariant[];
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
  /** Admin tarafindan eklenen alternatif poster havuzu. */
  posterUrls?: string[];
  backdropUrl?: string;
  /** Hero / detay ekraninda kullanilan alternatif backdrop havuzu. */
  backdropUrls?: string[];
  logoUrl?: string;
  /** Detay ekraninda sessiz autoplay edilen kisa trailer / preview. */
  trailerUrl?: string;
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

/** Studio tarafindan yonetilen ana sayfa rafi. */
export interface HomeShelf {
  id: string;
  title: string;
  titleIds: string[];
  enabled: boolean;
  shuffle: boolean;
  maxItems: number;
}

/** Player ana sayfasinin editoryal konfigurasyonu. */
export interface HomeConfig {
  heroTitleIds: string[];
  shelves: HomeShelf[];
  updatedAt: string;
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
