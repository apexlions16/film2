/// <reference types="vite/client" />

declare module '*.css'

interface Film2OfflineSubtitleRecord {
  language: string
  label?: string
  mimeType?: string
  url: string
  path: string
  localUrl?: string
}

interface Film2OfflineRecord {
  key: string
  titleId: string
  seasonNumber?: number
  episodeNumber?: number
  displayName: string
  videoUrl: string
  videoPath: string
  videoLocalUrl?: string
  qualityHeight?: number
  audioLanguages?: string[]
  subtitleRecords: Film2OfflineSubtitleRecord[]
  status: 'queued' | 'downloading' | 'complete' | 'failed'
  downloadedBytes: number
  totalBytes: number
  error?: string
  updatedAt: number
}

interface Window {
  film2?: {
    isElectron: true
    platform: string
    offline: {
      list: () => Promise<Film2OfflineRecord[]>
      enqueue: (request: unknown) => Promise<Film2OfflineRecord>
      remove: (key: string) => Promise<void>
      localPlayback: (key: string) => Promise<Film2OfflineRecord | null>
      onProgress: (callback: (record: Film2OfflineRecord) => void) => () => void
    }
  }
}
