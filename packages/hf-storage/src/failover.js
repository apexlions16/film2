import { ensureShardCapacity, getActiveShard, namespaceOf, recordUsage, isQuotaExceededError } from "./registry.js";
import { uploadFileToShard, uploadDirectoryToShard } from "./upload.js";

function tokenFor(accounts, namespace) {
  const account = accounts.find((a) => a.namespace === namespace);
  if (!account) {
    throw new Error(
      `"${namespace}" hesabi icin kayitli bir Hugging Face token'i yok. Studio Ayarlar ekranindan ekleyin.`,
    );
  }
  return account.token;
}

/**
 * uploadFileToShard'in hataya-dayanikli hali: aktif shard'a yukler, HF gercek bir
 * "kota doldu" hatasi dondurursebul (yalnizca bizim yaklasik byte sayacimiz degil,
 * gercek API yaniti) otomatik olarak siradaki kayitli Hugging Face hesabina/shard'ina
 * gecer ve yuklemeyi orada tekrar dener. Registry'i mutasyona ugratir (usage +
 * gerekirse yeni shard) — caginan taraf registry'i commit etmeli.
 *
 * @param {object} params
 * @param {string} params.localPath
 * @param {string} params.repoPath
 * @param {import("@film2/catalog-schema/src/types").ShardRegistry} params.registry
 * @param {{ namespace: string, token: string }[]} params.accounts
 */
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

/**
 * uploadDirectoryToShard'in hataya-dayanikli hali — bkz. uploadFileWithFailover.
 *
 * @param {object} params
 * @param {string} params.localDir
 * @param {string} params.repoPrefix
 * @param {import("@film2/catalog-schema/src/types").ShardRegistry} params.registry
 * @param {{ namespace: string, token: string }[]} params.accounts
 */
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
