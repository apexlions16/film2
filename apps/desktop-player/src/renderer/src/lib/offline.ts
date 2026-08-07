import type { PlayableAsset } from './types'
import { contentKey, playbackFor } from './userLibrary'

function bestVideo(asset: PlayableAsset, preferredHeight?: number): { url: string; height?: number } | null {
  const variants = asset.videoVariants ?? []
  const preferred = preferredHeight ? variants.find((variant) => variant.height === preferredHeight) : undefined
  const selected = preferred ?? variants.slice().sort((a, b) => b.height - a.height)[0]
  if (selected) return { url: selected.url, height: selected.height }
  if (asset.videoUrl) return { url: asset.videoUrl }
  return null
}

export async function enqueueOfflineAsset(params: {
  titleId: string
  seasonNumber?: number
  episodeNumber?: number
  displayName: string
  asset: PlayableAsset
}): Promise<Film2OfflineRecord | null> {
  const api = window.film2?.offline
  if (!api) return null
  const saved = playbackFor(params.titleId, params.seasonNumber, params.episodeNumber)
  const selected = bestVideo(params.asset, saved?.qualityHeight)
  if (!selected) return null

  return api.enqueue({
    key: contentKey(params.titleId, params.seasonNumber, params.episodeNumber),
    titleId: params.titleId,
    seasonNumber: params.seasonNumber,
    episodeNumber: params.episodeNumber,
    displayName: params.displayName,
    videoUrl: selected.url,
    qualityHeight: selected.height,
    audioLanguages: params.asset.audioLanguages ?? [],
    subtitles: (params.asset.externalSubtitleTracks ?? []).map((track) => ({
      language: track.language,
      label: track.label,
      mimeType: track.mimeType,
      url: track.url
    }))
  })
}
