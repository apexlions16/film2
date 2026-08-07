import { useCallback, useEffect, useRef, useState } from 'react'

import { fetchCatalog, fetchCatalogRevision, fetchHomeConfig } from '../lib/catalog'
import type { HomeConfig, Title } from '../lib/types'

export type CatalogState =
  | { status: 'loading'; home: HomeConfig; artworkSeed: number }
  | { status: 'error'; message: string; home: HomeConfig; artworkSeed: number }
  | { status: 'loaded'; titles: Title[]; home: HomeConfig; artworkSeed: number }

const EMPTY_HOME: HomeConfig = { heroTitleIds: [], shelves: [], updatedAt: new Date(0).toISOString() }

export function useCatalog(): CatalogState & { reload: () => void } {
  const [state, setState] = useState<CatalogState>({ status: 'loading', home: EMPTY_HOME, artworkSeed: Date.now() })
  const [attempt, setAttempt] = useState(0)
  const lastRevision = useRef<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const manual = attempt > 0
    if (!manual) setState((old) => ({ status: 'loading', home: old.home, artworkSeed: old.artworkSeed }))

    Promise.all([fetchCatalog(), fetchHomeConfig(), fetchCatalogRevision()])
      .then(([titles, home, revision]) => {
        if (cancelled) return
        lastRevision.current = revision
        setState({ status: 'loaded', titles, home, artworkSeed: Date.now() })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message = err instanceof Error ? err.message : 'Katalog yuklenirken bilinmeyen bir hata olustu.'
        setState((old) => ({ status: 'error', message, home: old.home, artworkSeed: Date.now() }))
      })

    return () => {
      cancelled = true
    }
  }, [attempt])

  useEffect(() => {
    let stopped = false
    const timer = window.setInterval(() => {
      void fetchCatalogRevision().then((revision) => {
        if (stopped || !revision) return
        if (lastRevision.current == null) {
          lastRevision.current = revision
          return
        }
        if (revision !== lastRevision.current) {
          lastRevision.current = revision
          setAttempt((n) => n + 1)
        }
      })
    }, 5_000)
    return () => {
      stopped = true
      window.clearInterval(timer)
    }
  }, [])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])

  return { ...state, reload }
}
