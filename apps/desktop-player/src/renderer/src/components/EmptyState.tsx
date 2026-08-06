import styles from './EmptyState.module.css'

export function EmptyState() {
  return (
    <section className={styles.wrap}>
      <svg
        className={styles.art}
        width="88"
        height="88"
        viewBox="0 0 88 88"
        fill="none"
        aria-hidden="true"
      >
        <rect x="10" y="20" width="68" height="48" rx="6" stroke="currentColor" strokeWidth="2" />
        <path d="M10 32H78" stroke="currentColor" strokeWidth="2" />
        <path d="M24 20V32" stroke="currentColor" strokeWidth="2" />
        <path d="M40 20V32" stroke="currentColor" strokeWidth="2" />
        <path d="M56 20V32" stroke="currentColor" strokeWidth="2" />
        <path d="M64 20V32" stroke="currentColor" strokeWidth="2" />
        <circle cx="44" cy="50" r="10" stroke="currentColor" strokeWidth="2" />
        <path d="M41.5 45.5L49 50L41.5 54.5V45.5Z" fill="currentColor" />
      </svg>
      <h2 className={styles.title}>Katalog henüz boş</h2>
      <p className={styles.body}>
        Bu alan, içerik Hugging Face'e yüklenip yayına hazır hale geldikçe otomatik dolacak.
        Şimdilik yalnızca oynatıcıyı denemek için sabitlenmiş demo yayın kullanılabilir.
      </p>
    </section>
  )
}
