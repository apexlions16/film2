// Katalog okuma/yazma. Listeleme icin @film2/catalog-client (kimliksiz, raw.githubusercontent
// + Contents API listing) kullanilir — player uygulamasinin da kullandigi ayni paket.
// Tekil title okuma/yazma icin ise sha gerektigi (guncelleme icin) ve token'li daha yuksek
// rate-limit istedigimiz icin GitHub Contents API'yi (github.ts) dogrudan kullaniyoruz.
import { listTitles as listTitlesRemote } from "@film2/catalog-client";
import type { Title } from "@shared/types";
import { getJsonFile, putJsonFile } from "./github";
import { getSettings } from "./settings";

function titlePath(id: string): string {
  return `catalog/titles/${id}.json`;
}

export async function listTitles(): Promise<Title[]> {
  const titles = (await listTitlesRemote()) as Title[];
  return titles.sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1));
}

export async function getTitle(id: string): Promise<Title> {
  const { githubToken } = getSettings();
  if (!githubToken) {
    throw new Error("GitHub token ayarlanmamis. Once Ayarlar ekranindan girin.");
  }
  const file = await getJsonFile<Title>(titlePath(id), githubToken);
  if (!file) {
    throw new Error(`catalog/titles/${id}.json bulunamadi.`);
  }
  return file.data;
}

/**
 * Title'i catalog/titles/{id}.json olarak commit eder (yeni ise olusturur, varsa gunceller).
 */
export async function saveTitle(title: Title): Promise<{ sha: string }> {
  const { githubToken } = getSettings();
  if (!githubToken) {
    throw new Error("GitHub token ayarlanmamis. Once Ayarlar ekranindan girin.");
  }
  const path = titlePath(title.id);
  const existing = await getJsonFile<Title>(path, githubToken);
  const now = new Date().toISOString();
  const payload: Title = {
    ...title,
    createdAt: title.createdAt || now,
    updatedAt: now,
  };
  const message = existing
    ? `chore(catalog): ${title.id} guncellendi (studio)`
    : `chore(catalog): ${title.id} eklendi (studio)`;
  return putJsonFile(path, payload, message, githubToken, existing?.sha);
}
