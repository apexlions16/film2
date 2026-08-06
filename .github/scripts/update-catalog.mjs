#!/usr/bin/env node
// package-media.mjs'in urettigi sonucu catalog/titles/{id}.json'a yazar.
// Kullanim: node update-catalog.mjs '<result json string>'
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
  const season = title.seasons?.find((s) => s.seasonNumber === seasonNumber);
  if (!season) throw new Error(`Sezon bulunamadi: ${titleId} S${seasonNumber}`);
  const episode = season.episodes.find((e) => e.episodeNumber === episodeNumber);
  if (!episode) throw new Error(`Bolum bulunamadi: ${titleId} S${seasonNumber}E${episodeNumber}`);
  episode.status = "ready";
  episode.shardId = shardId;
  episode.asset = asset;
} else {
  title.status = "ready";
  title.shardId = shardId;
  title.asset = asset;
}
title.updatedAt = now;

await writeFile(titlePath, JSON.stringify(title, null, 2) + "\n", "utf-8");
console.log(`Katalog guncellendi: ${titlePath}`);
