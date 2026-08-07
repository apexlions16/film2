export interface SubtitleCue {
  start: number
  end: number
  text: string
}

function parseTime(value: string): number {
  const normalized = value.trim().replace(',', '.')
  const parts = normalized.split(':').map(Number)
  if (parts.some((part) => !Number.isFinite(part))) return 0
  if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2]
  if (parts.length === 2) return parts[0] * 60 + parts[1]
  return parts[0] || 0
}

function cleanText(value: string): string {
  return value
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .trim()
}

export function parseSubtitleFile(raw: string): SubtitleCue[] {
  const text = raw.replace(/^\uFEFF/, '').replace(/\r/g, '')
  const blocks = text.split(/\n{2,}/)
  const cues: SubtitleCue[] = []

  for (const block of blocks) {
    const lines = block.split('\n').map((line) => line.trimEnd()).filter(Boolean)
    if (lines.length < 2) continue

    let timingIndex = lines.findIndex((line) => line.includes('-->'))
    if (timingIndex < 0) continue
    const timing = lines[timingIndex].split('-->')
    if (timing.length < 2) continue

    const start = parseTime(timing[0].trim().split(/\s+/)[0])
    const end = parseTime(timing[1].trim().split(/\s+/)[0])
    if (!(end > start)) continue

    const cueText = cleanText(lines.slice(timingIndex + 1).join('\n'))
    if (!cueText) continue
    cues.push({ start, end, text: cueText })
  }

  return cues.sort((a, b) => a.start - b.start)
}

export async function fetchSubtitleCues(url: string): Promise<SubtitleCue[]> {
  const response = await fetch(url, { cache: 'no-store' })
  if (!response.ok) throw new Error(`Altyazi indirilemedi (${response.status})`)
  return parseSubtitleFile(await response.text())
}

export function activeCueText(cues: SubtitleCue[], time: number): string {
  const active = cues.filter((cue) => time >= cue.start && time < cue.end)
  return active.map((cue) => cue.text).join('\n')
}
