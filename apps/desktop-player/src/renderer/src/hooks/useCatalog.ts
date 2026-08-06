import { useCallback, useEffect, useState } from 'react'

import { fetchCatalog } from '../lib/catalog'
import type { Title } from '../lib/types'

export type CatalogState =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'loaded'; titles: Title[] }

export function useCatalog(): CatalogState & { reload: () => void } {
  const [state, setState] = useState<CatalogState>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    setState({ status: 'loading' })

    fetchCatalog()
      .then((titles) => {
        if (cancelled) return
        setState({ status: 'loaded', titles })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const message =
          err instanceof Error ? err.message : 'Katalog yuklenirken bilinmeyen bir hata olustu.'
        setState({ status: 'error', message })
      })

    return () => {
      cancelled = true
    }
  }, [attempt])

  const reload = useCallback(() => setAttempt((n) => n + 1), [])

  return { ...state, reload }
}
