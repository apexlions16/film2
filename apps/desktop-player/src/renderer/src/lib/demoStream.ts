import type { Title } from './types'

/**
 * Gercek katalog Hugging Face'e ilk icerik yuklenene kadar bos olacak.
 * Bu sabit kayit, oynatici zincirini (hls.js, ses/altyazi parca secimi,
 * kontroller) ucdan uca test edebilmek icin ayri, acikca etiketlenmis bir
 * girdi olarak eklenir — gercek katalog listesinden GELMEZ.
 */
export const DEMO_STREAM_ID = 'demo-stream'

export const demoStreamTitle: Title = {
  id: DEMO_STREAM_ID,
  type: 'movie',
  imdbId: 'tt0000000',
  title: 'Demo Stream (test)',
  originalTitle: 'x36xhzz',
  overview:
    'Katalogda henuz gercek bir icerik yokken oynaticiyi (coklu ses parcasi, altyazi ve kontroller dahil) test etmek icin herkese acik bir HLS test yayini. Gercek katalogdan gelmez.',
  releaseYear: undefined,
  genres: ['Demo'],
  runtimeMinutes: undefined,
  posterUrl: '',
  backdropUrl: '',
  logoUrl: '',
  cast: [],
  crew: [],
  status: 'ready',
  manualEntry: true,
  createdAt: '2026-01-01T00:00:00.000Z',
  updatedAt: '2026-01-01T00:00:00.000Z',
  asset: {
    masterPlaylistUrl: 'https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8',
    audioLanguages: [],
    subtitleLanguages: []
  }
}
