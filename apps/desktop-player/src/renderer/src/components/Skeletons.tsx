import styles from './Skeletons.module.css'

export function HeroSkeleton() {
  return (
    <div className={styles.hero}>
      <div className={styles.heroContent}>
        <div className={`${styles.block} ${styles.eyebrow}`} />
        <div className={`${styles.block} ${styles.titleLine}`} />
        <div className={`${styles.block} ${styles.titleLineShort}`} />
        <div className={`${styles.block} ${styles.overviewLine}`} />
        <div className={`${styles.block} ${styles.overviewLine}`} />
        <div className={`${styles.block} ${styles.overviewLineShort}`} />
        <div className={`${styles.block} ${styles.cta}`} />
      </div>
    </div>
  )
}

export function RowSkeleton({ heading }: { heading?: string }) {
  return (
    <section className={styles.row}>
      <div className={`${styles.block} ${styles.rowHeading}`} />
      <div className={styles.track}>
        {Array.from({ length: 7 }).map((_, i) => (
          <div key={i} className={`${styles.block} ${styles.poster}`} />
        ))}
      </div>
      {heading ? null : null}
    </section>
  )
}

export function BrowseSkeleton() {
  return (
    <>
      <HeroSkeleton />
      <div style={{ marginTop: 40 }}>
        <RowSkeleton />
        <RowSkeleton />
        <RowSkeleton />
      </div>
    </>
  )
}
