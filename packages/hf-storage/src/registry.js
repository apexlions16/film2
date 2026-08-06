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

export function namespaceOf(shardId) {
  return shardId.split("/")[0];
}

function nextShardIdForNamespace(registry, namespace) {
  const prefixMatch = `${namespace}/${registry.prefix}-`;
  const numbers = registry.shards
    .filter((s) => s.id.startsWith(prefixMatch))
    .map((s) => {
      const match = s.id.match(/-(\d+)$/);
      return match ? Number(match[1]) : 0;
    });
  const next = Math.max(0, ...numbers) + 1;
  return `${prefixMatch}${String(next).padStart(2, "0")}`;
}

/**
 * Hugging Face'in "depolama kotasi doldu" hatasini tanir. Kotayi asan bir hesapta
 * yeni repo acmaya ya da dosya yuklemeye calisinca HF bu sekilde hata dondurur.
 * Bu, "bu hesap tukendi, siradaki kayitli hesaba gec" sinyalidir.
 * @param {unknown} error
 */
export function isQuotaExceededError(error) {
  const statusCode = /** @type {any} */ (error)?.statusCode ?? /** @type {any} */ (error)?.status;
  const message = String(/** @type {any} */ (error)?.message ?? "").toLowerCase();
  return (
    statusCode === 402 ||
    statusCode === 403 ||
    message.includes("quota") ||
    message.includes("storage limit") ||
    message.includes("storage quota") ||
    message.includes("payment required")
  );
}

/**
 * Aktif shard doluluk esigini astiysa (ya da `options.force` verilmisse, orn. gercek
 * bir yukleme kota hatasi alindiginda) yeni bir Hugging Face dataset repo acar.
 *
 * `accounts` kullanicinin (Studio ayarlarindan) kayitli tum Hugging Face hesaplarinin
 * ONCELIK SIRALI listesidir: `[{ namespace: "mfilms12", token: "hf_..." }, ...]`.
 * Once aktif shard'in bulundugu hesapta devam etmeye calisir (ayni hesap altinda yeni
 * bir repo). O hesap da kota hatasi verirse (isQuotaExceededError), listede SIRADAKI
 * FARKLI Hugging Face hesabina gecer ve orada ilk shard'i acar. Hicbir kayitli hesap
 * musait degilse acik, eyleme donusturulebilir bir hata firlatir — Studio bunu
 * kullaniciya "yeni bir Hugging Face hesabi ekleyin" seklinde gostermeli.
 *
 * Registry nesnesini mutasyona ugratir ve dondurur — caginan taraf saveShardRegistry/
 * putJsonFile ile commit etmeli.
 *
 * @param {import("@film2/catalog-schema/src/types").ShardRegistry} registry
 * @param {{ namespace: string, token: string }[]} accounts
 * @param {{ isPrivate?: boolean, force?: boolean }} [options]
 */
export async function ensureShardCapacity(registry, accounts, options = {}) {
  const active = getActiveShard(registry);
  if (!options.force && active.usedBytesApprox < registry.sizeThresholdBytes) {
    return { registry, created: false, shard: active, rotatedAccount: false };
  }

  if (!accounts || accounts.length === 0) {
    throw new Error(
      "Kayitli hicbir Hugging Face hesabi yok. Studio Ayarlar ekranindan en az bir hesap eklemelisiniz.",
    );
  }

  const activeNamespace = namespaceOf(active.id);
  const orderedAccounts = [
    ...accounts.filter((a) => a.namespace === activeNamespace),
    ...accounts.filter((a) => a.namespace !== activeNamespace),
  ];

  let lastError;
  for (const account of orderedAccounts) {
    const newId = nextShardIdForNamespace(registry, account.namespace);
    try {
      await createRepo({
        repo: { type: "dataset", name: newId },
        accessToken: account.token,
        private: options.isPrivate ?? false,
      });
    } catch (err) {
      lastError = err;
      if (isQuotaExceededError(err)) continue; // bu hesap dolu, siradakine gec
      throw err; // baska (auth/network/vb) gercek bir hata — sessizce atlanmamali
    }

    active.active = false;
    const newShard = {
      id: newId,
      repoType: "dataset",
      active: true,
      usedBytesApprox: 0,
      createdAt: new Date().toISOString(),
    };
    registry.shards.push(newShard);

    return { registry, created: true, shard: newShard, rotatedAccount: account.namespace !== activeNamespace };
  }

  throw new Error(
    "Tum kayitli Hugging Face hesaplari dolu (ya da erisim hatasi verdi). " +
      "Studio Ayarlar ekranindan yeni bir Hugging Face hesabi ekleyin." +
      (lastError ? ` Son hata: ${lastError.message}` : ""),
  );
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
