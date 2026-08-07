import { useMemo, type CSSProperties, type JSX } from 'react'

import type { Title } from '../lib/types'
import { chooseArtwork } from '../lib/userLibrary'
import { StatusBadge } from './StatusBadge'
import styles from './PosterCard.module.css'

function initials(title: string): string {
  const words = title.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

const PLACEHOLDER_BANDS: [number, number][] = [
  [16, 42],
  [95, 128]
]

function hueFor(seed: string): number {
  let hash = 0
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  const [from, to] = PLACEHOLDER_BANDS[hash % PLACEHOLDER_BANDS.length]
  return from + (hash % (to - from))
}

export function PosterCard({
  title,
  onSelect,
  emphasized,
  progress = 0,
  artworkSeed = 0
}: {
  title: Title
  onSelect: (title: Title) => void
  emphasized?: boolean
  progress?: number
  artworkSeed?: number
}): JSX.Element {
  const playable = title.status === 'ready'
  const hue = useMemo(() => hueFor(title.id), [title.id])
  const poster = useMemo(() => chooseArtwork(title, 'poster', artworkSeed), [title, artworkSeed])

  return (
    <button
      type="button"
      className={`${styles.card} ${playable ? styles.playable : styles.locked} ${emphasized ? styles.emphasized : ''}`}
      onClick={() => playable && onSelect(title)}
      aria-disabled={!playable}
      title={title.title}
    >
      <span className={styles.art}>
        {poster ? (
          <img src={poster} alt="" loading="lazy" draggable={false} />
        ) : (
          <span className={styles.placeholder} style={{ '--h': hue } as CSSProperties} aria-hidden="true">
            {initials(title.title)}
          </span>
        )}
        <span className={styles.scrim} />
        <span className={styles.badgeSlot}>
          <StatusBadge status={title.status} />
        </span>
        <span className={styles.meta}>
          <span className={styles.metaTitle}>{title.title}</span>
          {title.releaseYear ? <span className={styles.metaYear}>{title.releaseYear}</span> : null}
        </span>
        {progress > 0.005 ? (
          <span className={styles.progressTrack} aria-label={`%${Math.round(progress * 100)} izlendi`}>
            <span className={styles.progressFill} style={{ width: `${Math.min(100, progress * 100)}%` }} />
          </span>
        ) : null}
      </span>
    </button>
  )
}
