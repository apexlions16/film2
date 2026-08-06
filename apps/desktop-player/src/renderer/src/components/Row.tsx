import type { Title } from '../lib/types'
import { PosterCard } from './PosterCard'
import styles from './Row.module.css'

export function Row({
  heading,
  titles,
  onSelect,
  index = 0,
  emphasized
}: {
  heading: string
  titles: Title[]
  onSelect: (title: Title) => void
  index?: number
  emphasized?: boolean
}) {
  if (titles.length === 0) return null

  return (
    <section
      className={styles.row}
      style={{ animationDelay: `${Math.min(index, 6) * 70}ms` }}
    >
      <h2 className={styles.heading}>{heading}</h2>
      <div className={styles.track}>
        {titles.map((title) => (
          <PosterCard key={title.id} title={title} onSelect={onSelect} emphasized={emphasized} />
        ))}
      </div>
    </section>
  )
}
