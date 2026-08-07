import type { Title } from './types'

export type AspectMode = 'fit' | 'cover' | 'fill' | '16:9' | '4:3' | '21:9'
export type SubtitleEdge = 'none' | 'outline' | 'shadow'
export type SubtitleBackground = 'none' | 'soft' | 'dark'

export interface SubtitleStylePreference {
  fontScale: number
  bottomPercent: number
  color: string
  background: SubtitleBackground
  edge: SubtitleEdge
}

export interface PlaybackRecord {
  key: string
  titleId: string
  seasonNumber?: number
  episodeNumber?: number
  positionSeconds: number
  durationSeconds: number
  audioLanguage?: string
  subtitleLanguage?: string
  subtitlesDisabled: boolean
  qualityHeight?: number
  aspectMode: AspectMode
  updatedAt: number
}

export interface CustomList {
  id: string
  name: string
  titleIds: string[]
}

export interface UserLibraryState {
  playback: Record<string, PlaybackRecord>
  myListTitleIds: string[]
  customLists: CustomList[]
  subtitleStyle: SubtitleStylePreference
  defaultAspectMode: AspectMode
}

const STORAGE_KEY = 'film2.desktop.user-library.v2'
const EVENT_NAME = 'film2:user-library-changed'

const DEFAULT_STATE: UserLibraryState = {
  playback: {},
  myListTitleIds: [],
  customLists: [],
  subtitleStyle: {
    fontScale: 1,
    bottomPercent: 10,
    color: '#ffffff',
    background: 'soft',
    edge: 'outline'
  },
  defaultAspectMode: 'fit'
}

export function contentKey(titleId: string, seasonNumber?: number, episodeNumber?: number): string {
  return `${titleId}|${seasonNumber ?? -1}|${episodeNumber ?? -1}`
}

function parseState(raw: string | null): UserLibraryState {
  if (!raw) return structuredClone(DEFAULT_STATE)
  try {
    const parsed = JSON.parse(raw) as Partial<UserLibraryState>
    return {
      ...structuredClone(DEFAULT_STATE),
      ...parsed,
      playback: parsed.playback ?? {},
      myListTitleIds: parsed.myListTitleIds ?? [],
      customLists: parsed.customLists ?? [],
      subtitleStyle: { ...DEFAULT_STATE.subtitleStyle, ...(parsed.subtitleStyle ?? {}) }
    }
  } catch {
    return structuredClone(DEFAULT_STATE)
  }
}

export function readLibrary(): UserLibraryState {
  return parseState(localStorage.getItem(STORAGE_KEY))
}

function writeLibrary(next: UserLibraryState): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  window.dispatchEvent(new CustomEvent(EVENT_NAME))
}

export function subscribeLibrary(callback: () => void): () => void {
  window.addEventListener(EVENT_NAME, callback)
  window.addEventListener('storage', callback)
  return () => {
    window.removeEventListener(EVENT_NAME, callback)
    window.removeEventListener('storage', callback)
  }
}

export function savePlayback(snapshot: Omit<PlaybackRecord, 'key' | 'updatedAt'>): PlaybackRecord {
  const state = readLibrary()
  const key = contentKey(snapshot.titleId, snapshot.seasonNumber, snapshot.episodeNumber)
  const old = state.playback[key]
  const duration = Math.max(snapshot.durationSeconds || 0, old?.durationSeconds || 0)
  const position = duration > 0
    ? Math.min(Math.max(snapshot.positionSeconds, 0), duration)
    : Math.max(snapshot.positionSeconds, 0)
  const record: PlaybackRecord = {
    ...snapshot,
    key,
    positionSeconds: position,
    durationSeconds: duration,
    updatedAt: Date.now()
  }
  writeLibrary({ ...state, playback: { ...state.playback, [key]: record } })
  return record
}

export function playbackFor(titleId: string, seasonNumber?: number, episodeNumber?: number): PlaybackRecord | undefined {
  return readLibrary().playback[contentKey(titleId, seasonNumber, episodeNumber)]
}

export function progressFraction(record?: PlaybackRecord): number {
  if (!record || record.durationSeconds <= 0) return 0
  return Math.min(1, Math.max(0, record.positionSeconds / record.durationSeconds))
}

export function hasMeaningfulProgress(record?: PlaybackRecord): boolean {
  const progress = progressFraction(record)
  return Boolean(record && record.positionSeconds >= 30 && progress < 0.95)
}

export function continueWatching(titles: Title[]): { title: Title; record: PlaybackRecord }[] {
  const state = readLibrary()
  return Object.values(state.playback)
    .filter(hasMeaningfulProgress)
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .map((record) => ({ title: titles.find((title) => title.id === record.titleId), record }))
    .filter((item): item is { title: Title; record: PlaybackRecord } => Boolean(item.title))
}

export function toggleMyList(titleId: string): void {
  const state = readLibrary()
  const set = new Set(state.myListTitleIds)
  if (set.has(titleId)) set.delete(titleId)
  else set.add(titleId)
  writeLibrary({ ...state, myListTitleIds: [...set] })
}

export function createCustomList(name: string): CustomList | null {
  const clean = name.trim().slice(0, 64)
  if (!clean) return null
  const state = readLibrary()
  const list: CustomList = { id: crypto.randomUUID(), name: clean, titleIds: [] }
  writeLibrary({ ...state, customLists: [...state.customLists, list] })
  return list
}

export function toggleTitleInCustomList(listId: string, titleId: string): void {
  const state = readLibrary()
  writeLibrary({
    ...state,
    customLists: state.customLists.map((list) => {
      if (list.id !== listId) return list
      const set = new Set(list.titleIds)
      if (set.has(titleId)) set.delete(titleId)
      else set.add(titleId)
      return { ...list, titleIds: [...set] }
    })
  })
}

export function setSubtitleStyle(style: Partial<SubtitleStylePreference>): void {
  const state = readLibrary()
  writeLibrary({ ...state, subtitleStyle: { ...state.subtitleStyle, ...style } })
}

export function setDefaultAspectMode(defaultAspectMode: AspectMode): void {
  const state = readLibrary()
  writeLibrary({ ...state, defaultAspectMode })
}

export function chooseArtwork(title: Title, kind: 'poster' | 'backdrop', seed: number): string | undefined {
  const primary = kind === 'poster' ? title.posterUrl : title.backdropUrl
  const pool = kind === 'poster' ? title.posterUrls ?? [] : title.backdropUrls ?? []
  const values = [primary, ...pool].filter((value): value is string => Boolean(value?.trim()))
  if (values.length === 0) return undefined
  let hash = seed >>> 0
  for (const ch of `${title.id}:${kind}`) hash = (Math.imul(hash, 31) + ch.charCodeAt(0)) >>> 0
  return values[hash % values.length]
}
