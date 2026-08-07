import { useMemo, useState } from 'react'

import type { Episode, Season, Title } from '../lib/types'
import { contentKey, progressFraction } from '../lib/userLibrary'
import { enqueueOfflineAsset } from '../lib/offline'
import { useUserLibrary } from '../hooks/useUserLibrary'
import { useOfflineDownloads } from '../hooks/useOfflineDownloads'
import { StatusBadge } from './StatusBadge'
import styles from './EpisodePicker.module.css'

function formatRuntime(minutes?: number): string | null {
  if (!minutes) return null
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return h > 0 ? `${h}sa ${m}dk` : `${m}dk`
}

export function EpisodePicker({
  title,
  onBack,
  onSelectEpisode
}: {
  title: Title
  onBack: () => void
  onSelectEpisode: (episode: Episode, season: Season) => void
}) {
  const seasons = title.seasons ?? []
  const [seasonIndex, setSeasonIndex] = useState(0)
  const season = seasons[seasonIndex]
  const library = useUserLibrary()
  const downloads = useOfflineDownloads()

  const seasonProgress = useMemo(() => {
    if (!season) return 0
    const downloadable = season.episodes.filter((episode) => episode.asset)
    if (downloadable.length === 0) return 0
    return downloadable
      .map((episode) => downloads.byKey.get(contentKey(title.id, season.seasonNumber, episode.episodeNumber)))
      .filter(Boolean)
      .reduce((sum, record) => {
        const r = record!
        const fraction = r.status === 'complete' ? 1 : r.totalBytes > 0 ? r.downloadedBytes / r.totalBytes : 0
        return sum + fraction
      }, 0) / downloadable.length
  }, [season, downloads.byKey, title.id])

  const downloadSeason = async (): Promise<void> => {
    if (!season) return
    const records = season.episodes.map((episode) => downloads.byKey.get(contentKey(title.id, season.seasonNumber, episode.episodeNumber))).filter(Boolean)
    if (records.length > 0 && records.some((record) => record!.status === 'complete' || record!.status === 'downloading' || record!.status === 'queued')) {
      await Promise.all(records.map((record) => window.film2?.offline.remove(record!.key)))
      return
    }
    for (const episode of season.episodes) {
      if (!episode.asset) continue
      await enqueueOfflineAsset({
        titleId: title.id,
        seasonNumber: season.seasonNumber,
        episodeNumber: episode.episodeNumber,
        displayName: `${title.title} • S${season.seasonNumber}:B${episode.episodeNumber} ${episode.title}`,
        asset: episode.asset
      })
    }
  }

  return (
    <div className={styles.root}>
      <div className="titlebar-spacer" />
      <header className={styles.header}>
        <button type="button" className={styles.back} onClick={onBack} aria-label="Geri">←</button>
        <div className={styles.headerCopy}>
          <h1 className={styles.title}>{title.title}</h1>
          <p className={styles.subtitle}>Bölüm seç</p>
        </div>
        {season ? (
          <button type="button" className={styles.seasonDownload} onClick={() => void downloadSeason()}>
            ↓ Sezonu İndir {seasonProgress > 0 ? `• %${Math.round(seasonProgress * 100)}` : ''}
          </button>
        ) : null}
      </header>

      {seasons.length > 1 ? (
        <div className={styles.seasonTabs}>
          {seasons.map((s, i) => (
            <button key={s.seasonNumber} type="button" className={`${styles.seasonTab} ${i === seasonIndex ? styles.seasonTabActive : ''}`} onClick={() => setSeasonIndex(i)}>{s.name}</button>
          ))}
        </div>
      ) : null}

      {seasonProgress > 0 && seasonProgress < 1 ? <div className={styles.seasonProgress}><span style={{ width: `${seasonProgress * 100}%` }} /></div> : null}

      <div className={styles.grid}>
        {(season?.episodes ?? []).map((episode) => {
          const playable = episode.status === 'ready' && Boolean(episode.asset)
          const runtime = formatRuntime(episode.runtimeMinutes)
          const key = contentKey(title.id, season!.seasonNumber, episode.episodeNumber)
          const record = library.playback[key]
          const progress = progressFraction(record)
          const download = downloads.byKey.get(key)
          const downloadPct = download?.totalBytes ? download.downloadedBytes / download.totalBytes : download?.status === 'complete' ? 1 : 0
          return (
            <article key={episode.episodeNumber} className={`${styles.card} ${playable ? '' : styles.cardLocked}`}>
              <button type="button" className={styles.episodeMain} onClick={() => playable && season && onSelectEpisode(episode, season)} aria-disabled={!playable}>
                <span className={styles.stillWrap}>
                  {episode.stillUrl ? <img src={episode.stillUrl} alt="" draggable={false} /> : <span className={styles.stillFallback}>▶</span>}
                  <span className={styles.stillBadge}><StatusBadge status={episode.status} /></span>
                  <span className={styles.epNumber}>{episode.episodeNumber}</span>
                  {progress > .005 ? <span className={styles.watchProgress}><span style={{ width: `${progress * 100}%` }} /></span> : null}
                </span>
                <span className={styles.info}>
                  <span className={styles.epTitle}>{episode.title}</span>
                  {runtime ? <span className={styles.epRuntime}>{runtime}</span> : null}
                  {record && progress > .005 && progress < .95 ? <span className={styles.resume}>${Math.floor(record.positionSeconds / 60)} dk konumundan devam et</span> : null}
                  {episode.overview ? <span className={styles.epOverview}>{episode.overview}</span> : null}
                </span>
              </button>
              {episode.asset ? (
                <div className={styles.downloadZone}>
                  <button type="button" className={styles.downloadButton} onClick={async () => {
                    if (download) await window.film2?.offline.remove(download.key)
                    else await enqueueOfflineAsset({ titleId: title.id, seasonNumber: season!.seasonNumber, episodeNumber: episode.episodeNumber, displayName: `${title.title} • S${season!.seasonNumber}:B${episode.episodeNumber}`, asset: episode.asset! })
                  }}>
                    {download?.status === 'complete' ? '✓ Cihazda' : download?.status === 'downloading' || download?.status === 'queued' ? `↓ %${Math.round(downloadPct * 100)}` : '↓ İndir'}
                  </button>
                  {download && downloadPct > 0 && downloadPct < 1 ? <div className={styles.downloadProgress}><span style={{ width: `${downloadPct * 100}%` }} /></div> : null}
                </div>
              ) : null}
            </article>
          )
        })}
      </div>
    </div>
  )
}
