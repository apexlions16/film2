// Ham medya dosyalarini Hugging Face shard'ina yukleme + shards.json guncelleme +
// package-media repository_dispatch tetikleme orkestrasyonu. Hicbir adim GitHub Actions
// isinin BITMESINI beklemez — dispatch atildiktan sonra hemen doner ("İşleniyor…").
import { basename, extname } from "node:path";
import {
  ensureShardCapacity,
  getActiveShard,
  recordUsage,
  uploadFileToShard,
} from "@film2/hf-storage";
import type {
  DispatchPackageMediaPayload,
  ShardRegistry,
  UploadFileSelection,
  UploadProgressEvent,
  UploadTarget,
} from "@shared/types";
import { dispatchRepositoryEvent, getJsonFile, putJsonFile } from "./github";
import { getSettings } from "./settings";

const SHARDS_PATH = "catalog/shards.json";
const DISPATCH_EVENT_TYPE = "package-media";

function incomingPrefixFor(target: UploadTarget): string {
  if (target.kind === "episode") {
    return `incoming/${target.titleId}/s${target.seasonNumber}e${target.episodeNumber}`;
  }
  return `incoming/${target.titleId}`;
}

type FileRole = "combined" | "video" | { audio: string } | { subtitle: string };

interface PlannedFile {
  localPath: string;
  repoFileName: string;
  role: FileRole;
}

function planFiles(selection: UploadFileSelection): PlannedFile[] {
  const planned: PlannedFile[] = [];

  if (selection.mode === "combined") {
    if (!selection.combinedFile) {
      throw new Error("Birlesik dosya secilmedi.");
    }
    planned.push({
      localPath: selection.combinedFile,
      repoFileName: `combined${extname(selection.combinedFile)}`,
      role: "combined",
    });
    return planned;
  }

  if (!selection.videoFile) {
    throw new Error("Video dosyasi secilmedi.");
  }
  planned.push({
    localPath: selection.videoFile,
    repoFileName: `video${extname(selection.videoFile)}`,
    role: "video",
  });

  const audioEntries = Object.entries(selection.audioFiles);
  if (audioEntries.length === 0) {
    throw new Error("En az bir ses dosyasi (dil) secilmeli.");
  }
  for (const [lang, localPath] of audioEntries) {
    planned.push({ localPath, repoFileName: `audio_${lang}${extname(localPath)}`, role: { audio: lang } });
  }
  for (const [lang, localPath] of Object.entries(selection.subtitleFiles)) {
    planned.push({ localPath, repoFileName: `subs_${lang}${extname(localPath)}`, role: { subtitle: lang } });
  }

  return planned;
}

function isAudioRole(role: FileRole): role is { audio: string } {
  return typeof role === "object" && role !== null && "audio" in role;
}

function isSubtitleRole(role: FileRole): role is { subtitle: string } {
  return typeof role === "object" && role !== null && "subtitle" in role;
}

export async function uploadAndDispatch(
  target: UploadTarget,
  selection: UploadFileSelection,
  onProgress: (event: UploadProgressEvent) => void,
): Promise<{ uploadId: string; shardId: string }> {
  const uploadId = `${target.titleId}-${Date.now()}`;
  const { hfToken, githubToken } = getSettings();
  if (!hfToken) throw new Error("Hugging Face token ayarlanmamis. Once Ayarlar ekranindan girin.");
  if (!githubToken) throw new Error("GitHub token ayarlanmamis. Once Ayarlar ekranindan girin.");

  const planned = planFiles(selection);
  const totalFiles = planned.length;

  const shardsFile = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
  if (!shardsFile) {
    throw new Error("catalog/shards.json GitHub'da bulunamadi — repo bozuk olabilir.");
  }
  const registry = shardsFile.data;

  const { registry: updatedRegistry } = await ensureShardCapacity(registry, hfToken);
  const targetShard = getActiveShard(updatedRegistry);
  const incomingPrefix = incomingPrefixFor(target);

  let completed = 0;
  for (const file of planned) {
    const fileName = basename(file.localPath);
    onProgress({
      uploadId,
      phase: "uploading",
      fileName,
      completedFiles: completed,
      totalFiles,
      message: `${fileName} yukleniyor...`,
    });

    const repoPath = `${incomingPrefix}/${file.repoFileName}`;
    const { bytes } = await uploadFileToShard({
      localPath: file.localPath,
      repoPath,
      shardId: targetShard.id,
      token: hfToken,
    });
    recordUsage(updatedRegistry, targetShard.id, bytes);

    completed += 1;
    onProgress({ uploadId, phase: "uploading", fileName, completedFiles: completed, totalFiles });
  }

  onProgress({
    uploadId,
    phase: "updating-registry",
    completedFiles: totalFiles,
    totalFiles,
    message: "catalog/shards.json guncelleniyor...",
  });
  await putJsonFile(
    SHARDS_PATH,
    updatedRegistry,
    `chore(shards): ${target.titleId} yuklemesi sonrasi kullanim guncellendi`,
    githubToken,
    shardsFile.sha,
  );

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

  // .github/scripts/package-media.mjs'in bekledigi client_payload sekliyle birebir eslesir.
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

  onProgress({
    uploadId,
    phase: "dispatching",
    completedFiles: totalFiles,
    totalFiles,
    message: "Paketleme workflow'u tetikleniyor...",
  });
  await dispatchRepositoryEvent(DISPATCH_EVENT_TYPE, payload as unknown as Record<string, unknown>, githubToken);

  // NOT: GitHub Actions isinin bitmesi burada BEKLENMEZ — dispatch atildiktan hemen sonra
  // "done" (yukleme+tetikleme tamam, paketleme arka planda) bildirilir.
  onProgress({
    uploadId,
    phase: "done",
    completedFiles: totalFiles,
    totalFiles,
    message: "Yukleme tamamlandi. Paketleme GitHub Actions'ta arka planda devam ediyor.",
  });

  return { uploadId, shardId: targetShard.id };
}
