// Main <-> preload <-> renderer arasinda paylasilan tipler.
import type {
  Title,
  Season,
  Episode,
  CastMember,
  CrewMember,
  AssetStatus,
  TitleType,
  PlayableAsset,
  ExternalMediaTrack,
  VideoVariant,
  ShardRegistry,
  HomeConfig,
  HomeShelf,
} from "@film2/catalog-schema/src/types";

export type {
  Title,
  Season,
  Episode,
  CastMember,
  CrewMember,
  AssetStatus,
  TitleType,
  PlayableAsset,
  ExternalMediaTrack,
  VideoVariant,
  ShardRegistry,
  HomeConfig,
  HomeShelf,
};

export interface StudioSettings {
  tmdbApiKey: string;
  githubToken: string;
}

export type SettingsField = keyof StudioSettings;

export interface SettingsPresence {
  tmdbApiKey: boolean;
  githubToken: boolean;
  hfAccountsCount: number;
}

export interface HfAccount {
  namespace: string;
  fullname?: string;
}

export interface AppError {
  message: string;
  detail?: string;
}

export type IpcResult<T> = { ok: true; data: T } | { ok: false; error: AppError };
export interface GithubFileResult<T = unknown> { content: T; sha: string; }

export type UploadMode = "combined" | "separate";

export interface UploadTarget {
  titleId: string;
  kind: "movie" | "episode";
  seasonNumber?: number;
  episodeNumber?: number;
}

export interface UploadFileSelection {
  mode: UploadMode;
  combinedFile?: string;
  videoFile?: string;
  audioFiles: Record<string, string>;
  subtitleFiles: Record<string, string>;
}

export interface UploadProgressEvent {
  uploadId: string;
  phase: "preparing" | "uploading" | "updating-registry" | "publishing" | "dispatching" | "done" | "error";
  fileName?: string;
  completedFiles: number;
  totalFiles: number;
  percent?: number;
  bytesProcessed?: number;
  totalBytes?: number;
  message?: string;
}

export interface DispatchPackageMediaPayload {
  titleId: string;
  kind: "movie" | "episode";
  seasonNumber?: number;
  episodeNumber?: number;
  shardId: string;
  mode: UploadMode;
  incomingPrefix: string;
  combinedFile?: string;
  videoFile?: string;
  audioFiles: Record<string, string>;
  subtitleFiles: Record<string, string>;
}

export interface UploadStartRequest { target: UploadTarget; selection: UploadFileSelection; }
export interface UploadStartResponse { uploadId: string; shardId: string; fastPath?: boolean; }
export interface PickFilesOptions { label: string; multi?: boolean; }

export interface TrailerUploadRequest { titleId: string; localPath: string; }
export interface QualityGenerateRequest extends UploadTarget {
  heights: number[];
}

export interface StudioApi {
  settings: {
    getPresence: () => Promise<IpcResult<SettingsPresence>>;
    getValues: () => Promise<IpcResult<StudioSettings>>;
    save: (values: Partial<StudioSettings>) => Promise<IpcResult<SettingsPresence>>;
  };
  hfAccounts: {
    list: () => Promise<IpcResult<HfAccount[]>>;
    add: (token: string) => Promise<IpcResult<HfAccount>>;
    remove: (namespace: string) => Promise<IpcResult<void>>;
  };
  tmdb: { fetchFromImdb: (imdbLinkOrId: string) => Promise<IpcResult<Title | null>>; };
  catalog: {
    listTitles: () => Promise<IpcResult<Title[]>>;
    getTitle: (id: string) => Promise<IpcResult<Title>>;
    saveTitle: (title: Title) => Promise<IpcResult<{ sha: string }>>;
    getHome: () => Promise<IpcResult<HomeConfig>>;
    saveHome: (config: HomeConfig) => Promise<IpcResult<void>>;
  };
  files: { pickFiles: (options: PickFilesOptions) => Promise<IpcResult<string[]>>; };
  upload: {
    start: (request: UploadStartRequest) => Promise<IpcResult<UploadStartResponse>>;
    onProgress: (callback: (event: UploadProgressEvent) => void) => () => void;
  };
  media: {
    uploadTrailer: (request: TrailerUploadRequest) => Promise<IpcResult<{ url: string }>>;
    generateQualities: (request: QualityGenerateRequest) => Promise<IpcResult<void>>;
  };
}
