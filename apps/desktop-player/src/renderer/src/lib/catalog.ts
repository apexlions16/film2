import { listTitles as fetchTitlesFromGithub } from '../../../../../../packages/catalog-client/src/index.js'

import type { HomeConfig, Title } from './types'

const REPO = 'apexlions16/film2'
const BRANCH = 'main'

export async function fetchCatalog(): Promise<Title[]> {
  const titles = await fetchTitlesFromGithub()
  return titles as Title[]
}

async function fetchJson<T>(path: string): Promise<T> {
  const nonce = Date.now()
  const response = await fetch(
    `https://raw.githubusercontent.com/${REPO}/${BRANCH}/${path}?v=${nonce}`,
    { cache: 'no-store' }
  )
  if (!response.ok) throw new Error(`${path} alinamadi (${response.status})`)
  return response.json() as Promise<T>
}

export async function fetchHomeConfig(): Promise<HomeConfig> {
  try {
    return await fetchJson<HomeConfig>('catalog/home.json')
  } catch {
    return { heroTitleIds: [], shelves: [], updatedAt: new Date(0).toISOString() }
  }
}

export async function fetchCatalogRevision(): Promise<string | null> {
  try {
    const value = await fetchJson<{ revision: string }>('catalog/version.json')
    return value.revision
  } catch {
    return null
  }
}
