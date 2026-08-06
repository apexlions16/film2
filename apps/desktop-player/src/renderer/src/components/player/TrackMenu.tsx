import type { MediaPlaylist } from 'hls.js'

import styles from './TrackMenu.module.css'

function trackLabel(track: MediaPlaylist, index: number): string {
  if (track.name) return track.name
  if (track.lang) return track.lang.toUpperCase()
  return `Parça ${index + 1}`
}

export function TrackMenu({
  label,
  tracks,
  activeId,
  allowOff,
  open,
  onToggle,
  onSelect
}: {
  label: string
  tracks: MediaPlaylist[]
  activeId: number
  allowOff: boolean
  open: boolean
  onToggle: () => void
  onSelect: (id: number) => void
}) {
  if (tracks.length === 0) return null

  const activeLabel = allowOff && activeId === -1 ? 'Kapalı' : trackLabel(tracks[activeId] ?? tracks[0], Math.max(activeId, 0))

  return (
    <div className={styles.wrap}>
      <button
        type="button"
        className={`${styles.trigger} ${open ? styles.triggerOpen : ''}`}
        onClick={(e) => {
          e.stopPropagation()
          onToggle()
        }}
      >
        <span className={styles.triggerLabel}>{label}</span>
        <span className={styles.triggerValue}>{activeLabel}</span>
      </button>

      {open ? (
        <div className={styles.menu} onClick={(e) => e.stopPropagation()}>
          {allowOff ? (
            <button
              type="button"
              className={`${styles.item} ${activeId === -1 ? styles.itemActive : ''}`}
              onClick={() => onSelect(-1)}
            >
              Kapalı
            </button>
          ) : null}
          {tracks.map((track, i) => (
            <button
              key={`${track.id ?? i}-${track.lang ?? ''}`}
              type="button"
              className={`${styles.item} ${activeId === i ? styles.itemActive : ''}`}
              onClick={() => onSelect(i)}
            >
              {trackLabel(track, i)}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  )
}
