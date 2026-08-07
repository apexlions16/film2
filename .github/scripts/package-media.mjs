#!/usr/bin/env node
// Studio'nun Hugging Face'e yukledigi video + harici sesleri TEK MP4 icine remux eder.
// HLS / m3u8 / ts segmenti URETMEZ. Video ve ses yeniden encode edilmez (-c copy),
// dolayisiyla kalite kaybi yoktur. VTT/SRT altyazilar sidecar olarak kalir.

import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdir, mkdtemp, writeFile } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { tmpdir } from "node:os";
import { join, extname } from "node:path";
import { fileURLToPath } from "node:url";

import {
  loadShardRegistry,
  saveShardRegistry,
  namespaceOf,
  resolveUrl,
  resolveHfAccount,
  uploadDirectoryWithFailover,
} from "../../packages/hf-storage/src/index.js";

const execFileAsync = promisify(execFile);
const REPO_ROOT = fileURLToPath(new URL("../../", import.meta.url));
const SHARDS_JSON = join(REPO_ROOT, "catalog", "shards.json");
const HF_TOKEN = process.env.HF_TOKEN;
const HF_ACCOUNTS_JSON = process.env.HF_ACCOUNTS_JSON;

if (!HF_TOKEN && !HF_ACCOUNTS_JSON) {
  console.error("HF_TOKEN veya HF_ACCOUNTS_JSON eksik.");
  process.exit(1);
}

async function resolveAccounts() {
  const accounts = HF_ACCOUNTS_JSON ? JSON.parse(HF_ACCOUNTS_JSON) : [];
  if (HF_TOKEN && !accounts.some((a) => a.token === HF_TOKEN)) {
    const { namespace } = await resolveHfAccount(HF_TOKEN);
    accounts.push({ namespace, token: HF_TOKEN });
  }
  if (accounts.length === 0) throw new Error("Kullanilabilir Hugging Face hesabi yok.");
  return accounts;
}

const payload = JSON.parse(process.argv[2] ?? "{}");
const {
  titleId,
  kind,
  seasonNumber,
  episodeNumber,
  shardId,
  mode,
  incomingPrefix,
  combinedFile,
  videoFile,
  audioFiles = {},
  subtitleFiles = {},
} = payload;

if (!titleId || !shardId || !incomingPrefix || !mode) {
  throw new Error(`Eksik payload: ${JSON.stringify(payload)}`);
}

let accounts = [];

function tokenForNamespace(namespace) {
  const account = accounts.find((a) => a.namespace === namespace);
  if (!account) throw new Error(`${namespace} icin HF token bulunamadi.`);
  return account.token;
}

async function downloadFromShard(repoPath, destPath) {
  const url = resolveUrl(shardId, repoPath);
  const token = tokenForNamespace(namespaceOf(shardId));
  const res = await fetch(url, { headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`Indirme basarisiz (${res.status}): ${url}`);
  await pipeline(Readable.fromWeb(res.body), createWriteStream(destPath));
  return destPath;
}

async function ffprobeStreams(filePath) {
  const { stdout } = await execFileAsync("ffprobe", [
    "-v", "error",
    "-show_entries", "stream=index,codec_type:stream_tags=language,title",
    "-of", "json",
    filePath,
  ]);
  return JSON.parse(stdout).streams ?? [];
}

function languageInfo(raw, index = 0) {
  const original = String(raw ?? "").trim();
  const key = original
    .toLocaleLowerCase("tr-TR")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^a-z0-9]/g, "");

  if (["en", "eng", "english", "ingilizce", "ingilizce"][0] === key || ["en", "eng", "english", "ingilizce"].includes(key)) {
    return { code: "eng", label: "İngilizce" };
  }
  if (["tr", "tur", "turkish", "turkce", "trke", "turkce"].includes(key)) {
    return { code: "tur", label: "Türkçe" };
  }
  if (["de", "deu", "ger", "german", "almanca"].includes(key)) return { code: "deu", label: "Almanca" };
  if (["fr", "fra", "fre", "french", "fransizca"].includes(key)) return { code: "fra", label: "Fransızca" };
  if (["es", "spa", "spanish", "ispanyolca"].includes(key)) return { code: "spa", label: "İspanyolca" };
  return { code: /^[a-z]{3}$/.test(key) ? key : "und", label: original || `Ses ${index + 1}` };
}

async function extractSubtitleAsVtt(inputPath, outPath, streamIndex) {
  const args = ["-y", "-i", inputPath];
  if (streamIndex !== undefined) args.push("-map", `0:${streamIndex}`);
  args.push("-c:s", "webvtt", outPath);
  await execFileAsync("ffmpeg", args);
}

async function remuxSeparate(videoInput, audioInputs, outputPath) {
  const args = ["-y", "-fflags", "+genpts", "-i", videoInput];
  for (const audio of audioInputs) args.push("-i", audio.path);

  args.push("-map", "0:v:0");
  audioInputs.forEach((_, i) => args.push("-map", `${i + 1}:a:0`));
  args.push("-c:v", "copy", "-c:a", "copy");

  audioInputs.forEach((audio, i) => {
    args.push(`-metadata:s:a:${i}`, `language=${audio.info.code}`);
    args.push(`-metadata:s:a:${i}`, `title=${audio.info.label}`);
    args.push(`-disposition:a:${i}`, i === 0 ? "default" : "0");
  });

  args.push("-movflags", "+faststart", "-avoid_negative_ts", "make_zero", outputPath);
  await execFileAsync("ffmpeg", args, { maxBuffer: 16 * 1024 * 1024 });
}

async function remuxCombined(inputPath, streams, outputPath) {
  const audioStreams = streams.filter((s) => s.codec_type === "audio");
  if (audioStreams.length === 0) throw new Error("Birlesik dosyada ses track'i bulunamadi.");

  const args = ["-y", "-i", inputPath, "-map", "0:v:0"];
  audioStreams.forEach((s) => args.push("-map", `0:${s.index}`));
  args.push("-c:v", "copy", "-c:a", "copy");

  audioStreams.forEach((s, i) => {
    const info = languageInfo(s.tags?.language ?? s.tags?.title, i);
    args.push(`-metadata:s:a:${i}`, `language=${info.code}`);
    args.push(`-metadata:s:a:${i}`, `title=${s.tags?.title || info.label}`);
    args.push(`-disposition:a:${i}`, i === 0 ? "default" : "0");
  });

  args.push("-movflags", "+faststart", "-avoid_negative_ts", "make_zero", outputPath);
  await execFileAsync("ffmpeg", args, { maxBuffer: 16 * 1024 * 1024 });
}

async function main() {
  accounts = await resolveAccounts();
  const workDir = await mkdtemp(join(tmpdir(), "film2-remux-"));
  const outDir = join(workDir, "out");
  await mkdir(outDir, { recursive: true });

  const outputVideo = join(outDir, "video.mp4");
  let audioLanguages = [];
  let subtitleLanguages = [];
  let externalSubtitleTracks = [];

  if (mode === "combined") {
    if (!combinedFile) throw new Error("combinedFile eksik.");
    const local = await downloadFromShard(`${incomingPrefix}/${combinedFile}`, join(workDir, combinedFile));
    const streams = await ffprobeStreams(local);
    const audioStreams = streams.filter((s) => s.codec_type === "audio");
    audioLanguages = audioStreams.map((s, i) => languageInfo(s.tags?.language ?? s.tags?.title, i).code);
    await remuxCombined(local, streams, outputVideo);

    const subtitleStreams = streams.filter((s) => s.codec_type === "subtitle");
    for (const [i, s] of subtitleStreams.entries()) {
      const info = languageInfo(s.tags?.language ?? s.tags?.title ?? `sub${i}`, i);
      const name = `subs_${info.code}_${i + 1}.vtt`;
      await extractSubtitleAsVtt(local, join(outDir, name), s.index);
      subtitleLanguages.push(info.code);
      externalSubtitleTracks.push({ language: info.code, label: info.label, __outName: name, mimeType: "text/vtt" });
    }
  } else {
    if (!videoFile) throw new Error("videoFile eksik.");
    const videoLocal = await downloadFromShard(`${incomingPrefix}/${videoFile}`, join(workDir, videoFile));
    const audioInputs = [];

    let index = 0;
    for (const [lang, relPath] of Object.entries(audioFiles)) {
      const info = languageInfo(lang, index);
      const ext = extname(relPath) || ".aac";
      const local = await downloadFromShard(`${incomingPrefix}/${relPath}`, join(workDir, `audio_${index}${ext}`));
      audioInputs.push({ path: local, info });
      index++;
    }
    if (audioInputs.length === 0) throw new Error("Separate modda en az bir ses dosyasi gerekli.");
    audioLanguages = audioInputs.map((a) => a.info.code);
    await remuxSeparate(videoLocal, audioInputs, outputVideo);

    index = 0;
    for (const [lang, relPath] of Object.entries(subtitleFiles)) {
      const info = languageInfo(lang, index);
      subtitleLanguages.push(info.code);
      externalSubtitleTracks.push({
        language: info.code,
        label: info.label,
        url: resolveUrl(shardId, `${incomingPrefix}/${relPath}`),
        mimeType: relPath.toLowerCase().endsWith(".srt") ? "application/x-subrip" : "text/vtt",
      });
      index++;
    }
  }

  const registry = await loadShardRegistry(SHARDS_JSON);
  const repoPrefix = kind === "episode" ? `media/${titleId}/s${seasonNumber}e${episodeNumber}` : `media/${titleId}`;
  const { shard: targetShard, registry: updatedRegistry } = await uploadDirectoryWithFailover({
    localDir: outDir,
    repoPrefix,
    registry,
    accounts,
  });
  await saveShardRegistry(SHARDS_JSON, updatedRegistry);

  externalSubtitleTracks = externalSubtitleTracks.map((track) => {
    if (!track.__outName) return track;
    const { __outName, ...rest } = track;
    return { ...rest, url: resolveUrl(targetShard.id, `${repoPrefix}/${__outName}`) };
  });

  const asset = {
    videoUrl: resolveUrl(targetShard.id, `${repoPrefix}/video.mp4`),
    masterPlaylistUrl: null,
    audioLanguages,
    subtitleLanguages,
    externalAudioTracks: [],
    externalSubtitleTracks,
  };

  const result = { titleId, kind, seasonNumber, episodeNumber, shardId: targetShard.id, asset };
  console.log(JSON.stringify(result, null, 2));
  if (process.env.GITHUB_OUTPUT) {
    await writeFile(process.env.GITHUB_OUTPUT, `result=${JSON.stringify(result)}\n`, { flag: "a" });
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
