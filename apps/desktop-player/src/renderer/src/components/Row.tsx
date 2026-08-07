import type { Title } from '../lib/types'
import { PosterCard } from './PosterCard'
import styles from './Row.module.css'

export function Row({
  heading,
  titles,
  onSelect,
  index = 0,
  emphasized,
  progressByTitle = {},
  artworkSeed = 0
}: {
  heading: string
  titles: Title[]
  onSelect: (title: Title) => void
  index?: number
  emphasized?: boolean
  progressByTitle?: Record<string, number>
  artworkSeed?: number
}) {
  if (titles.length === 0) return null

  return (
    <section className={styles.row} style={{ animationDelay: `${Math.min(index, 6) * 70}ms` }}>
      {heading ? <h2 className={styles.heading}>{heading}</h2> : null}
      <div className={styles.track}>
        {titles.map((title) => (
          <PosterCard
            key={title.id}
            title={title}
            onSelect={onSelect}
            emphasized={emphasized}
            progress={progressByTitle[title.id] ?? 0}
            artworkSeed={artworkSeed}
          />
        ))}
      </div>
    </section>
  )
}
