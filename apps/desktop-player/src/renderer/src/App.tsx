import { useMemo, useState } from 'react'

import { useCatalog } from './hooks/useCatalog'
import { demoStreamTitle } from './lib/demoStream'
import type { Episode, PlayableAsset, Season, Title } from './lib/types'

import { Header } from './components/Header'
import { Hero } from './components/Hero'
import { Row } from './components/Row'
import { EmptyState } from './components/EmptyState'
import { ErrorState } from './components/ErrorState'
import { BrowseSkeleton } from './components/Skeletons'
import { EpisodePicker } from './components/EpisodePicker'
import { Player } from './components/player/Player'

import styles from './App.module.css'

type PlayerRequest = {
  asset: PlayableAsset
  heading: string
  subheading?: string
}

type View =
  | { kind: 'browse' }
  | { kind: 'episodes'; title: Title }
  | { kind: 'player'; request: PlayerRequest; backTo: View }

function groupByGenre(titles: Title[]): { genre: string; titles: Title[] }[] {
  const order: string[] = []
  const map = new Map<string, Title[]>()
  for (const title of titles) {
    for (const genre of title.genres.length > 0 ? title.genres : ['Diğer']) {
      if (!map.has(genre)) {
        map.set(genre, [])
        order.push(genre)
      }
      map.get(genre)!.push(title)
    }
  }
  return order.map((genre) => ({ genre, titles: map.get(genre)! }))
}

function episodeSubheading(title: Title, season: Season, episode: Episode): string {
  const parts = [`Sezon ${season.seasonNumber}`, `Bölüm ${episode.episodeNumber}`]
  if (episode.title) parts.push(episode.title)
  return parts.join(' · ')
}

export function App() {
  const catalog = useCatalog()
  const [view, setView] = useState<View>({ kind: 'browse' })

  const realTitles = catalog.status === 'loaded' ? catalog.titles : []
  const genreRows = useMemo(() => groupByGenre(realTitles), [realTitles])

  const featured: Title = useMemo(() => {
    const firstReady = realTitles.find((t) => t.status === 'ready')
    return firstReady ?? demoStreamTitle
  }, [realTitles])

  function openTitle(title: Title): void {
    if (title.status !== 'ready') return
    if (title.type === 'series') {
      setView({ kind: 'episodes', title })
      return
    }
    if (!title.asset) return
    setView({
      kind: 'player',
      request: { asset: title.asset, heading: title.title },
      backTo: view
    })
  }

  function openEpisode(episode: Episode, season: Season, title: Title): void {
    if (episode.status !== 'ready' || !episode.asset) return
    setView({
      kind: 'player',
      request: {
        asset: episode.asset,
        heading: title.title,
        subheading: episodeSubheading(title, season, episode)
      },
      backTo: { kind: 'episodes', title }
    })
  }

  if (view.kind === 'player') {
    return (
      <Player
        asset={view.request.asset}
        heading={view.request.heading}
        subheading={view.request.subheading}
        onClose={() => setView(view.backTo)}
      />
    )
  }

  if (view.kind === 'episodes') {
    return (
      <EpisodePicker
        title={view.title}
        onBack={() => setView({ kind: 'browse' })}
        onSelectEpisode={(episode, season) => openEpisode(episode, season, view.title)}
      />
    )
  }

  return (
    <div className={styles.app}>
      <Header />

      {catalog.status === 'loading' ? (
        <div className={styles.skeletonWrap}>
          <BrowseSkeleton />
        </div>
      ) : catalog.status === 'error' ? (
        <>
          <div className={styles.errorHeroSpacer} />
          <ErrorState message={catalog.message} onRetry={catalog.reload} />
        </>
      ) : (
        <>
          <Hero title={featured} onPlay={openTitle} />

          <div className={styles.rows}>
            <Row
              heading="Demo Stream (test)"
              titles={[demoStreamTitle]}
              onSelect={openTitle}
              index={0}
            />

            {genreRows.length === 0 ? (
              <EmptyState />
            ) : (
              genreRows.map((group, i) => (
                <Row
                  key={group.genre}
                  heading={group.genre}
                  titles={group.titles}
                  onSelect={openTitle}
                  index={i + 1}
                />
              ))
            )}
          </div>
        </>
      )}
    </div>
  )
}
