import { ensureShardCapacity, getActiveShard, namespaceOf, recordUsage, isQuotaExceededError } from "./registry.js";
import { uploadFileToShard, uploadDirectoryToShard, uploadFilesToShard } from "./upload.js";

function tokenFor(accounts, namespace) {
  const account = accounts.find((a) => a.namespace === namespace);
  if (!account) {
    throw new Error(`"${namespace}" hesabi icin kayitli bir Hugging Face token'i yok. Studio Ayarlar ekranindan ekleyin.`);
  }
  return account.token;
}

export async function uploadFileWithFailover({ localPath, repoPath, registry, accounts }) {
  const shard = getActiveShard(registry);
  const token = tokenFor(accounts, namespaceOf(shard.id));

  try {
    const result = await uploadFileToShard({ localPath, repoPath, shardId: shard.id, token });
    recordUsage(registry, shard.id, result.bytes);
    return { ...result, shard, registry, rotatedAccount: false };
  } catch (err) {
    if (!isQuotaExceededError(err)) throw err;
    const { shard: newShard, rotatedAccount } = await ensureShardCapacity(registry, accounts, { force: true });
    const newToken = tokenFor(accounts, namespaceOf(newShard.id));
    const result = await uploadFileToShard({ localPath, repoPath, shardId: newShard.id, token: newToken });
    recordUsage(registry, newShard.id, result.bytes);
    return { ...result, shard: newShard, registry, rotatedAccount };
  }
}

/** Tek title'a ait final MP4 + altyazilari tek Hub commit'inde gonderir. */
export async function uploadFilesWithFailover({ files, registry, accounts }) {
  const shard = getActiveShard(registry);
  const token = tokenFor(accounts, namespaceOf(shard.id));

  try {
    const result = await uploadFilesToShard({ files, shardId: shard.id, token });
    recordUsage(registry, shard.id, result.totalBytes);
    return { ...result, shard, registry, rotatedAccount: false };
  } catch (err) {
    if (!isQuotaExceededError(err)) throw err;
    const { shard: newShard, rotatedAccount } = await ensureShardCapacity(registry, accounts, { force: true });
    const newToken = tokenFor(accounts, namespaceOf(newShard.id));
    const result = await uploadFilesToShard({ files, shardId: newShard.id, token: newToken });
    recordUsage(registry, newShard.id, result.totalBytes);
    return { ...result, shard: newShard, registry, rotatedAccount };
  }
}

export async function uploadDirectoryWithFailover({ localDir, repoPrefix, registry, accounts }) {
  const shard = getActiveShard(registry);
  const token = tokenFor(accounts, namespaceOf(shard.id));

  try {
    const result = await uploadDirectoryToShard({ localDir, repoPrefix, shardId: shard.id, token });
    recordUsage(registry, shard.id, result.totalBytes);
    return { ...result, shard, registry, rotatedAccount: false };
  } catch (err) {
    if (!isQuotaExceededError(err)) throw err;
    const { shard: newShard, rotatedAccount } = await ensureShardCapacity(registry, accounts, { force: true });
    const newToken = tokenFor(accounts, namespaceOf(newShard.id));
    const result = await uploadDirectoryToShard({ localDir, repoPrefix, shardId: newShard.id, token: newToken });
    recordUsage(registry, newShard.id, result.totalBytes);
    return { ...result, shard: newShard, registry, rotatedAccount };
  }
}
