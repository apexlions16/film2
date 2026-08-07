#!/usr/bin/env node
import { readdir, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = fileURLToPath(new URL('../../', import.meta.url))
const TITLES_DIR = join(ROOT, 'catalog', 'titles')
const VERSION_PATH = join(ROOT, 'catalog', 'version.json')
const OUTPUT_PATH = join(ROOT, 'catalog', 'index.json')

const files = (await readdir(TITLES_DIR))
  .filter((name) => name.endsWith('.json') && !name.startsWith('_'))
  .sort()

const titles = []
for (const file of files) {
  const raw = await readFile(join(TITLES_DIR, file), 'utf8')
  const title = JSON.parse(raw)
  titles.push(title)
}

titles.sort((a, b) => String(b.updatedAt ?? '').localeCompare(String(a.updatedAt ?? '')))

let revision = new Date().toISOString()
try {
  const version = JSON.parse(await readFile(VERSION_PATH, 'utf8'))
  if (typeof version.revision === 'string' && version.revision) revision = version.revision
} catch {}

const snapshot = {
  revision,
  generatedAt: new Date().toISOString(),
  count: titles.length,
  titles,
}

await writeFile(OUTPUT_PATH, JSON.stringify(snapshot, null, 2) + '\n', 'utf8')
console.log(`catalog/index.json generated: ${titles.length} titles, revision=${revision}`)
