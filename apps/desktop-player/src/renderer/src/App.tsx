import { useMemo, useState } from 'react'

import { useCatalog } from './hooks/useCatalog'
import { useUserLibrary } from './hooks/useUserLibrary'
import { demoStreamTitle } from './lib/demoStream'
import { hasMeaningfulProgress, progressFraction } from './lib/userLibrary'
import type { Episode, PlayableAsset, Season, Title } from './lib/types'

import { Header } from './components/Header'
import { Hero } from './components/Hero'
import { Row } from './components/Row'
import { EmptyState } from './components/EmptyState'
import { ErrorState } from './components/ErrorState'
import { BrowseSkeleton } from './components/Skeletons'
import { EpisodePicker } from './components/EpisodePicker'
import { Player } from './components/player/Player'
import { TitleDetail } from './components/TitleDetail'
import { LibraryView } from './components/LibraryView'

import styles from './App.module.css'

type PlayerRequest = {
  asset: PlayableAsset
  heading: string
  subheading?: string
  titleId: string
  seasonNumber?: number
  episodeNumber?: number
}

type View =
  | { kind: 'browse' }
  | { kind: 'detail'; title: Title }
  | { kind: 'episodes'; title: Title }
  | { kind: 'library' }
  | { kind: 'player'; request: PlayerRequest; backTo: View }

function groupByGenre(titles: Title[]): { genre: string; titles: Title[] }[] {
  const order: string[] = []
  const map = new Map<string, Title[]>()
  for (const title of titles) {
    for (const genre of title.genres.length > 0 ? title.genres : ['Diğer']) {
      if (!map.has(genre)) { map.set(genre, []); order.push(genre) }
      map.get(genre)!.push(title)
    }
  }
  return order.map((genre) => ({ genre, titles: map.get(genre)! }))
}

function episodeSubheading(season: Season, episode: Episode): string {
  return [`Sezon ${season.seasonNumber}`, `Bölüm ${episode.episodeNumber}`, episode.title].filter(Boolean).join(' · ')
}

function shuffleWithSeed<T>(items: T[], seed: number): T[] {
  const result = [...items]
  let state = seed >>> 0
  for (let i = result.length - 1; i > 0; i--) {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    const j = state % (i + 1)
    ;[result[i], result[j]] = [result[j], result[i]]
  }
  return result
}

export function App() {
  const catalog = useCatalog()
  const library = useUserLibrary()
  const [view, setView] = useState<View>({ kind: 'browse' })

  const realTitles = catalog.status === 'loaded' ? catalog.titles : []
  const readyTitles = realTitles.filter((title) => title.status === 'ready')
  const genreRows = useMemo(() => groupByGenre(readyTitles), [readyTitles])

  const latestByTitle = useMemo(() => {
    const map = new Map<string, (typeof library.playback)[string]>()
    Object.values(library.playback).forEach((record) => {
      const old = map.get(record.titleId)
      if (!old || record.updatedAt > old.updatedAt) map.set(record.titleId, record)
    })
    return map
  }, [library])

  const progressByTitle = useMemo(() => Object.fromEntries(realTitles.map((title) => [title.id, progressFraction(latestByTitle.get(title.id))])), [realTitles, latestByTitle])

  const continueTitles = useMemo(() => Object.values(library.playback)
    .filter(hasMeaningfulProgress)
    .sort((a, b) => b.updatedAt - a.updatedAt)
    .filter((record, index, all) => all.findIndex((item) => item.titleId === record.titleId) === index)
    .map((record) => realTitles.find((title) => title.id === record.titleId))
    .filter((title): title is Title => Boolean(title)), [library, realTitles])

  const featured: Title = useMemo(() => {
    const configured = catalog.home.heroTitleIds.map((id) => readyTitles.find((title) => title.id === id)).filter((title): title is Title => Boolean(title))
    if (configured.length > 0) return configured[catalog.artworkSeed % configured.length]
    return readyTitles[0] ?? realTitles[0] ?? demoStreamTitle
  }, [catalog.home, catalog.artworkSeed, readyTitles, realTitles])

  const editorialRows = useMemo(() => catalog.home.shelves
    .filter((shelf) => shelf.enabled)
    .map((shelf, index) => {
      let titles = shelf.titleIds.map((id) => realTitles.find((title) => title.id === id)).filter((title): title is Title => Boolean(title))
      if (shelf.shuffle) titles = shuffleWithSeed(titles, catalog.artworkSeed + index * 97)
      return { ...shelf, titles: titles.slice(0, shelf.maxItems || 20) }
    })
    .filter((shelf) => shelf.titles.length > 0), [catalog.home, catalog.artworkSeed, realTitles])

  const openTitle = (title: Title): void => setView({ kind: 'detail', title })

  function playMovie(title: Title, backTo: View): void {
    if (title.status !== 'ready' || !title.asset) return
    setView({ kind: 'player', request: { asset: title.asset, heading: title.title, titleId: title.id }, backTo })
  }

  function openEpisode(episode: Episode, season: Season, title: Title): void {
    if (episode.status !== 'ready' || !episode.asset) return
    setView({
      kind: 'player',
      request: {
        asset: episode.asset,
        heading: title.title,
        subheading: episodeSubheading(season, episode),
        titleId: title.id,
        seasonNumber: season.seasonNumber,
        episodeNumber: episode.episodeNumber
      },
      backTo: { kind: 'episodes', title }
    })
  }

  function continueTitle(title: Title): void {
    const record = latestByTitle.get(title.id)
    if (!record) { openTitle(title); return }
    if (record.seasonNumber != null && record.episodeNumber != null) {
      const season = title.seasons?.find((item) => item.seasonNumber === record.seasonNumber)
      const episode = season?.episodes.find((item) => item.episodeNumber === record.episodeNumber)
      if (season && episode) { openEpisode(episode, season, title); return }
    }
    playMovie(title, { kind: 'detail', title })
  }

  if (view.kind === 'player') {
    return <Player asset={view.request.asset} heading={view.request.heading} subheading={view.request.subheading} identity={{ titleId: view.request.titleId, seasonNumber: view.request.seasonNumber, episodeNumber: view.request.episodeNumber }} onClose={() => setView(view.backTo)} />
  }

  if (view.kind === 'episodes') {
    return <EpisodePicker title={view.title} onBack={() => setView({ kind: 'detail', title: view.title })} onSelectEpisode={(episode, season) => openEpisode(episode, season, view.title)} />
  }

  if (view.kind === 'detail') {
    return <TitleDetail title={view.title} artworkSeed={catalog.artworkSeed} onBack={() => setView({ kind: 'browse' })} onPlayMovie={() => playMovie(view.title, view)} onEpisodes={() => setView({ kind: 'episodes', title: view.title })} />
  }

  if (view.kind === 'library') {
    return <LibraryView titles={realTitles} artworkSeed={catalog.artworkSeed} onBack={() => setView({ kind: 'browse' })} onSelect={openTitle} />
  }

  return (
    <div className={styles.app}>
      <Header onRefresh={catalog.reload} onOpenLibrary={() => setView({ kind: 'library' })} onHome={() => setView({ kind: 'browse' })} />

      {catalog.status === 'loading' ? (
        <div className={styles.skeletonWrap}><BrowseSkeleton /></div>
      ) : catalog.status === 'error' ? (
        <><div className={styles.errorHeroSpacer} /><ErrorState message={catalog.message} onRetry={catalog.reload} /></>
      ) : (
        <>
          <Hero title={featured} onPlay={(title) => title.type === 'series' ? setView({ kind: 'episodes', title }) : playMovie(title, { kind: 'detail', title })} artworkSeed={catalog.artworkSeed} />
          <div className={styles.rows}>
            {continueTitles.length > 0 ? <Row heading="Devam Et" titles={continueTitles} onSelect={continueTitle} index={0} progressByTitle={progressByTitle} artworkSeed={catalog.artworkSeed} emphasized /> : null}
            {editorialRows.map((shelf, index) => <Row key={shelf.id} heading={shelf.title} titles={shelf.titles} onSelect={openTitle} index={index + 1} progressByTitle={progressByTitle} artworkSeed={catalog.artworkSeed} />)}
            {library.myListTitleIds.length > 0 ? <Row heading="Listem" titles={library.myListTitleIds.map((id) => realTitles.find((title) => title.id === id)).filter((title): title is Title => Boolean(title))} onSelect={openTitle} index={editorialRows.length + 1} progressByTitle={progressByTitle} artworkSeed={catalog.artworkSeed} /> : null}
            {genreRows.length === 0 ? <EmptyState /> : genreRows.map((group, i) => <Row key={group.genre} heading={group.genre} titles={group.titles} onSelect={openTitle} index={i + editorialRows.length + 2} progressByTitle={progressByTitle} artworkSeed={catalog.artworkSeed} />)}
            <Row heading="Demo Stream (test)" titles={[demoStreamTitle]} onSelect={openTitle} index={99} artworkSeed={catalog.artworkSeed} />
          </div>
        </>
      )}
    </div>
  )
}
