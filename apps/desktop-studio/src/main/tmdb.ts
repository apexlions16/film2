// @film2/tmdb-client'in ince sarmalayicisi — token'i settings'ten alir.
import { fetchTitleFromImdbLink } from "@film2/tmdb-client";
import type { Title } from "@shared/types";
import { getSettings } from "./settings";

/**
 * IMDb linkinden/tt-id'sinden Title dondurur. TMDB'de eslesme yoksa null doner —
 * cagiran taraf (renderer) bu durumda manuel giris formuna dusmeli.
 */
export async function fetchTitleByImdb(imdbLinkOrId: string): Promise<Title | null> {
  const { tmdbApiKey } = getSettings();
  if (!tmdbApiKey) {
    throw new Error("TMDB API anahtari ayarlanmamis. Once Ayarlar ekranindan girin.");
  }
  const result = await fetchTitleFromImdbLink(imdbLinkOrId, tmdbApiKey);
  return (result as Title | null) ?? null;
}
