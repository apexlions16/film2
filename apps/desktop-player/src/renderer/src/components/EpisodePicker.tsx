import { useState } from 'react'

import type { Episode, Season, Title } from '../lib/types'
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

  return (
    <div className={styles.root}>
      <div className="titlebar-spacer" />
      <header className={styles.header}>
        <button type="button" className={styles.back} onClick={onBack} aria-label="Geri">
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
            <path
              d="M11.5 3.5L6 9L11.5 14.5"
              stroke="currentColor"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </button>
        <div>
          <h1 className={styles.title}>{title.title}</h1>
          <p className={styles.subtitle}>Bölüm seç</p>
        </div>
      </header>

      {seasons.length > 1 ? (
        <div className={styles.seasonTabs}>
          {seasons.map((s, i) => (
            <button
              key={s.seasonNumber}
              type="button"
              className={`${styles.seasonTab} ${i === seasonIndex ? styles.seasonTabActive : ''}`}
              onClick={() => setSeasonIndex(i)}
            >
              {s.name}
            </button>
          ))}
        </div>
      ) : null}

      <div className={styles.grid}>
        {(season?.episodes ?? []).map((episode) => {
          const playable = episode.status === 'ready'
          const runtime = formatRuntime(episode.runtimeMinutes)
          return (
            <button
              type="button"
              key={episode.episodeNumber}
              className={`${styles.card} ${playable ? '' : styles.cardLocked}`}
              onClick={() => playable && season && onSelectEpisode(episode, season)}
              aria-disabled={!playable}
            >
              <span className={styles.stillWrap}>
                {episode.stillUrl ? (
                  <img src={episode.stillUrl} alt="" draggable={false} />
                ) : (
                  <span className={styles.stillFallback} aria-hidden="true">
                    <svg width="26" height="26" viewBox="0 0 26 26" fill="none">
                      <path d="M10 8L18 13L10 18V8Z" fill="currentColor" />
                    </svg>
                  </span>
                )}
                <span className={styles.stillBadge}>
                  <StatusBadge status={episode.status} />
                </span>
                <span className={styles.epNumber}>{episode.episodeNumber}</span>
              </span>
              <span className={styles.info}>
                <span className={styles.epTitle}>{episode.title}</span>
                {runtime ? <span className={styles.epRuntime}>{runtime}</span> : null}
                {episode.overview ? (
                  <span className={styles.epOverview}>{episode.overview}</span>
                ) : null}
              </span>
            </button>
          )
        })}
      </div>
    </div>
  )
}
