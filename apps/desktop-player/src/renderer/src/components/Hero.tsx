import { useMemo, useState } from 'react'

import type { Title } from '../lib/types'
import { chooseArtwork } from '../lib/userLibrary'
import { DEMO_STREAM_ID } from '../lib/demoStream'
import styles from './Hero.module.css'

export function Hero({
  title,
  onPlay,
  artworkSeed = 0
}: {
  title: Title
  onPlay: (title: Title) => void
  artworkSeed?: number
}) {
  const isDemo = title.id === DEMO_STREAM_ID
  const kindLabel = title.type === 'series' ? 'Dizi' : 'Film'
  const backdrop = useMemo(() => chooseArtwork(title, 'backdrop', artworkSeed), [title, artworkSeed])
  const [muted, setMuted] = useState(true)

  return (
    <section className={styles.hero}>
      {backdrop ? (
        <img className={styles.backdrop} src={backdrop} alt="" draggable={false} />
      ) : (
        <div className={styles.backdropFallback} aria-hidden="true" />
      )}
      {title.trailerUrl ? (
        <video
          key={title.trailerUrl}
          className={styles.trailer}
          src={title.trailerUrl}
          muted={muted}
          autoPlay
          loop
          playsInline
          preload="metadata"
        />
      ) : null}
      <div className={styles.scrimH} />
      <div className={styles.scrimB} />

      {title.trailerUrl ? (
        <button
          type="button"
          className={styles.muteButton}
          onClick={() => setMuted((value) => !value)}
          aria-label={muted ? 'Trailer sesini ac' : 'Trailer sesini kapat'}
        >
          {muted ? '🔇' : '🔊'}
        </button>
      ) : null}

      <div className={styles.content}>
        {isDemo ? <span className={styles.demoTag}>Demo yayın</span> : null}
        <div className={styles.eyebrow}>
          <span>{kindLabel}</span>
          {title.releaseYear ? <span>{title.releaseYear}</span> : null}
          {title.genres[0] ? <span>{title.genres[0]}</span> : null}
        </div>

        {title.logoUrl ? (
          <img className={styles.logo} src={title.logoUrl} alt={title.title} draggable={false} />
        ) : (
          <h1 className={styles.title}>{title.title}</h1>
        )}

        <p className={styles.overview}>{title.overview}</p>

        <div className={styles.actions}>
          <button type="button" className={styles.playButton} onClick={() => onPlay(title)}>
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
              <path d="M5 3.5L14 9L5 14.5V3.5Z" fill="currentColor" />
            </svg>
            Oynat
          </button>
        </div>
      </div>
    </section>
  )
}
