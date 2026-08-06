import { useMemo, type CSSProperties, type JSX } from 'react'

import type { Title } from '../lib/types'
import { StatusBadge } from './StatusBadge'
import styles from './PosterCard.module.css'

function initials(title: string): string {
  const words = title.trim().split(/\s+/).filter(Boolean)
  if (words.length === 0) return '?'
  if (words.length === 1) return words[0].slice(0, 2).toUpperCase()
  return (words[0][0] + words[1][0]).toUpperCase()
}

// Deterministic-but-varied placeholder tint for posters without art, kept
// strictly within the app's warm amber / muted olive palette — never a
// full hue sweep, so we can't accidentally land on blue/purple/magenta.
const PLACEHOLDER_BANDS: [number, number][] = [
  [16, 42], // amber / copper
  [95, 128] // muted olive / moss
]

function hueFor(seed: string): number {
  let hash = 0
  for (let i = 0; i < seed.length; i++) {
    hash = (hash * 31 + seed.charCodeAt(i)) >>> 0
  }
  const [from, to] = PLACEHOLDER_BANDS[hash % PLACEHOLDER_BANDS.length]
  return from + (hash % (to - from))
}

export function PosterCard({
  title,
  onSelect,
  emphasized
}: {
  title: Title
  onSelect: (title: Title) => void
  emphasized?: boolean
}): JSX.Element {
  const playable = title.status === 'ready'
  const hue = useMemo(() => hueFor(title.id), [title.id])

  return (
    <button
      type="button"
      className={`${styles.card} ${playable ? styles.playable : styles.locked} ${
        emphasized ? styles.emphasized : ''
      }`}
      onClick={() => playable && onSelect(title)}
      aria-disabled={!playable}
      title={title.title}
    >
      <span className={styles.art}>
        {title.posterUrl ? (
          <img src={title.posterUrl} alt="" loading="lazy" draggable={false} />
        ) : (
          <span
            className={styles.placeholder}
            style={{ '--h': hue } as CSSProperties}
            aria-hidden="true"
          >
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
      </span>
    </button>
  )
}
