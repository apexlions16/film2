import { posterUrl, backdropUrl, profileUrl, stillUrl } from "./imageUrls.js";

const API_BASE = "https://api.themoviedb.org/3";
const IMDB_ID_RE = /tt\d{6,9}/;
const COMBINING_DIACRITICS_RE = /\p{Diacritic}/gu;

/**
 * IMDb linkinden veya dogrudan girilen tt-id'den IMDb ID cikarir.
 * Kabul eder: "tt1234567", "https://www.imdb.com/title/tt1234567/", "imdb.com/title/tt1234567"
 * @param {string} input
 * @returns {string | null}
 */
export function imdbLinkToId(input) {
  if (!input) return null;
  const match = input.trim().match(IMDB_ID_RE);
  return match ? match[0] : null;
}

/**
 * Basligi catalog/titles/{id}.json dosya adi olacak slug'a cevirir.
 * @param {string} title
 * @param {string} imdbId
 */
export function slugify(title, imdbId) {
  const base = (title || "")
    // Turkce noktasiz/noktali I harfleri NFD ile ayrisitirilmadigi icin once elle cevrilir
    .replace(/İ/g, "I")
    .replace(/ı/g, "i")
    .toLowerCase()
    .normalize("NFD")
    .replace(COMBINING_DIACRITICS_RE, "")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
  return base ? `${base}-${imdbId.slice(2, 6)}` : imdbId;
}

async function tmdbGet(path, apiKey, params = {}) {
  const url = new URL(`${API_BASE}${path}`);
  url.searchParams.set("api_key", apiKey);
  url.searchParams.set("language", "tr-TR");
  for (const [key, value] of Object.entries(params)) {
    url.searchParams.set(key, value);
  }
  const res = await fetch(url);
  if (!res.ok) {
    if (res.status === 404) return null;
    throw new Error(`TMDB istegi basarisiz: ${res.status} ${res.statusText} (${path})`);
  }
  return res.json();
}

/**
 * IMDb ID'yi TMDB find endpoint'i ile eslestirir.
 * @returns {Promise<{ type: "movie" | "tv", tmdbId: number } | null>}
 */
export async function findByImdbId(imdbId, apiKey) {
  const result = await tmdbGet(`/find/${imdbId}`, apiKey, { external_source: "imdb_id" });
  if (!result) return null;
  if (result.movie_results?.length) {
    return { type: "movie", tmdbId: result.movie_results[0].id };
  }
  if (result.tv_results?.length) {
    return { type: "tv", tmdbId: result.tv_results[0].id };
  }
  return null;
}

function mapCast(credits) {
  return (credits?.cast ?? []).slice(0, 20).map((c) => ({
    name: c.name,
    character: c.character ?? "",
    profileUrl: profileUrl(c.profile_path),
  }));
}

function mapCrew(credits) {
  const relevantJobs = new Set(["Director", "Writer", "Creator", "Executive Producer", "Producer"]);
  return (credits?.crew ?? [])
    .filter((c) => relevantJobs.has(c.job))
    .map((c) => ({ name: c.name, job: c.job, profileUrl: profileUrl(c.profile_path) }));
}

async function fetchMovie(tmdbId, apiKey) {
  const data = await tmdbGet(`/movie/${tmdbId}`, apiKey, { append_to_response: "credits" });
  if (!data) return null;
  return {
    type: "movie",
    tmdbId: data.id,
    title: data.title,
    originalTitle: data.original_title,
    overview: data.overview,
    releaseYear: data.release_date ? Number(data.release_date.slice(0, 4)) : undefined,
    genres: (data.genres ?? []).map((g) => g.name),
    runtimeMinutes: data.runtime || undefined,
    posterUrl: posterUrl(data.poster_path),
    backdropUrl: backdropUrl(data.backdrop_path),
    cast: mapCast(data.credits),
    crew: mapCrew(data.credits),
  };
}

async function fetchTvSeason(tmdbId, seasonNumber, apiKey) {
  const data = await tmdbGet(`/tv/${tmdbId}/season/${seasonNumber}`, apiKey);
  if (!data) return null;
  return {
    seasonNumber: data.season_number,
    name: data.name,
    overview: data.overview ?? "",
    posterUrl: posterUrl(data.poster_path),
    episodes: (data.episodes ?? []).map((ep) => ({
      episodeNumber: ep.episode_number,
      title: ep.name,
      overview: ep.overview ?? "",
      airDate: ep.air_date ?? undefined,
      stillUrl: stillUrl(ep.still_path),
      runtimeMinutes: ep.runtime || undefined,
      status: "pending",
    })),
  };
}

async function fetchTv(tmdbId, apiKey) {
  const data = await tmdbGet(`/tv/${tmdbId}`, apiKey, { append_to_response: "credits" });
  if (!data) return null;
  const seasonNumbers = (data.seasons ?? [])
    .map((s) => s.season_number)
    .filter((n) => n > 0); // "Ozel Bolumler" (season 0) haric
  const seasons = [];
  for (const seasonNumber of seasonNumbers) {
    const season = await fetchTvSeason(tmdbId, seasonNumber, apiKey);
    if (season) seasons.push(season);
  }
  return {
    type: "series",
    tmdbId: data.id,
    title: data.name,
    originalTitle: data.original_name,
    overview: data.overview,
    releaseYear: data.first_air_date ? Number(data.first_air_date.slice(0, 4)) : undefined,
    genres: (data.genres ?? []).map((g) => g.name),
    posterUrl: posterUrl(data.poster_path),
    backdropUrl: backdropUrl(data.backdrop_path),
    cast: mapCast(data.credits),
    crew: mapCrew(data.credits),
    seasons,
  };
}

/**
 * IMDb linkinden baslayip catalog-schema Title seklinde (kismi) veri doner.
 * TMDB'de bulunamazsa `null` doner — cagiran taraf (Studio UI) bu durumda
 * manualEntry: true ile bos forma dusmeli.
 * @param {string} imdbLinkOrId
 * @param {string} apiKey
 */
export async function fetchTitleFromImdbLink(imdbLinkOrId, apiKey) {
  const imdbId = imdbLinkToId(imdbLinkOrId);
  if (!imdbId) throw new Error("Gecerli bir IMDb linki/ID bulunamadi (tt... formatinda olmali)");

  const found = await findByImdbId(imdbId, apiKey);
  if (!found) return null;

  const details = found.type === "movie" ? await fetchMovie(found.tmdbId, apiKey) : await fetchTv(found.tmdbId, apiKey);
  if (!details) return null;

  const id = slugify(details.title, imdbId);
  const now = new Date().toISOString();

  return {
    id,
    imdbId,
    manualEntry: false,
    status: "pending",
    createdAt: now,
    updatedAt: now,
    ...details,
  };
}

export * from "./imageUrls.js";
