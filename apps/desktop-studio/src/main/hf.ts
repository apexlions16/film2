import { basename, extname } from "node:path";
import {
  ensureShardCapacity,
  getActiveShard,
  uploadFileWithFailover,
  uploadFilesWithFailover,
} from "@film2/hf-storage";
import type {
  DispatchPackageMediaPayload,
  ExternalMediaTrack,
  PlayableAsset,
  ShardRegistry,
  Title,
  UploadFileSelection,
  UploadProgressEvent,
  UploadTarget,
} from "@shared/types";
import { dispatchWorkflow, getJsonFile, putJsonFile } from "./github";
import { getSettings, getHfAccountsWithTokens } from "./settings";
import { getTitle, saveTitle } from "./catalog";
import { prepareFastMedia } from "./remux";

const SHARDS_PATH = "catalog/shards.json";
const PACKAGE_MEDIA_WORKFLOW = "package-media.yml";

function incomingPrefixFor(target: UploadTarget): string {
  return target.kind === "episode"
    ? `incoming/${target.titleId}/s${target.seasonNumber}e${target.episodeNumber}`
    : `incoming/${target.titleId}`;
}

function mediaPrefixFor(target: UploadTarget): string {
  return target.kind === "episode"
    ? `media/${target.titleId}/s${target.seasonNumber}e${target.episodeNumber}`
    : `media/${target.titleId}`;
}

function normalizeLanguage(value: string): string {
  const key = value
    .trim()
    .toLocaleLowerCase("tr-TR")
    .replaceAll("ı", "i")
    .replaceAll("ğ", "g")
    .replaceAll("ü", "u")
    .replaceAll("ş", "s")
    .replaceAll("ö", "o")
    .replaceAll("ç", "c")
    .replace(/[^a-z0-9]/g, "");
  if (["en", "eng", "english", "ingilizce"].includes(key)) return "eng";
  if (["tr", "tur", "turkish", "turkce", "trke"].includes(key)) return "tur";
  if (["de", "deu", "ger", "german", "almanca"].includes(key)) return "deu";
  if (["fr", "fra", "fre", "french", "fransizca"].includes(key)) return "fra";
  if (["es", "spa", "spanish", "ispanyolca"].includes(key)) return "spa";
  return key.length === 3 ? key : "und";
}

function languageLabel(code: string): string {
  if (code === "eng") return "İngilizce";
  if (code === "tur") return "Türkçe";
  if (code === "deu") return "Almanca";
  if (code === "fra") return "Fransızca";
  if (code === "spa") return "İspanyolca";
  return code;
}

function subtitleMime(ext: string): string {
  return ext === ".srt" ? "application/x-subrip" : "text/vtt";
}

async function markProcessing(target: UploadTarget): Promise<void> {
  const title = await getTitle(target.titleId);
  if (target.kind === "movie") {
    if (title.status === "ready" && title.asset) return;
    await saveTitle({ ...title, status: "processing" });
    return;
  }

  const seasons = (title.seasons ?? []).map((season) => {
    if (season.seasonNumber !== target.seasonNumber) return season;
    return {
      ...season,
      episodes: season.episodes.map((episode) => {
        if (episode.episodeNumber !== target.episodeNumber) return episode;
        if (episode.status === "ready" && episode.asset) return episode;
        return { ...episode, status: "processing" as const };
      }),
    };
  });
  const anyReady = seasons.some((season) => season.episodes.some((episode) => episode.status === "ready" && episode.asset));
  await saveTitle({ ...title, status: anyReady ? "ready" : "processing", seasons });
}

async function publishReady(
  target: UploadTarget,
  shardId: string,
  asset: PlayableAsset,
): Promise<void> {
  const title = await getTitle(target.titleId);
  if (target.kind === "movie") {
    await saveTitle({ ...title, status: "ready", shardId, asset });
    return;
  }

  const seasons = (title.seasons ?? []).map((season) => {
    if (season.seasonNumber !== target.seasonNumber) return season;
    return {
      ...season,
      episodes: season.episodes.map((episode) => episode.episodeNumber === target.episodeNumber
        ? { ...episode, status: "ready" as const, shardId, asset }
        : episode),
    };
  });
  await saveTitle({ ...title, status: "ready", seasons });
}

async function tryFastPublish(
  uploadId: string,
  target: UploadTarget,
  selection: UploadFileSelection,
  onProgress: (event: UploadProgressEvent) => void,
): Promise<{ uploadId: string; shardId: string; fastPath: boolean } | null> {
  let prepared;
  try {
    prepared = await prepareFastMedia(uploadId, selection, (message) => {
      onProgress({ uploadId, phase: "preparing", completedFiles: 0, totalFiles: 1, percent: 12, message });
    });
  } catch (error) {
    onProgress({
      uploadId,
      phase: "preparing",
      completedFiles: 0,
      totalFiles: 1,
      percent: 8,
      message: `Yerel hızlı mux uygun değil; güvenli GitHub fallback kullanılacak (${error instanceof Error ? error.message.slice(0, 180) : "bilinmeyen hata"}).`,
    });
    return null;
  }
  if (!prepared) return null;

  const { githubToken } = getSettings();
  const accounts = getHfAccountsWithTokens();
  if (!githubToken) throw new Error("GitHub token ayarlanmamis.");
  if (accounts.length === 0) throw new Error("Hugging Face hesabi eklenmemis.");

  const shardsFile = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
  if (!shardsFile) throw new Error("catalog/shards.json bulunamadi.");
  let registry = shardsFile.data;
  ({ registry } = await ensureShardCapacity(registry, accounts));

  const version = Date.now();
  const prefix = mediaPrefixFor(target);
  const videoRepoPath = `${prefix}/video_${version}.mp4`;
  const batchFiles: Array<{ localPath: string; repoPath: string }> = [
    { localPath: prepared.videoPath, repoPath: videoRepoPath },
  ];
  const subtitlePlan = Object.entries(selection.subtitleFiles).map(([rawLanguage, localPath], index) => {
    const language = normalizeLanguage(rawLanguage);
    const ext = extname(localPath).toLowerCase() || ".vtt";
    const repoPath = `${prefix}/subs_${language}_${version}_${index + 1}${ext}`;
    batchFiles.push({ localPath, repoPath });
    return { language, localPath, repoPath, ext };
  });

  try {
    onProgress({
      uploadId,
      phase: "uploading",
      completedFiles: 0,
      totalFiles: batchFiles.length,
      percent: 32,
      message: `Final MP4 + ${subtitlePlan.length} altyazı tek Hugging Face batch commit'inde yükleniyor…`,
    });

    const result = await uploadFilesWithFailover({ files: batchFiles, registry, accounts });
    registry = result.registry;
    const shardId = result.shard.id;

    onProgress({
      uploadId,
      phase: "updating-registry",
      completedFiles: batchFiles.length,
      totalFiles: batchFiles.length,
      percent: 88,
      message: "Shard kaydı güncelleniyor…",
    });
    const latestShards = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
    await putJsonFile(
      SHARDS_PATH,
      registry,
      `chore(shards): ${target.titleId} Windows hızlı yükleme`,
      githubToken,
      latestShards?.sha,
    );

    const subtitleTracks: ExternalMediaTrack[] = subtitlePlan.map((subtitle) => ({
      language: subtitle.language,
      label: languageLabel(subtitle.language),
      mimeType: subtitleMime(subtitle.ext),
      url: result.urls[subtitle.repoPath],
    }));

    const existingTitle = await getTitle(target.titleId);
    let fallbackLanguages: string[] = [];
    if (target.kind === "movie") fallbackLanguages = existingTitle.asset?.audioLanguages ?? [];
    else {
      fallbackLanguages = existingTitle.seasons
        ?.find((season) => season.seasonNumber === target.seasonNumber)
        ?.episodes.find((episode) => episode.episodeNumber === target.episodeNumber)
        ?.asset?.audioLanguages ?? [];
    }

    const asset: PlayableAsset = {
      videoUrl: result.urls[videoRepoPath],
      durationSeconds: undefined,
      audioLanguages: prepared.audioLanguages.length > 0 ? prepared.audioLanguages : fallbackLanguages,
      subtitleLanguages: subtitleTracks.map((track) => track.language),
      externalAudioTracks: [],
      externalSubtitleTracks: subtitleTracks,
      videoVariants: [],
    };

    onProgress({ uploadId, phase: "publishing", completedFiles: batchFiles.length, totalFiles: batchFiles.length, percent: 96, message: "Katalog anında hazır duruma getiriliyor…" });
    await publishReady(target, shardId, asset);

    onProgress({ uploadId, phase: "done", completedFiles: batchFiles.length, totalFiles: batchFiles.length, percent: 100, message: "Hazır: GitHub Actions remux'u atlandı. Player birkaç saniye içinde görecek." });
    return { uploadId, shardId, fastPath: true };
  } finally {
    await prepared.cleanup();
  }
}

type FileRole = "combined" | "video" | { audio: string } | { subtitle: string };
interface PlannedFile { localPath: string; repoFileName: string; role: FileRole; }

function planFiles(selection: UploadFileSelection): PlannedFile[] {
  const planned: PlannedFile[] = [];
  if (selection.mode === "combined") {
    if (!selection.combinedFile) throw new Error("Birlesik dosya secilmedi.");
    planned.push({ localPath: selection.combinedFile, repoFileName: `combined${extname(selection.combinedFile)}`, role: "combined" });
    return planned;
  }
  if (!selection.videoFile) throw new Error("Video dosyasi secilmedi.");
  planned.push({ localPath: selection.videoFile, repoFileName: `video${extname(selection.videoFile)}`, role: "video" });
  for (const [lang, localPath] of Object.entries(selection.audioFiles)) planned.push({ localPath, repoFileName: `audio_${lang}${extname(localPath)}`, role: { audio: lang } });
  for (const [lang, localPath] of Object.entries(selection.subtitleFiles)) planned.push({ localPath, repoFileName: `subs_${lang}${extname(localPath)}`, role: { subtitle: lang } });
  return planned;
}

function isAudioRole(role: FileRole): role is { audio: string } { return typeof role === "object" && "audio" in role; }
function isSubtitleRole(role: FileRole): role is { subtitle: string } { return typeof role === "object" && "subtitle" in role; }

async function uploadServerFallback(
  uploadId: string,
  target: UploadTarget,
  selection: UploadFileSelection,
  onProgress: (event: UploadProgressEvent) => void,
): Promise<{ uploadId: string; shardId: string; fastPath: boolean }> {
  const { githubToken } = getSettings();
  const accounts = getHfAccountsWithTokens();
  if (accounts.length === 0) throw new Error("Hicbir Hugging Face hesabi eklenmemis.");
  if (!githubToken) throw new Error("GitHub token ayarlanmamis.");

  const planned = planFiles(selection);
  const shardsFile = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
  if (!shardsFile) throw new Error("catalog/shards.json GitHub'da bulunamadi.");
  let updatedRegistry = shardsFile.data;
  ({ registry: updatedRegistry } = await ensureShardCapacity(updatedRegistry, accounts));
  const incomingPrefix = incomingPrefixFor(target);
  let targetShard = getActiveShard(updatedRegistry);

  let completed = 0;
  for (const file of planned) {
    const fileName = basename(file.localPath);
    onProgress({ uploadId, phase: "uploading", fileName, completedFiles: completed, totalFiles: planned.length, percent: 15 + Math.round((completed / planned.length) * 58), message: `${fileName} yükleniyor (fallback)…` });
    const uploadResult = await uploadFileWithFailover({ localPath: file.localPath, repoPath: `${incomingPrefix}/${file.repoFileName}`, registry: updatedRegistry, accounts });
    updatedRegistry = uploadResult.registry;
    targetShard = uploadResult.shard;
    completed += 1;
  }

  const latestShards = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
  await putJsonFile(SHARDS_PATH, updatedRegistry, `chore(shards): ${target.titleId} fallback yukleme`, githubToken, latestShards?.sha);

  const audioFiles: Record<string, string> = {};
  const subtitleFiles: Record<string, string> = {};
  let combinedFile: string | undefined;
  let videoFile: string | undefined;
  for (const file of planned) {
    if (file.role === "combined") combinedFile = file.repoFileName;
    else if (file.role === "video") videoFile = file.repoFileName;
    else if (isAudioRole(file.role)) audioFiles[file.role.audio] = file.repoFileName;
    else if (isSubtitleRole(file.role)) subtitleFiles[file.role.subtitle] = file.repoFileName;
  }
  const payload: DispatchPackageMediaPayload = {
    titleId: target.titleId,
    kind: target.kind,
    seasonNumber: target.seasonNumber,
    episodeNumber: target.episodeNumber,
    shardId: targetShard.id,
    mode: selection.mode,
    incomingPrefix,
    combinedFile,
    videoFile,
    audioFiles,
    subtitleFiles,
  };
  onProgress({ uploadId, phase: "dispatching", completedFiles: planned.length, totalFiles: planned.length, percent: 92, message: "Uyumluluk fallback'i: GitHub paketleme workflow'u tetikleniyor…" });
  await dispatchWorkflow(PACKAGE_MEDIA_WORKFLOW, { payload: JSON.stringify(payload) }, githubToken);
  onProgress({ uploadId, phase: "done", completedFiles: planned.length, totalFiles: planned.length, percent: 100, message: "Yükleme tamamlandı; uyumluluk paketlemesi GitHub Actions'ta devam ediyor." });
  return { uploadId, shardId: targetShard.id, fastPath: false };
}

export async function uploadAndDispatch(
  target: UploadTarget,
  selection: UploadFileSelection,
  onProgress: (event: UploadProgressEvent) => void,
): Promise<{ uploadId: string; shardId: string; fastPath: boolean }> {
  const uploadId = `${target.titleId}-${Date.now()}`;
  await markProcessing(target);
  const fast = await tryFastPublish(uploadId, target, selection, onProgress);
  if (fast) return fast;
  return uploadServerFallback(uploadId, target, selection, onProgress);
}
