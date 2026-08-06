// Main <-> preload <-> renderer arasinda paylasilan tipler. Sadece tip-only import'lar
// icerir (runtime kodu yok) ki hem main hem renderer tsconfig'i sorunsuz derlesin.
import type {
  Title,
  Season,
  Episode,
  CastMember,
  CrewMember,
  AssetStatus,
  TitleType,
  PlayableAsset,
  ShardRegistry,
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
  ShardRegistry,
};

/** Studio ayarlar ekraninda toplanan uc token. Sadece electron-store'da (userData) saklanir. */
export interface StudioSettings {
  tmdbApiKey: string;
  hfToken: string;
  githubToken: string;
}

export type SettingsField = keyof StudioSettings;

/** Renderer'in tokenlarin DOLU olup olmadigini bilmesi yeterli — degerlerin kendisini
 * gostermek zorunda degiliz (input'lar odaklaninca mevcut degeri main'den ayrica cekeriz). */
export interface SettingsPresence {
  tmdbApiKey: boolean;
  hfToken: boolean;
  githubToken: boolean;
}

export interface AppError {
  message: string;
  detail?: string;
}

/** Butun IPC cagrilari bu zarfla doner — basari/hata renderer'da hep ayni sekilde ele alinir. */
export type IpcResult<T> = { ok: true; data: T } | { ok: false; error: AppError };

export interface GithubFileResult<T = unknown> {
  content: T;
  sha: string;
}

export type UploadMode = "combined" | "separate";

export interface UploadTarget {
  titleId: string;
  kind: "movie" | "episode";
  seasonNumber?: number;
  episodeNumber?: number;
}

/** Renderer'da secilen dosyalarin YEREL diski yollari (dialog.showOpenDialog sonucu). */
export interface UploadFileSelection {
  mode: UploadMode;
  combinedFile?: string;
  videoFile?: string;
  audioFiles: Record<string, string>;
  subtitleFiles: Record<string, string>;
}

export interface UploadProgressEvent {
  uploadId: string;
  phase: "uploading" | "updating-registry" | "dispatching" | "done" | "error";
  fileName?: string;
  completedFiles: number;
  totalFiles: number;
  message?: string;
}

/** package-media.mjs'in bekledigi client_payload sekli — .github/scripts/package-media.mjs ile birebir eslesmeli. */
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

export interface UploadStartRequest {
  target: UploadTarget;
  selection: UploadFileSelection;
}

export interface UploadStartResponse {
  uploadId: string;
  shardId: string;
}

export interface PickFilesOptions {
  label: string;
  multi?: boolean;
}

/** window.api yuzeyi — preload'un contextBridge ile expose ettigi tam sozlesme. */
export interface StudioApi {
  settings: {
    getPresence: () => Promise<IpcResult<SettingsPresence>>;
    getValues: () => Promise<IpcResult<StudioSettings>>;
    save: (values: Partial<StudioSettings>) => Promise<IpcResult<SettingsPresence>>;
  };
  tmdb: {
    fetchFromImdb: (imdbLinkOrId: string) => Promise<IpcResult<Title | null>>;
  };
  catalog: {
    listTitles: () => Promise<IpcResult<Title[]>>;
    getTitle: (id: string) => Promise<IpcResult<Title>>;
    saveTitle: (title: Title) => Promise<IpcResult<{ sha: string }>>;
  };
  files: {
    pickFiles: (options: PickFilesOptions) => Promise<IpcResult<string[]>>;
  };
  upload: {
    start: (request: UploadStartRequest) => Promise<IpcResult<UploadStartResponse>>;
    onProgress: (callback: (event: UploadProgressEvent) => void) => () => void;
  };
}
