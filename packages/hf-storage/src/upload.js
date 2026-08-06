import { readdir, readFile, stat } from "node:fs/promises";
import { join, relative, sep } from "node:path";
import { uploadFiles } from "@huggingface/hub";

/**
 * Hugging Face dataset repo'sundaki bir dosyaya cozumlenmis (resolve) mutlak URL uretir.
 * Player uygulamalari bu URL'i dogrudan hls.js / ExoPlayer'a verir.
 * @param {string} shardId "owner/repo-name"
 * @param {string} pathInRepo
 */
export function resolveUrl(shardId, pathInRepo) {
  return `https://huggingface.co/datasets/${shardId}/resolve/main/${pathInRepo}`;
}

async function collectFiles(localDir) {
  const entries = await readdir(localDir, { withFileTypes: true, recursive: true });
  const files = [];
  for (const entry of entries) {
    if (entry.isFile()) {
      const full = join(entry.parentPath ?? entry.path, entry.name);
      files.push(full);
    }
  }
  return files;
}

/**
 * Yerel bir klasoru (orn. bir HLS ciktisi: master.m3u8 + segmentler + vtt) oldugu gibi
 * bir shard altinda repoPrefix altina yukler.
 *
 * @param {object} params
 * @param {string} params.localDir Yuklenecek yerel klasor
 * @param {string} params.repoPrefix Repo icindeki hedef klasor, orn. "media/kayip-sehir-1234"
 * @param {string} params.shardId "owner/repo-name"
 * @param {string} params.token HF write token
 * @returns {Promise<{ totalBytes: number, uploadedPaths: string[] }>}
 */
export async function uploadDirectoryToShard({ localDir, repoPrefix, shardId, token }) {
  const localFiles = await collectFiles(localDir);
  let totalBytes = 0;
  const files = [];
  const uploadedPaths = [];

  for (const localPath of localFiles) {
    const relPath = relative(localDir, localPath).split(sep).join("/");
    const repoPath = `${repoPrefix}/${relPath}`;
    const buffer = await readFile(localPath);
    totalBytes += buffer.byteLength;
    files.push({ path: repoPath, content: new Blob([buffer]) });
    uploadedPaths.push(repoPath);
  }

  if (files.length === 0) {
    return { totalBytes: 0, uploadedPaths: [] };
  }

  await uploadFiles({
    repo: { type: "dataset", name: shardId },
    accessToken: token,
    files,
  });

  return { totalBytes, uploadedPaths };
}

/**
 * Tek bir dosyayi yukler (orn. Studio'dan gelen ham video/ses/altyazi dosyasi).
 *
 * @param {object} params
 * @param {string} params.localPath
 * @param {string} params.repoPath Repo icindeki hedef yol, orn. "incoming/kayip-sehir-1234/video.mkv"
 * @param {string} params.shardId
 * @param {string} params.token
 * @returns {Promise<{ bytes: number, url: string }>}
 */
export async function uploadFileToShard({ localPath, repoPath, shardId, token }) {
  const info = await stat(localPath);
  const buffer = await readFile(localPath);

  await uploadFiles({
    repo: { type: "dataset", name: shardId },
    accessToken: token,
    files: [{ path: repoPath, content: new Blob([buffer]) }],
  });

  return { bytes: info.size, url: resolveUrl(shardId, repoPath) };
}
