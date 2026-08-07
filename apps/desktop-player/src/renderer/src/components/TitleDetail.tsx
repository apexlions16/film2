import { useMemo, useState } from 'react'
import type { Title } from '../lib/types'
import { chooseArtwork, contentKey, createCustomList, progressFraction, toggleMyList, toggleTitleInCustomList } from '../lib/userLibrary'
import { enqueueOfflineAsset } from '../lib/offline'
import { useUserLibrary } from '../hooks/useUserLibrary'
import { useOfflineDownloads } from '../hooks/useOfflineDownloads'
import styles from './TitleDetail.module.css'

export function TitleDetail({
  title,
  artworkSeed,
  onBack,
  onPlayMovie,
  onEpisodes
}: {
  title: Title
  artworkSeed: number
  onBack: () => void
  onPlayMovie: () => void
  onEpisodes: () => void
}) {
  const library = useUserLibrary()
  const downloads = useOfflineDownloads()
  const [muted, setMuted] = useState(true)
  const [listsOpen, setListsOpen] = useState(false)
  const [newListName, setNewListName] = useState('')
  const backdrop = useMemo(() => chooseArtwork(title, 'backdrop', artworkSeed), [title, artworkSeed])
  const playback = library.playback[contentKey(title.id)]
  const progress = progressFraction(playback)
  const download = downloads.byKey.get(contentKey(title.id))
  const inMyList = library.myListTitleIds.includes(title.id)

  const toggleDownload = async (): Promise<void> => {
    if (download) {
      await window.film2?.offline.remove(download.key)
      return
    }
    if (title.asset) {
      await enqueueOfflineAsset({ titleId: title.id, displayName: title.title, asset: title.asset })
    }
  }

  return (
    <div className={styles.root}>
      <div className={styles.hero}>
        {backdrop ? <img src={backdrop} alt="" className={styles.backdrop} /> : null}
        {title.trailerUrl ? <video className={styles.trailer} src={title.trailerUrl} autoPlay loop muted={muted} playsInline /> : null}
        <div className={styles.scrim} />
        <button type="button" className={styles.back} onClick={onBack}>←</button>
        {title.trailerUrl ? <button type="button" className={styles.mute} onClick={() => setMuted((v) => !v)}>{muted ? '🔇' : '🔊'}</button> : null}
      </div>

      <main className={styles.content}>
        {title.logoUrl ? <img src={title.logoUrl} className={styles.logo} alt={title.title} /> : <h1>{title.title}</h1>}
        <div className={styles.meta}>
          {title.releaseYear ? <span>{title.releaseYear}</span> : null}
          <span>{title.type === 'series' ? 'Dizi' : 'Film'}</span>
          {title.runtimeMinutes ? <span>{title.runtimeMinutes} dk</span> : null}
          {title.genres.slice(0, 3).map((genre) => <span key={genre}>{genre}</span>)}
        </div>

        <div className={styles.actions}>
          {title.type === 'movie' ? (
            <button className={styles.play} disabled={!title.asset} onClick={onPlayMovie}>{playback && progress > .005 && progress < .95 ? `▶ Devam Et • ${Math.floor(playback.positionSeconds / 60)} dk` : '▶ Oynat'}</button>
          ) : (
            <button className={styles.play} onClick={onEpisodes}>▶ Bölümler</button>
          )}
          <button className={styles.secondary} onClick={() => toggleMyList(title.id)}>{inMyList ? '✓ Listemde' : '+ Listem'}</button>
          <button className={styles.secondary} onClick={() => setListsOpen((v) => !v)}>☰ Listeler</button>
          {title.type === 'movie' && title.asset ? (
            <button className={styles.secondary} onClick={() => void toggleDownload()}>
              {download?.status === 'complete' ? '✓ İndirildi • Kaldır' : download?.status === 'downloading' || download?.status === 'queued' ? `↓ %${download.totalBytes > 0 ? Math.round(download.downloadedBytes / download.totalBytes * 100) : 0} • İptal` : '↓ İndir'}
            </button>
          ) : null}
        </div>

        {download && (download.status === 'downloading' || download.status === 'queued') ? (
          <div className={styles.downloadBar}><span style={{ width: `${download.totalBytes > 0 ? Math.min(100, download.downloadedBytes / download.totalBytes * 100) : 3}%` }} /></div>
        ) : null}

        {progress > .005 ? <div className={styles.watchBar}><span style={{ width: `${Math.min(100, progress * 100)}%` }} /></div> : null}

        <p className={styles.overview}>{title.overview}</p>
        {title.cast.length > 0 ? <p className={styles.people}><b>Başroldekiler:</b> {title.cast.slice(0, 6).map((person) => person.name).join(', ')}</p> : null}
        {title.crew.length > 0 ? <p className={styles.people}><b>Yapım:</b> {title.crew.slice(0, 5).map((person) => `${person.name} (${person.job})`).join(', ')}</p> : null}

        {listsOpen ? (
          <section className={styles.listsPanel}>
            <h2>Listeye Ekle</h2>
            {library.customLists.map((list) => (
              <button key={list.id} className={styles.listRow} onClick={() => toggleTitleInCustomList(list.id, title.id)}>
                <span>{list.name}</span><span>{list.titleIds.includes(title.id) ? '✓' : '+'}</span>
              </button>
            ))}
            <div className={styles.newList}>
              <input value={newListName} onChange={(e) => setNewListName(e.target.value)} placeholder="Yeni liste adı" />
              <button onClick={() => {
                const created = createCustomList(newListName)
                if (created) toggleTitleInCustomList(created.id, title.id)
                setNewListName('')
              }}>Oluştur</button>
            </div>
          </section>
        ) : null}
      </main>
    </div>
  )
}
