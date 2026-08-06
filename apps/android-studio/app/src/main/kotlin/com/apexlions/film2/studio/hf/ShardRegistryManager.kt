package com.apexlions.film2.studio.hf

import com.apexlions.film2.studio.catalog.ShardEntry
import com.apexlions.film2.studio.catalog.ShardRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/**
 * Kotlin port of packages/hf-storage/src/registry.js's capacity logic. The registry
 * (catalog/shards.json) itself is read/written through GitHubContentsClient — this class
 * only owns the "is the active shard full, and if so create a new Hugging Face dataset
 * repo for the next one" decision plus usage bookkeeping.
 */
class ShardRegistryManager(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getActiveShard(registry: ShardRegistry): ShardEntry =
        registry.shards.firstOrNull { it.active }
            ?: throw IllegalStateException("shards.json icinde aktif shard yok — registry bozuk olabilir")

    private fun nextShardId(registry: ShardRegistry): String {
        val numbers = registry.shards.map { shard ->
            SHARD_NUMBER_SUFFIX.find(shard.id)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }
        val next = (numbers.maxOrNull() ?: 0) + 1
        return "${registry.namespace}/${registry.prefix}-${next.toString().padStart(2, '0')}"
    }

    /**
     * If the active shard is at/over the size threshold, creates a new Hugging Face
     * dataset repo, deactivates the old shard, and returns an updated registry with the
     * new shard active. Otherwise returns the registry unchanged. Caller is responsible
     * for persisting the result via GitHubContentsClient.putShardRegistry.
     */
    suspend fun ensureCapacity(registry: ShardRegistry, hfToken: String): ShardRegistry {
        val active = getActiveShard(registry)
        if (active.usedBytesApprox < registry.sizeThresholdBytes) return registry

        val newId = nextShardId(registry)
        createDatasetRepo(newId, hfToken)

        val updatedShards = registry.shards.map { if (it.id == active.id) it.copy(active = false) else it } +
            ShardEntry(
                id = newId,
                repoType = "dataset",
                active = true,
                usedBytesApprox = 0,
                createdAt = Instant.now().toString(),
            )
        return registry.copy(shards = updatedShards)
    }

    /** Adds bytesAdded to the given shard's usage counter, returning an updated registry. */
    fun recordUsage(registry: ShardRegistry, shardId: String, bytesAdded: Long): ShardRegistry {
        val updatedShards = registry.shards.map { shard ->
            if (shard.id == shardId) shard.copy(usedBytesApprox = shard.usedBytesApprox + bytesAdded) else shard
        }
        return registry.copy(shards = updatedShards)
    }

    @Serializable
    private data class CreateRepoRequest(
        val type: String = "dataset",
        val name: String,
        val private: Boolean = false,
    )

    private suspend fun createDatasetRepo(repoId: String, hfToken: String) = withContext(Dispatchers.IO) {
        // repoId is "namespace/name" — the create-repo API wants the bare name plus
        // an implicit "organization" derived from the namespace when it isn't the
        // token owner's own username. We pass the full "namespace/name" as `name`,
        // which Hugging Face's API accepts (it splits on the last `/`).
        val body = json.encodeToString(CreateRepoRequest.serializer(), CreateRepoRequest(name = repoId))
        val request = Request.Builder()
            .url("https://huggingface.co/api/repos/create")
            .header("Authorization", "Bearer $hfToken")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HfUploadException(
                    "Yeni Hugging Face shard repo'su olusturulamadi ($repoId): ${response.code} ${response.body?.string().orEmpty()}",
                )
            }
        }
    }

    companion object {
        private val SHARD_NUMBER_SUFFIX = Regex("-(\\d+)$")
    }
}
