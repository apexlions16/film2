import styles from './ErrorState.module.css'

export function ErrorState({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <section className={styles.wrap}>
      <svg
        className={styles.art}
        width="64"
        height="64"
        viewBox="0 0 64 64"
        fill="none"
        aria-hidden="true"
      >
        <circle cx="32" cy="32" r="24" stroke="currentColor" strokeWidth="2" />
        <path d="M32 20V34" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
        <circle cx="32" cy="42.5" r="1.6" fill="currentColor" />
      </svg>
      <h2 className={styles.title}>Katalog yüklenemedi</h2>
      <p className={styles.body}>{message}</p>
      <button type="button" className={styles.retry} onClick={onRetry}>
        Tekrar dene
      </button>
    </section>
  )
}
