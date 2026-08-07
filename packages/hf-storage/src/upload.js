import { readdir, stat } from "node:fs/promises";
import { openAsBlob } from "node:fs";
import { join, relative, sep } from "node:path";
import { uploadFiles } from "@huggingface/hub";

// Node fs.readFile/readFileSync dosyayi tamamen bellege alir ve cok buyuk film
// dosyalarinda gereksiz RAM baskisi yaratir. openAsBlob() tembel/lazy bir Blob verir;
// @huggingface/hub dosyayi diskten akitir.

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
 * Birden fazla yerel dosyayi TEK @huggingface/hub uploadFiles cagrisi/commit'i ile
 * ayni dataset shard'ina yollar. Blob'lar lazy oldugu icin buyuk MP4 RAM'e alinmaz.
 *
 * @param {object} params
 * @param {{localPath:string, repoPath:string}[]} params.files
 * @param {string} params.shardId
 * @param {string} params.token
 */
export async function uploadFilesToShard({ files, shardId, token }) {
  if (!files.length) return { totalBytes: 0, uploadedPaths: [], urls: {} };

  let totalBytes = 0;
  const payload = [];
  const uploadedPaths = [];
  const urls = {};
  for (const file of files) {
    const info = await stat(file.localPath);
    const blob = await openAsBlob(file.localPath);
    totalBytes += info.size;
    payload.push({ path: file.repoPath, content: blob });
    uploadedPaths.push(file.repoPath);
    urls[file.repoPath] = resolveUrl(shardId, file.repoPath);
  }

  await uploadFiles({
    repo: { type: "dataset", name: shardId },
    accessToken: token,
    files: payload,
  });

  return { totalBytes, uploadedPaths, urls };
}

export async function uploadDirectoryToShard({ localDir, repoPrefix, shardId, token }) {
  const localFiles = await collectFiles(localDir);
  const files = [];
  for (const localPath of localFiles) {
    const relPath = relative(localDir, localPath).split(sep).join("/");
    files.push({ localPath, repoPath: `${repoPrefix}/${relPath}` });
  }
  return uploadFilesToShard({ files, shardId, token });
}

export async function uploadFileToShard({ localPath, repoPath, shardId, token }) {
  const result = await uploadFilesToShard({
    files: [{ localPath, repoPath }],
    shardId,
    token,
  });
  return {
    bytes: result.totalBytes,
    url: result.urls[repoPath],
  };
}
