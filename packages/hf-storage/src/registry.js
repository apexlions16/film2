import { readFile, writeFile } from "node:fs/promises";
import { createRepo } from "@huggingface/hub";

/**
 * catalog/shards.json dosyasini okur.
 * @param {string} shardsJsonPath
 * @returns {Promise<import("@film2/catalog-schema/src/types").ShardRegistry>}
 */
export async function loadShardRegistry(shardsJsonPath) {
  const raw = await readFile(shardsJsonPath, "utf-8");
  return JSON.parse(raw);
}

/**
 * catalog/shards.json dosyasini yazar. Caginan taraf degisiklikleri git commit etmeli.
 * @param {string} shardsJsonPath
 * @param {import("@film2/catalog-schema/src/types").ShardRegistry} registry
 */
export async function saveShardRegistry(shardsJsonPath, registry) {
  await writeFile(shardsJsonPath, JSON.stringify(registry, null, 2) + "\n", "utf-8");
}

export function getActiveShard(registry) {
  const active = registry.shards.find((s) => s.active);
  if (!active) throw new Error("shards.json icinde aktif shard yok — registry bozuk olabilir");
  return active;
}

function nextShardId(registry) {
  const numbers = registry.shards.map((s) => {
    const match = s.id.match(/-(\d+)$/);
    return match ? Number(match[1]) : 0;
  });
  const next = Math.max(0, ...numbers) + 1;
  return `${registry.namespace}/${registry.prefix}-${String(next).padStart(2, "0")}`;
}

/**
 * Aktif shard doluluk esigini astiysa yeni bir Hugging Face dataset repo acar,
 * eskisini pasiflestirir ve yeni shard'i aktif yapar. Registry nesnesini mutasyona
 * ugratir ve dondurur — caginan taraf saveShardRegistry + git commit yapmali.
 *
 * @param {import("@film2/catalog-schema/src/types").ShardRegistry} registry
 * @param {string} hfToken
 * @param {{ isPrivate?: boolean }} [options]
 */
export async function ensureShardCapacity(registry, hfToken, options = {}) {
  const active = getActiveShard(registry);
  if (active.usedBytesApprox < registry.sizeThresholdBytes) {
    return { registry, created: false, shard: active };
  }

  const newId = nextShardId(registry);
  await createRepo({
    repo: { type: "dataset", name: newId },
    accessToken: hfToken,
    private: options.isPrivate ?? false,
  });

  active.active = false;
  const newShard = {
    id: newId,
    repoType: "dataset",
    active: true,
    usedBytesApprox: 0,
    createdAt: new Date().toISOString(),
  };
  registry.shards.push(newShard);

  return { registry, created: true, shard: newShard };
}

/**
 * Bir yuklemeden sonra kullanim sayacini gunceller (registry mutasyona ugrar).
 * @param {import("@film2/catalog-schema/src/types").ShardRegistry} registry
 * @param {string} shardId
 * @param {number} bytesAdded
 */
export function recordUsage(registry, shardId, bytesAdded) {
  const shard = registry.shards.find((s) => s.id === shardId);
  if (shard) shard.usedBytesApprox += bytesAdded;
  return registry;
}
