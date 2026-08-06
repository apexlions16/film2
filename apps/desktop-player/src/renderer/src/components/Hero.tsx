import type { Title } from '../lib/types'
import { DEMO_STREAM_ID } from '../lib/demoStream'
import styles from './Hero.module.css'

export function Hero({ title, onPlay }: { title: Title; onPlay: (title: Title) => void }) {
  const isDemo = title.id === DEMO_STREAM_ID
  const kindLabel = title.type === 'series' ? 'Dizi' : 'Film'

  return (
    <section className={styles.hero}>
      {title.backdropUrl ? (
        <img className={styles.backdrop} src={title.backdropUrl} alt="" draggable={false} />
      ) : (
        <div className={styles.backdropFallback} aria-hidden="true" />
      )}
      <div className={styles.scrimH} />
      <div className={styles.scrimB} />

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
