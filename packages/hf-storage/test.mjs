// Manuel dogrulama: HF_TOKEN ile calistirilir, catalog/shards.json'daki aktif shard'a
// kucuk bir test dosyasi yukler.
// Kullanim: HF_TOKEN=hf_xxx node test.mjs
import { writeFile, mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { loadShardRegistry, getActiveShard, uploadFileToShard } from "./src/index.js";

const token = process.env.HF_TOKEN;
if (!token) {
  console.error("HF_TOKEN ortam degiskeni gerekli.");
  process.exit(1);
}

const registry = await loadShardRegistry(new URL("../../catalog/shards.json", import.meta.url).pathname);
const shard = getActiveShard(registry);
console.log("Aktif shard:", shard.id);

const dir = await mkdtemp(join(tmpdir(), "film2-hf-test-"));
const localPath = join(dir, "hello.txt");
await writeFile(localPath, `film2 test yuklemesi — ${new Date().toISOString()}`);

const result = await uploadFileToShard({
  localPath,
  repoPath: `_selftest/hello-${Date.now()}.txt`,
  shardId: shard.id,
  token,
});

console.log("Yuklendi:", result.url);
