import type { Title } from '../lib/types'
import { progressFraction } from '../lib/userLibrary'
import { useUserLibrary } from '../hooks/useUserLibrary'
import { useOfflineDownloads } from '../hooks/useOfflineDownloads'
import { Row } from './Row'
import styles from './LibraryView.module.css'

export function LibraryView({
  titles,
  artworkSeed,
  onBack,
  onSelect
}: {
  titles: Title[]
  artworkSeed: number
  onBack: () => void
  onSelect: (title: Title) => void
}) {
  const library = useUserLibrary()
  const downloads = useOfflineDownloads()
  const progressByTitle = Object.fromEntries(
    titles.map((title) => {
      const records = Object.values(library.playback).filter((record) => record.titleId === title.id)
      const latest = records.sort((a,b) => b.updatedAt - a.updatedAt)[0]
      return [title.id, progressFraction(latest)]
    })
  )

  const myList = library.myListTitleIds.map((id) => titles.find((title) => title.id === id)).filter((title): title is Title => Boolean(title))
  const downloadedTitleIds = new Set(downloads.records.filter((record) => record.status === 'complete').map((record) => record.titleId))
  const downloaded = titles.filter((title) => downloadedTitleIds.has(title.id))

  return (
    <div className={styles.root}>
      <div className="titlebar-spacer" />
      <header className={styles.header}>
        <button className={styles.back} onClick={onBack}>←</button>
        <div><h1>Benim Film2'm</h1><p>Listelerin, indirdiklerin ve koleksiyonların</p></div>
      </header>
      <Row heading="İndirilenler" titles={downloaded} onSelect={onSelect} progressByTitle={progressByTitle} artworkSeed={artworkSeed} index={0} />
      <Row heading="Listem" titles={myList} onSelect={onSelect} progressByTitle={progressByTitle} artworkSeed={artworkSeed} index={1} />
      {library.customLists.map((list, index) => (
        <Row
          key={list.id}
          heading={list.name}
          titles={list.titleIds.map((id) => titles.find((title) => title.id === id)).filter((title): title is Title => Boolean(title))}
          onSelect={onSelect}
          progressByTitle={progressByTitle}
          artworkSeed={artworkSeed}
          index={index + 2}
        />
      ))}
      {downloaded.length === 0 && myList.length === 0 && library.customLists.length === 0 ? <div className={styles.empty}>Henüz bir listen veya indirilen içeriğin yok.</div> : null}
    </div>
  )
}
