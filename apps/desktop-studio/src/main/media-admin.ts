import { extname } from "node:path";
import { ensureShardCapacity, uploadFileWithFailover } from "@film2/hf-storage";
import type { QualityGenerateRequest, ShardRegistry, TrailerUploadRequest } from "@shared/types";
import { getTitle, saveTitle } from "./catalog";
import { dispatchWorkflow, getJsonFile, putJsonFile } from "./github";
import { getHfAccountsWithTokens, getSettings } from "./settings";

const SHARDS_PATH = "catalog/shards.json";

export async function uploadTrailer(request: TrailerUploadRequest): Promise<{ url: string }> {
  const { githubToken } = getSettings();
  const accounts = getHfAccountsWithTokens();
  if (!githubToken) throw new Error("GitHub token ayarlanmamis.");
  if (accounts.length === 0) throw new Error("Hugging Face hesabi eklenmemis.");

  const ext = extname(request.localPath).toLowerCase();
  if (ext !== ".mp4" && ext !== ".m4v") {
    throw new Error("Trailer icin MP4/M4V secin. Player detay ekraninda progressive video bekliyor.");
  }

  const shardsFile = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
  if (!shardsFile) throw new Error("catalog/shards.json bulunamadi.");
  let registry = shardsFile.data;
  ({ registry } = await ensureShardCapacity(registry, accounts));

  const path = `media/${request.titleId}/trailer_${Date.now()}.mp4`;
  const uploaded = await uploadFileWithFailover({
    localPath: request.localPath,
    repoPath: path,
    registry,
    accounts,
  });
  registry = uploaded.registry;

  const latestRegistry = await getJsonFile<ShardRegistry>(SHARDS_PATH, githubToken);
  await putJsonFile(
    SHARDS_PATH,
    registry,
    `chore(shards): ${request.titleId} trailer (windows-studio)`,
    githubToken,
    latestRegistry?.sha,
  );

  const title = await getTitle(request.titleId);
  await saveTitle({ ...title, trailerUrl: uploaded.url });
  return { url: uploaded.url };
}

export async function generateQualities(request: QualityGenerateRequest): Promise<void> {
  const { githubToken } = getSettings();
  if (!githubToken) throw new Error("GitHub token ayarlanmamis.");
  const title = await getTitle(request.titleId);
  const asset = request.kind === "movie"
    ? title.asset
    : title.seasons
      ?.find((season) => season.seasonNumber === request.seasonNumber)
      ?.episodes.find((episode) => episode.episodeNumber === request.episodeNumber)
      ?.asset;
  if (!asset?.videoUrl) throw new Error("Kalite uretmek icin once direct MP4 medyanin hazir olmasi gerekiyor.");

  const targets = [...new Set(request.heights)]
    .map(Number)
    .filter((height) => Number.isInteger(height) && height >= 240 && height <= 2160)
    .sort((a, b) => b - a);
  if (targets.length === 0) throw new Error("En az bir gecerli kalite secin.");

  await dispatchWorkflow(
    "generate-qualities.yml",
    {
      payload: JSON.stringify({
        titleId: request.titleId,
        kind: request.kind,
        seasonNumber: request.seasonNumber,
        episodeNumber: request.episodeNumber,
        targets,
      }),
    },
    githubToken,
  );
}
