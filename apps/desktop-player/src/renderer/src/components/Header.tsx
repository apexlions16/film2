import { useEffect, useState } from 'react'

import styles from './Header.module.css'

export function Header({
  onRefresh,
  onOpenLibrary,
  onHome
}: {
  onRefresh: () => void
  onOpenLibrary: () => void
  onHome: () => void
}) {
  const [scrolled, setScrolled] = useState(false)

  useEffect(() => {
    const onScroll = (): void => setScrolled(window.scrollY > 8)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  return (
    <header className={`${styles.header} ${scrolled ? styles.scrolled : ''}`}>
      <div className="titlebar-spacer" />
      <div className={styles.bar}>
        <button type="button" className={styles.wordmarkButton} onClick={onHome}>
          <span className={styles.wordmark}>
            film<span className={styles.wordmarkAccent}>2</span>
          </span>
        </button>
        <div className={styles.actions}>
          <button type="button" className={styles.action} onClick={onRefresh} title="Katalogu yenile">
            ↻ <span>Yenile</span>
          </button>
          <button type="button" className={styles.action} onClick={onOpenLibrary}>
            <span>Benim Film2'm</span>
          </button>
        </div>
      </div>
    </header>
  )
}
