import { useEffect, useState } from 'react'

import styles from './Header.module.css'

export function Header() {
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
        <span className={styles.wordmark}>
          film<span className={styles.wordmarkAccent}>2</span>
        </span>
      </div>
    </header>
  )
}
