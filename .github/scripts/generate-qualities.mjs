#!/usr/bin/env node
// Mevcut tek MP4 kaynaktan 720p / 480p gibi kalite varyantlari uretir.
// HLS/m3u8/segment YOKTUR. Kaynak MP4 degistirilmez.
// Her varyant tek MP4'tur ve kaynak dosyadaki tum ses track'leri -c:a copy ile korunur.

import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdtemp, readFile, writeFile, rm, stat } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { tmpdir } from "node:os";
import { dirname, join, posix } from "node:path";
import { fileURLToPath } from "node:url";

import {
  loadShardRegistry,
  saveShardRegistry,
  namespaceOf,
  resolveHfAccount,
  uploadFileWithFailover,
} from "../../packages/hf-storage/src/index.js";

const execFileAsync = promisify(execFile);
const REPO_ROOT = fileURLToPath(new URL("../../", import.meta.url));
const SHARDS_JSON = join(REPO_ROOT, "catalog", "shards.json");
const HF_TOKEN = process.env.HF_TOKEN;
const HF_ACCOUNTS_JSON = process.env.HF_ACCOUNTS_JSON;

if (!HF_TOKEN && !HF_ACCOUNTS_JSON) {
  throw new Error("HF_TOKEN veya HF_ACCOUNTS_JSON eksik");
}

async function resolveAccounts() {
  const accounts = HF_ACCOUNTS_JSON ? JSON.parse(HF_ACCOUNTS_JSON) : [];
  if (HF_TOKEN && !accounts.some((a) => a.token === HF_TOKEN)) {
    const { namespace } = await resolveHfAccount(HF_TOKEN);
    accounts.push({ namespace, token: HF_TOKEN });
  }
  if (accounts.length === 0) throw new Error("Kullanilabilir Hugging Face hesabi yok");
  return accounts;
}

function tokenForNamespace(accounts, namespace) {
  const account = accounts.find((a) => a.namespace === namespace);
  if (!account) throw new Error(`${namespace} icin HF token bulunamadi`);
  return account.token;
}

function sourcePathFromUrl(url) {
  const parsed = new URL(url);
  const marker = "/resolve/main/";
  const index = parsed.pathname.indexOf(marker);
  if (index < 0) throw new Error(`HF resolve URL'i taninamadi: ${url}`);
  return decodeURIComponent(parsed.pathname.slice(index + marker.length));
}

async function download(url, shardId, accounts, destination) {
  const token = tokenForNamespace(accounts, namespaceOf(shardId));
  const started = Date.now();
  const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`Kaynak MP4 indirilemedi (${res.status})`);
  await pipeline(Readable.fromWeb(res.body), createWriteStream(destination));
  const info = await stat(destination);
  const seconds = Math.max((Date.now() - started) / 1000, 0.001);
  console.log(`QUALITY_DOWNLOAD bytes=${info.size} seconds=${seconds.toFixed(2)} mbps=${((info.size / 1024 / 1024) / seconds).toFixed(2)}`);
  return info.size;
}

async function probe(file) {
  const { stdout } = await execFileAsync("ffprobe", [
    "-v", "error",
    "-select_streams", "v:0",
    "-show_entries", "stream=width,height,codec_name",
    "-of", "json",
    file,
  ], { maxBuffer: 1024 * 1024 });
  const stream = JSON.parse(stdout).streams?.[0];
  if (!stream?.height) throw new Error("Kaynak videonun cozunurlugu belirlenemedi");
  return stream;
}

function findAsset(title, kind, seasonNumber, episodeNumber) {
  if (kind === "movie") {
    if (!title.asset?.videoUrl) throw new Error(`${title.id} icin direct videoUrl yok`);
    return { asset: title.asset, shardId: title.shardId };
  }
  const seasonNo = Number(seasonNumber);
  const episodeNo = Number(episodeNumber);
  const episode = title.seasons?.find((s) => Number(s.seasonNumber) === seasonNo)
    ?.episodes?.find((e) => Number(e.episodeNumber) === episodeNo);
  if (!episode?.asset?.videoUrl) throw new Error(`${title.id} S${seasonNo}E${episodeNo} icin direct videoUrl yok`);
  return { asset: episode.asset, shardId: episode.shardId };
}

function attachVariants(title, kind, seasonNumber, episodeNumber, variants) {
  if (kind === "movie") {
    title.asset.videoVariants = variants;
    return;
  }
  const seasonNo = Number(seasonNumber);
  const episodeNo = Number(episodeNumber);
  const episode = title.seasons.find((s) => Number(s.seasonNumber) === seasonNo)
    .episodes.find((e) => Number(e.episodeNumber) === episodeNo);
  episode.asset.videoVariants = variants;
}

async function encodeVariant(source, output, height) {
  const crf = height >= 720 ? "22" : "24";
  const started = Date.now();
  console.log(`QUALITY_ENCODE_START height=${height}`);
  await execFileAsync("ffmpeg", [
    "-y",
    "-i", source,
    "-map", "0:v:0",
    "-map", "0:a?",
    "-vf", `scale=-2:${height}:flags=lanczos`,
    "-c:v", "libx264",
    "-preset", "veryfast",
    "-crf", crf,
    "-pix_fmt", "yuv420p",
    "-c:a", "copy",
    "-map_metadata", "0",
    "-movflags", "+faststart",
    "-max_muxing_queue_size", "4096",
    output,
  ], { maxBuffer: 16 * 1024 * 1024 });
  const info = await stat(output);
  console.log(`QUALITY_ENCODE_DONE height=${height} bytes=${info.size} seconds=${((Date.now() - started) / 1000).toFixed(2)}`);
  return info.size;
}

async function main() {
  const payload = JSON.parse(process.argv[2] ?? "{}");
  const titleId = payload.titleId;
  const kind = payload.kind === "episode" ? "episode" : "movie";
  const targets = Array.from(new Set((payload.targets ?? [720, 480]).map(Number)))
    .filter((h) => Number.isInteger(h) && h > 0)
    .sort((a, b) => b - a);
  if (!titleId) throw new Error("titleId eksik");

  const titlePath = join(REPO_ROOT, "catalog", "titles", `${titleId}.json`);
  const title = JSON.parse(await readFile(titlePath, "utf-8"));
  const { asset, shardId } = findAsset(title, kind, payload.seasonNumber, payload.episodeNumber);
  if (!shardId) throw new Error("Kaynak shardId bulunamadi");

  const sourceUrl = asset.videoUrl;
  const sourceRepoPath = sourcePathFromUrl(sourceUrl);
  const sourceDir = posix.dirname(sourceRepoPath);
  const accounts = await resolveAccounts();
  const workDir = await mkdtemp(join(tmpdir(), "film2-quality-"));
  const sourceLocal = join(workDir, "source.mp4");

  console.log(`QUALITY_SOURCE url=${sourceUrl}`);
  await download(sourceUrl, shardId, accounts, sourceLocal);
  const sourceInfo = await probe(sourceLocal);
  const sourceHeight = Number(sourceInfo.height);
  const sourceWidth = Number(sourceInfo.width ?? 0);
  console.log(`QUALITY_SOURCE_INFO width=${sourceWidth} height=${sourceHeight} codec=${sourceInfo.codec_name ?? "unknown"}`);

  let registry = await loadShardRegistry(SHARDS_JSON);
  const variants = [{
    label: `${sourceHeight}p`,
    height: sourceHeight,
    width: sourceWidth || null,
    url: sourceUrl,
    source: true,
  }];

  for (const height of targets.filter((h) => h < sourceHeight)) {
    const outputLocal = join(workDir, `video_${height}p.mp4`);
    await encodeVariant(sourceLocal, outputLocal, height);
    const repoPath = `${sourceDir}/video_${height}p.mp4`;
    const uploadStarted = Date.now();
    const uploaded = await uploadFileWithFailover({
      localPath: outputLocal,
      repoPath,
      registry,
      accounts,
    });
    registry = uploaded.registry;
    console.log(`QUALITY_UPLOAD_DONE height=${height} bytes=${uploaded.bytes} seconds=${((Date.now() - uploadStarted) / 1000).toFixed(2)} shard=${uploaded.shard.id}`);
    variants.push({
      label: `${height}p`,
      height,
      width: null,
      url: uploaded.url,
      source: false,
    });
    await rm(outputLocal, { force: true });
  }

  variants.sort((a, b) => b.height - a.height);
  attachVariants(title, kind, payload.seasonNumber, payload.episodeNumber, variants);
  title.updatedAt = new Date().toISOString();
  await writeFile(titlePath, JSON.stringify(title, null, 2) + "\n", "utf-8");
  await saveShardRegistry(SHARDS_JSON, registry);

  await rm(workDir, { recursive: true, force: true });
  console.log(JSON.stringify({
    titleId,
    kind,
    seasonNumber: payload.seasonNumber ?? null,
    episodeNumber: payload.episodeNumber ?? null,
    sourceHeight,
    variants,
  }, null, 2));
}

main().catch((err) => {
  console.error(err?.stack ?? err);
  process.exit(1);
});
