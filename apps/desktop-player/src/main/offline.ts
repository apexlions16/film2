import { app, type BrowserWindow } from 'electron'
import { createWriteStream, existsSync } from 'node:fs'
import { mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises'
import { basename, dirname, join } from 'node:path'
import { pipeline } from 'node:stream/promises'
import { Readable, Transform } from 'node:stream'
import { pathToFileURL } from 'node:url'

export type OfflineStatus = 'queued' | 'downloading' | 'complete' | 'failed'

export interface OfflineSubtitleRequest {
  language: string
  label?: string
  mimeType?: string
  url: string
}

export interface OfflineEnqueueRequest {
  key: string
  titleId: string
  seasonNumber?: number
  episodeNumber?: number
  displayName: string
  videoUrl: string
  qualityHeight?: number
  audioLanguages?: string[]
  subtitles?: OfflineSubtitleRequest[]
}

export interface OfflineSubtitleRecord extends OfflineSubtitleRequest {
  path: string
  localUrl?: string
}

export interface OfflineRecord extends OfflineEnqueueRequest {
  status: OfflineStatus
  videoPath: string
  videoLocalUrl?: string
  subtitleRecords: OfflineSubtitleRecord[]
  downloadedBytes: number
  totalBytes: number
  error?: string
  updatedAt: number
}

interface PersistedState {
  records: Record<string, OfflineRecord>
}

const queue: string[] = []
const running = new Set<string>()
const MAX_PARALLEL = 2
let mainWindow: BrowserWindow | null = null
let state: PersistedState = { records: {} }
let loaded = false

function statePath(): string {
  return join(app.getPath('userData'), 'offline-downloads-v2.json')
}

function downloadsRoot(): string {
  return join(app.getPath('videos'), 'Film2 Downloads')
}

function safe(value: string): string {
  return value
    .normalize('NFKD')
    .replace(/[^a-zA-Z0-9._-]+/g, '_')
    .replace(/^_+|_+$/g, '')
    .slice(0, 96) || 'media'
}

async function ensureLoaded(): Promise<void> {
  if (loaded) return
  loaded = true
  try {
    state = JSON.parse(await readFile(statePath(), 'utf8')) as PersistedState
  } catch {
    state = { records: {} }
  }
}

async function persist(): Promise<void> {
  await mkdir(dirname(statePath()), { recursive: true })
  await writeFile(statePath(), JSON.stringify(state, null, 2), 'utf8')
}

function emit(record: OfflineRecord): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('offline:progress', record)
  }
}

function relativeFolder(request: OfflineEnqueueRequest): string {
  const part = request.seasonNumber != null && request.episodeNumber != null
    ? `S${request.seasonNumber}E${request.episodeNumber}`
    : 'Movie'
  return join(safe(request.titleId), part)
}

async function fileSize(path: string): Promise<number> {
  try {
    return (await stat(path)).size
  } catch {
    return 0
  }
}

async function streamDownload(
  url: string,
  finalPath: string,
  onProgress: (downloaded: number, total: number) => void
): Promise<void> {
  await mkdir(dirname(finalPath), { recursive: true })
  const partialPath = `${finalPath}.part`
  let existing = await fileSize(partialPath)
  const headers: Record<string, string> = { 'User-Agent': 'film2-desktop-player/1.0' }
  if (existing > 0) headers.Range = `bytes=${existing}-`

  let response = await fetch(url, { headers, redirect: 'follow' })
  if (!response.ok && response.status !== 206) {
    throw new Error(`Indirme HTTP ${response.status}`)
  }

  let append = existing > 0 && response.status === 206
  if (!append && existing > 0) {
    await rm(partialPath, { force: true })
    existing = 0
    response = await fetch(url, {
      headers: { 'User-Agent': 'film2-desktop-player/1.0' },
      redirect: 'follow'
    })
    if (!response.ok) throw new Error(`Indirme HTTP ${response.status}`)
  }

  const body = response.body
  if (!body) throw new Error('Indirme govdesi bos')
  const contentLength = Number(response.headers.get('content-length') || 0)
  const total = contentLength > 0 ? existing + contentLength : 0
  let downloaded = existing

  const counter = new Transform({
    transform(chunk, _encoding, callback) {
      downloaded += chunk.length
      onProgress(downloaded, total)
      callback(null, chunk)
    }
  })

  await pipeline(
    Readable.fromWeb(body as never),
    counter,
    createWriteStream(partialPath, { flags: append ? 'a' : 'w' })
  )

  await rm(finalPath, { force: true })
  const { rename } = await import('node:fs/promises')
  await rename(partialPath, finalPath)
}

async function runRecord(key: string): Promise<void> {
  const record = state.records[key]
  if (!record) return
  record.status = 'downloading'
  record.error = undefined
  record.updatedAt = Date.now()
  await persist()
  emit(record)

  try {
    const videoPath = record.videoPath
    await streamDownload(record.videoUrl, videoPath, async (downloaded, total) => {
      record.downloadedBytes = downloaded
      record.totalBytes = total
      record.updatedAt = Date.now()
      emit({ ...record })
    })

    for (const subtitle of record.subtitleRecords) {
      await streamDownload(subtitle.url, subtitle.path, () => {})
      subtitle.localUrl = pathToFileURL(subtitle.path).toString()
    }

    record.status = 'complete'
    record.videoLocalUrl = pathToFileURL(videoPath).toString()
    record.downloadedBytes = await fileSize(videoPath)
    record.totalBytes = record.downloadedBytes
    record.updatedAt = Date.now()
    await persist()
    emit({ ...record })
  } catch (error) {
    record.status = 'failed'
    record.error = error instanceof Error ? error.message : String(error)
    record.updatedAt = Date.now()
    await persist()
    emit({ ...record })
  }
}

function pump(): void {
  while (running.size < MAX_PARALLEL && queue.length > 0) {
    const key = queue.shift()!
    if (running.has(key) || !state.records[key]) continue
    running.add(key)
    void runRecord(key).finally(() => {
      running.delete(key)
      pump()
    })
  }
}

function schedule(key: string): void {
  if (!queue.includes(key) && !running.has(key)) queue.push(key)
  pump()
}

export async function initializeOfflineDownloads(win: BrowserWindow): Promise<void> {
  mainWindow = win
  await ensureLoaded()
  for (const record of Object.values(state.records)) {
    if (record.status === 'queued' || record.status === 'downloading') {
      record.status = 'queued'
      schedule(record.key)
    }
  }
}

export async function listOfflineDownloads(): Promise<OfflineRecord[]> {
  await ensureLoaded()
  return Object.values(state.records).sort((a, b) => b.updatedAt - a.updatedAt)
}

export async function enqueueOfflineDownload(request: OfflineEnqueueRequest): Promise<OfflineRecord> {
  await ensureLoaded()
  const existingRecord = state.records[request.key]
  if (existingRecord && existingRecord.status !== 'failed') return existingRecord

  const folder = join(downloadsRoot(), relativeFolder(request))
  const videoPath = join(folder, 'video.mp4')
  const subtitles = (request.subtitles ?? []).map((subtitle, index) => {
    const urlName = basename(new URL(subtitle.url).pathname)
    const ext = urlName.includes('.') ? `.${urlName.split('.').pop()}` : '.vtt'
    return {
      ...subtitle,
      path: join(folder, `subtitle_${safe(subtitle.language)}_${index + 1}${ext}`)
    }
  })

  const record: OfflineRecord = {
    ...request,
    status: 'queued',
    videoPath,
    subtitleRecords: subtitles,
    downloadedBytes: 0,
    totalBytes: 0,
    updatedAt: Date.now()
  }
  state.records[request.key] = record
  await persist()
  emit(record)
  schedule(request.key)
  return record
}

export async function removeOfflineDownload(key: string): Promise<void> {
  await ensureLoaded()
  queue.splice(0, queue.length, ...queue.filter((item) => item !== key))
  const record = state.records[key]
  if (!record) return
  delete state.records[key]
  await persist()
  await rm(dirname(record.videoPath), { recursive: true, force: true })
  emit({ ...record, status: 'failed', error: 'removed', updatedAt: Date.now() })
}

export async function localPlaybackFor(key: string): Promise<OfflineRecord | null> {
  await ensureLoaded()
  const record = state.records[key]
  if (!record || record.status !== 'complete' || !existsSync(record.videoPath)) return null
  return {
    ...record,
    videoLocalUrl: pathToFileURL(record.videoPath).toString(),
    subtitleRecords: record.subtitleRecords.map((subtitle) => ({
      ...subtitle,
      localUrl: existsSync(subtitle.path) ? pathToFileURL(subtitle.path).toString() : undefined
    }))
  }
}
