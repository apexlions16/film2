#!/usr/bin/env node
// package-media.yml workflow'unun calistirdigi script.
// Studio uygulamasinin repository_dispatch ile gonderdigi ham dosyalari Hugging Face'ten
// indirir, ffmpeg ile coklu ses track + WebVTT altyazi destekli HLS'e paketler, sonucu
// ayni (ya da gerekirse yeni) shard'a yukler ve catalog/titles/{id}.json'u gunceller.
//
// Beklenen ortam degiskenleri:
//   HF_TOKEN — tek hesaplik kurulum (varsayilan, bu proje simdilik boyle)
//   HF_ACCOUNTS_JSON — coklu Hugging Face hesabi: '[{"namespace":"...","token":"hf_..."}, ...]'
//     Herhangi bir hesabin depolama kotasi dolarsa pipeline otomatik olarak listedeki
//     siradaki hesaba gecer (bkz. packages/hf-storage/src/failover.js). HF_TOKEN'in
//     ait oldugu hesap bu listede yoksa otomatik olarak listeye eklenir.
// Beklenen girdi: process.argv[2] = repository_dispatch client_payload'un JSON string'i
// (workflow bunu `github.event.client_payload` uzerinden gecirir)

import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { createWriteStream } from "node:fs";
import { Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
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

/** @returns {Promise<{ namespace: string, token: string }[]>} */
async function resolveAccounts() {
  const accounts = HF_ACCOUNTS_JSON ? JSON.parse(HF_ACCOUNTS_JSON) : [];
  if (HF_TOKEN && !accounts.some((a) => a.token === HF_TOKEN)) {
    const { namespace } = await resolveHfAccount(HF_TOKEN);
    accounts.push({ namespace, token: HF_TOKEN });
  }
  if (accounts.length === 0) {
    throw new Error("Kullanilabilir hicbir Hugging Face hesabi cozumlenemedi.");
  }
  return accounts;
}

const payload = JSON.parse(process.argv[2] ?? "{}");
const { titleId, kind, seasonNumber, episodeNumber, shardId, mode, incomingPrefix, combinedFile, videoFile, audioFiles = {}, subtitleFiles = {} } = payload;

if (!titleId || !shardId || !incomingPrefix || !mode) {
  console.error("Eksik payload alanlari:", payload);
  process.exit(1);
}

/** @type {{ namespace: string, token: string }[]} */
let accounts = [];

function tokenForNamespace(namespace) {
  const account = accounts.find((a) => a.namespace === namespace);
  if (!account) {
    throw new Error(`"${namespace}" hesabi icin bir Hugging Face token'i bulunamadi (HF_ACCOUNTS_JSON'a eklenmemis).`);
  }
  return account.token;
}

// NOT: buyuk film dosyalarini (birkac GB) res.arrayBuffer() ile tek seferde bellege
// almak Actions runner'inda hafiza tasmasina yol acabilir — bunun yerine yanit govdesi
// dogrudan diske akitiliyor (stream), bellek kullanimi dosya boyutundan bagimsiz kaliyor.
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
    "-show_entries", "stream=index,codec_type:stream_tags=language",
    "-of", "json",
    filePath,
  ]);
  const parsed = JSON.parse(stdout);
  return parsed.streams ?? [];
}

// NOT: ilk versiyon "v:0,a:N,agroup:aud" (video her ses icin TEKRARLANIYOR) pattern'i
// kullaniyordu. Bu YANLIS: ffmpeg ayni video stream'i birden fazla variant'ta gorunce
// "Same elementary stream found more than once" hatasiyla direkt REDDEDIYOR — coklu
// sesli gercek bir dosyayla yerelde denenip dogrulandi. Dogru HLS "alternate audio"
// yapisi: TEK video-only variant + her dil icin AYRI, audio-only bir "agroup" uyesi.
// hls.js/ExoPlayer'in track-secici API'leri (audioTracks/audioTrack) sadece bu yapiyi
// (EXT-X-MEDIA:TYPE=AUDIO satirlari) tanir — onceki yapi bunlari ayri "kalite seviyesi"
// sanardi, ses degistirme hic calismazdi. Yerelde sentetik 2 dilli bir dosyayla
// (Turkce/Ingilizce) uretilen master.m3u8 dogru EXT-X-MEDIA satirlarini urettigi
// dogrulandi.
function buildVarStreamMap(audioInputs) {
  const parts = audioInputs.map((audio, i) => {
    const nameTag = (audio.lang || `trk${i}`).replace(/[^a-zA-Z0-9]/g, "").slice(0, 12) || `trk${i}`;
    const defaultFlag = i === 0 ? ",default:yes" : "";
    return `a:${i},agroup:aud,name:${nameTag},language:${audio.lang || "und"}${defaultFlag}`;
  });
  parts.push("v:0,agroup:aud");
  return parts.join(" ");
}

async function muxToHls({ workDir, videoInput, audioInputs, outDir }) {
  await mkdir(outDir, { recursive: true });
  const args = ["-y"];

  // Girdi dosyalarini tekillestir: "combined" modda tum ses track'leri AYNI dosyadan
  // (videoInput) farkli stream index'leriyle gelir — o dosyayi ffmpeg'e birden fazla
  // kez ayri -i olarak vermek (eskiden oldugu gibi) her seferinde stream a:0'i (ilk ses
  // track'ini) seciyordu, yani tum diller yanlislikla AYNI sesi alıyordu. "separate"
  // modda ise her ses ayri bir dosyadir.
  const inputPaths = [videoInput];
  const audioInputIndex = audioInputs.map((audio) => {
    let idx = inputPaths.indexOf(audio.path);
    if (idx === -1) {
      inputPaths.push(audio.path);
      idx = inputPaths.length - 1;
    }
    return idx;
  });
  for (const p of inputPaths) args.push("-i", p);

  args.push("-map", "0:v:0");
  audioInputs.forEach((audio, i) => {
    const inputIdx = audioInputIndex[i];
    // combined modda audio.streamIndex o dosyadaki KESIN stream numarasidir (orn. 0:2);
    // separate modda her ses kendi ayri dosyasinin ilk (ve tek) ses stream'idir (N:a:0).
    const selector = audio.streamIndex !== undefined ? `${inputIdx}:${audio.streamIndex}` : `${inputIdx}:a:0`;
    args.push("-map", selector);
  });

  args.push(
    "-c:v", "copy",
    "-c:a", "copy",
    "-f", "hls",
    "-hls_time", "6",
    "-hls_playlist_type", "vod",
    "-hls_flags", "independent_segments",
    "-hls_segment_type", "mpegts",
    "-master_pl_name", "master.m3u8",
    "-var_stream_map", buildVarStreamMap(audioInputs),
    join(outDir, "variant_%v", "stream.m3u8"),
  );

  await execFileAsync("ffmpeg", args, { cwd: workDir });
}

async function extractSubtitleAsVtt(inputPath, outPath, streamIndex) {
  const args = ["-y", "-i", inputPath];
  if (streamIndex !== undefined) args.push("-map", `0:${streamIndex}`);
  args.push(outPath);
  await execFileAsync("ffmpeg", args);
}

/** ffmpeg'in urettigi master.m3u8'e altyazi EXT-X-MEDIA satirlarini ve SUBTITLES grubunu ekler. */
async function patchMasterWithSubtitles(masterPath, subtitleLangs) {
  if (subtitleLangs.length === 0) return;
  let content = await readFile(masterPath, "utf-8");

  const mediaLines = subtitleLangs
    .map(
      (lang, i) =>
        `#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="subs",NAME="${lang}",LANGUAGE="${lang}",AUTOSELECT=${i === 0 ? "YES" : "NO"},DEFAULT=${i === 0 ? "YES" : "NO"},URI="subs_${lang}.vtt"`,
    )
    .join("\n");

  content = content.replace(
    /(#EXT-X-STREAM-INF:[^\n]*)/g,
    (line) => (line.includes("SUBTITLES=") ? line : `${line},SUBTITLES="subs"`),
  );

  content = content.replace("#EXT-X-VERSION:", `${mediaLines}\n#EXT-X-VERSION:`);
  await writeFile(masterPath, content, "utf-8");
}

async function main() {
  accounts = await resolveAccounts();

  const workDir = await mkdtemp(join(tmpdir(), "film2-package-"));
  const outDir = join(workDir, "out");
  await mkdir(outDir, { recursive: true });

  let videoLocal;
  const audioInputs = [];
  const subtitleLangs = [];

  if (mode === "combined") {
    videoLocal = await downloadFromShard(`${incomingPrefix}/${combinedFile}`, join(workDir, combinedFile));
    const streams = await ffprobeStreams(videoLocal);
    const audioStreams = streams.filter((s) => s.codec_type === "audio");
    audioStreams.forEach((s, i) => audioInputs.push({ path: videoLocal, lang: s.tags?.language ?? `trk${i}`, streamIndex: s.index }));
    const subStreams = streams.filter((s) => s.codec_type === "subtitle");
    for (const [i, s] of subStreams.entries()) {
      const lang = s.tags?.language ?? `sub${i}`;
      await extractSubtitleAsVtt(videoLocal, join(outDir, `subs_${lang}.vtt`), s.index);
      subtitleLangs.push(lang);
    }
  } else {
    videoLocal = await downloadFromShard(`${incomingPrefix}/${videoFile}`, join(workDir, videoFile));
    for (const [lang, relPath] of Object.entries(audioFiles)) {
      const local = await downloadFromShard(`${incomingPrefix}/${relPath}`, join(workDir, `audio_${lang}${relPath.slice(relPath.lastIndexOf("."))}`));
      audioInputs.push({ path: local, lang });
    }
    for (const [lang, relPath] of Object.entries(subtitleFiles)) {
      const local = await downloadFromShard(`${incomingPrefix}/${relPath}`, join(workDir, `subs_${lang}${relPath.slice(relPath.lastIndexOf("."))}`));
      await extractSubtitleAsVtt(local, join(outDir, `subs_${lang}.vtt`));
      subtitleLangs.push(lang);
    }
  }

  if (audioInputs.length === 0) {
    throw new Error("En az bir ses track'i gerekli (combined dosyada bulunamadi ya da separate modda audioFiles bos).");
  }

  await muxToHls({ workDir, videoInput: videoLocal, audioInputs, outDir });
  await patchMasterWithSubtitles(join(outDir, "master.m3u8"), subtitleLangs);

  // Aktif shard'a yukler; shard doluysa (ya da HF gercek bir kota hatasi verirse)
  // otomatik olarak siradaki kayitli Hugging Face hesabina/shard'ina geçer.
  const registry = await loadShardRegistry(SHARDS_JSON);
  const repoPrefix = kind === "episode" ? `media/${titleId}/s${seasonNumber}e${episodeNumber}` : `media/${titleId}`;
  const { shard: targetShard, registry: updatedRegistry, rotatedAccount } = await uploadDirectoryWithFailover({
    localDir: outDir,
    repoPrefix,
    registry,
    accounts,
  });
  if (rotatedAccount) {
    console.log(`Hesap dolu, yeni Hugging Face hesabina gecildi: ${targetShard.id}`);
  }
  await saveShardRegistry(SHARDS_JSON, updatedRegistry);

  const masterPlaylistUrl = resolveUrl(targetShard.id, `${repoPrefix}/master.m3u8`);
  const audioLanguages = audioInputs.map((a) => a.lang);

  const result = {
    titleId,
    kind,
    seasonNumber,
    episodeNumber,
    shardId: targetShard.id,
    asset: { masterPlaylistUrl, audioLanguages, subtitleLanguages: subtitleLangs },
  };
  console.log(JSON.stringify(result, null, 2));

  if (process.env.GITHUB_OUTPUT) {
    await writeFile(process.env.GITHUB_OUTPUT, `result=${JSON.stringify(result)}\n`, { flag: "a" });
  }
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
