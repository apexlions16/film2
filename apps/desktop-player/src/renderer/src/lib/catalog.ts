// Katalog verisine erisim: dogrudan packages/catalog-client kullanir (GitHub'dan
// taze ceker, yerel depolama yok). Relative import ile — workspace symlink'lerine
// guvenmiyoruz, monorepo henuz `npm install` calistirilmamis olabilir.
import { listTitles as fetchTitlesFromGithub } from '../../../../../../packages/catalog-client/src/index.js'

import type { Title } from './types'

export async function fetchCatalog(): Promise<Title[]> {
  const titles = await fetchTitlesFromGithub()
  return titles as Title[]
}
