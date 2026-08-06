// packages/tmdb-client/src/index.js'deki imdbLinkToId/slugify ile BIREBIR ayni mantik —
// sadece TMDB'de eslesme bulunamayip manuel girise dusuldugunde, id/imdbId'yi renderer
// tarafinda (main process cagrisi olmadan) turetmek icin kullanilir. Iki tarafi senkron
// tutmak gerekiyorsa oncelikle packages/tmdb-client'i referans alin.
const IMDB_ID_RE = /tt\d{6,9}/;
const COMBINING_DIACRITICS_RE = /\p{Diacritic}/gu;

export function extractImdbId(input: string): string | null {
  if (!input) return null;
  const match = input.trim().match(IMDB_ID_RE);
  return match ? match[0] : null;
}

export function slugify(title: string, imdbId: string): string {
  const base = (title || "")
    .toLowerCase()
    .normalize("NFD")
    .replace(COMBINING_DIACRITICS_RE, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return base ? `${base}-${imdbId.slice(2, 6)}` : imdbId;
}
