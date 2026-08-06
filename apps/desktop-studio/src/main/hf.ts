// Ham medya dosyalarini Hugging Face shard'ina yukleme + shards.json guncelleme +
// package-media workflow_dispatch tetikleme orkestrasyonu. Hicbir adim GitHub Actions
// isinin BITMESINI beklemez — dispatch atildiktan sonra hemen doner ("İşleniyor…").
import { basename, extname } from "node:path";
import { ensureShardCapacity, getActiveShard, uploadFileWithFailover } from "@film2/hf-storage";
import type {
  DispatchPackageMediaPayload,
  ShardRegistry,
  UploadFileSelection,
  UploadProgressEvent,
  UploadTarget,
} from "@shared/types";
import { dispatchWorkflow, getJsonFile, putJsonFile } from "./github";
import { getSettings, getHfAccountsWithTokens } from "./settings";

const SHARDS_PATH = "catalog/shards.json";
const PACKAGE_MEDIA_WORKFLOW = "package-media.yml";

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
  const { githubToken } = getSettings();
  const accounts = getHfAccountsWithTokens();
  if (accounts.length === 0) {
    throw new Error("Hicbir Hugging Face hesabi eklenmemis. Once Ayarlar ekranindan en az bir hesap ekleyin.");
  }
  if (!githubToken) throw new Error("GitHub token ayarlanmamis. Once Ayarlar ekranindan girin.");

  const planned = planFiles(selection);
  const totalFiles = planned.length;

  const shardsFile = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
  if (!shardsFile) {
    throw new Error("catalog/shards.json GitHub'da bulunamadi — repo bozuk olabilir.");
  }
  let updatedRegistry = shardsFile.data;

  // Kapasite onceden (esik bazli) kontrol edilir; asil "hesap gercekten dolu" durumu
  // (HF'nin gercek kota hatasi) her dosya yuklemesinde uploadFileWithFailover
  // tarafindan ayrica yakalanip otomatik siradaki hesaba gecilir.
  ({ registry: updatedRegistry } = await ensureShardCapacity(updatedRegistry, accounts));
  const incomingPrefix = incomingPrefixFor(target);
  let targetShard = getActiveShard(updatedRegistry);

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
    const uploadResult = await uploadFileWithFailover({
      localPath: file.localPath,
      repoPath,
      registry: updatedRegistry,
      accounts,
    });
    updatedRegistry = uploadResult.registry;
    targetShard = uploadResult.shard;
    if (uploadResult.rotatedAccount) {
      onProgress({
        uploadId,
        phase: "uploading",
        fileName,
        completedFiles: completed,
        totalFiles,
        message: `Hesap dolu, farkli bir Hugging Face hesabina gecildi (${targetShard.id}).`,
      });
    }

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

  // NOT (bilinen sinir durum): eger yukleme SIRASINDA hesap rotasyonu olduysa (bir dosya
  // eski shard'a, bir sonraki yeni shard'a gittiyse) burada tek bir shardId gonderiliyor —
  // package-media.mjs tum incoming dosyalarin AYNI shard'da oldugunu varsayiyor. Pratikte
  // bir title'in birkac dosyasi tek seferde yuklendigi icin nadir bir durum; cozmek icin
  // her dosyanin kendi shardId'sini tasiyacak sekilde payload semasini genisletmek gerekir.
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
  await dispatchWorkflow(PACKAGE_MEDIA_WORKFLOW, { payload: JSON.stringify(payload) }, githubToken);

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
