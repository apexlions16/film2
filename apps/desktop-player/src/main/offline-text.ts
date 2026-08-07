import { app } from 'electron'
import { readFile } from 'node:fs/promises'
import { join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export async function readOfflineText(fileUrl: string): Promise<string> {
  const path = fileUrl.startsWith('file:') ? fileURLToPath(fileUrl) : fileUrl
  const root = resolve(join(app.getPath('videos'), 'Film2 Downloads')).toLowerCase()
  const candidate = resolve(path).toLowerCase()
  const inside = candidate === root || candidate.startsWith(`${root}\\`) || candidate.startsWith(`${root}/`)
  if (!inside) throw new Error('Film2 indirme klasoru disindaki dosyaya erisim reddedildi')
  return readFile(path, 'utf8')
}
