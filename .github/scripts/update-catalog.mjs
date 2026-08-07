#!/usr/bin/env node
// package-media.mjs'in urettigi tek-MP4 sonucunu catalog/titles/{id}.json'a yazar.
import { readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const REPO_ROOT = fileURLToPath(new URL("../../", import.meta.url));
const result = JSON.parse(process.argv[2]);
const { titleId, kind, seasonNumber, episodeNumber, shardId, asset } = result;

const titlePath = join(REPO_ROOT, "catalog", "titles", `${titleId}.json`);
const title = JSON.parse(await readFile(titlePath, "utf-8"));
const now = new Date().toISOString();

if (kind === "episode") {
  if (!Number.isInteger(seasonNumber) || !Number.isInteger(episodeNumber)) {
    throw new Error(`Gecersiz sezon/bolum: ${seasonNumber}/${episodeNumber}`);
  }
  title.seasons ??= [];
  let season = title.seasons.find((s) => s.seasonNumber === seasonNumber);
  if (!season) {
    season = {
      seasonNumber,
      name: `Sezon ${seasonNumber}`,
      overview: "",
      episodes: [],
    };
    title.seasons.push(season);
  }
  season.episodes ??= [];
  let episode = season.episodes.find((e) => e.episodeNumber === episodeNumber);
  if (!episode) {
    episode = {
      episodeNumber,
      title: `${episodeNumber}. Bolum`,
      overview: "",
      status: "ready",
    };
    season.episodes.push(episode);
  }
  episode.status = "ready";
  episode.shardId = shardId;
  episode.asset = asset;
  season.episodes.sort((a, b) => a.episodeNumber - b.episodeNumber);
  title.seasons.sort((a, b) => a.seasonNumber - b.seasonNumber);
  title.status = "ready";
} else {
  title.status = "ready";
  title.shardId = shardId;
  title.asset = asset;
}

title.updatedAt = now;
await writeFile(titlePath, JSON.stringify(title, null, 2) + "\n", "utf-8");
console.log(`Katalog guncellendi: ${titlePath}`);
