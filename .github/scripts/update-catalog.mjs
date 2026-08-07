#!/usr/bin/env node
// package-media.mjs'in urettigi tek-MP4 sonucunu catalog/titles/{id}.json'a yazar.
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = fileURLToPath(new URL("../../", import.meta.url));
const result = JSON.parse(process.argv[2]);
const { titleId, kind, seasonNumber, episodeNumber, shardId, asset } = result;

if (!titleId) throw new Error("Katalog sonucu titleId icermiyor");
if (!shardId) throw new Error("Katalog sonucu shardId icermiyor");
if (!asset?.videoUrl && !asset?.masterPlaylistUrl) {
  throw new Error("Katalog sonucu videoUrl/masterPlaylistUrl icermiyor");
}

function positiveInteger(value, label) {
  const number = Number(value);
  if (!Number.isInteger(number) || number < 1) {
    throw new Error(`${label} pozitif bir tam sayi olmali; gelen deger: ${value}`);
  }
  return number;
}

const titlePath = join(REPO_ROOT, "catalog", "titles", `${titleId}.json`);
const versionPath = join(REPO_ROOT, "catalog", "version.json");
const title = JSON.parse(await readFile(titlePath, "utf-8"));
const now = new Date().toISOString();

if (kind === "episode") {
  const seasonNo = positiveInteger(seasonNumber, "seasonNumber");
  const episodeNo = positiveInteger(episodeNumber, "episodeNumber");
  if (!Array.isArray(title.seasons)) title.seasons = [];

  let season = title.seasons.find((item) => Number(item.seasonNumber) === seasonNo);
  if (!season) {
    season = { seasonNumber: seasonNo, name: `Sezon ${seasonNo}`, overview: "", episodes: [] };
    title.seasons.push(season);
  }
  if (!Array.isArray(season.episodes)) season.episodes = [];

  let episode = season.episodes.find((item) => Number(item.episodeNumber) === episodeNo);
  if (!episode) {
    episode = { episodeNumber: episodeNo, title: `${episodeNo}. Bolum`, overview: "", status: "pending" };
    season.episodes.push(episode);
  }

  episode.status = "ready";
  episode.shardId = shardId;
  episode.asset = asset;
  title.status = "ready";
  season.episodes.sort((a, b) => Number(a.episodeNumber) - Number(b.episodeNumber));
  title.seasons.sort((a, b) => Number(a.seasonNumber) - Number(b.seasonNumber));
} else if (kind === "movie") {
  title.status = "ready";
  title.shardId = shardId;
  title.asset = asset;
} else {
  throw new Error(`Bilinmeyen katalog turu: ${kind}`);
}

title.updatedAt = now;
await writeFile(titlePath, JSON.stringify(title, null, 2) + "\n", "utf-8");
await writeFile(versionPath, JSON.stringify({ revision: now }, null, 2) + "\n", "utf-8");
console.log(`Katalog guncellendi: ${titlePath}`);
console.log(`Player revision guncellendi: ${versionPath}`);
