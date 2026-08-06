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

/** A Hugging Face account as needed by the capacity/failover algorithm: just enough to
 *  create repos and upload under it. Kept distinct from settings.HfAccountToken (which
 *  also carries a display fullname) so this layer doesn't depend on the settings layer. */
data class HfAccountEntry(
    val namespace: String,
    val token: String,
)

/** Result of resolving a Hugging Face token via whoami — used by the Studio "add account"
 *  flow so the user only pastes a token and the namespace/fullname are auto-detected. */
data class HfAccountInfo(
    val namespace: String,
    val fullname: String?,
)

data class ShardCapacityResult(
    val registry: ShardRegistry,
    val created: Boolean,
    val shard: ShardEntry,
    val rotatedAccount: Boolean,
)

fun namespaceOf(shardId: String): String = shardId.substringBefore("/")

/**
 * Kotlin port of packages/hf-storage/src/registry.js's isQuotaExceededError. Recognizes
 * Hugging Face's "this account's storage is full" signal: HTTP 402/403, or an error
 * message mentioning quota/storage-limit/payment-required. This is the trigger for
 * automatically failing over to the next registered Hugging Face account.
 */
fun isQuotaExceededError(t: Throwable): Boolean {
    val statusCode = (t as? HfUploadException)?.statusCode
    val message = (t.message ?: "").lowercase()
    return statusCode == 402 ||
        statusCode == 403 ||
        message.contains("quota") ||
        message.contains("storage limit") ||
        message.contains("storage quota") ||
        message.contains("payment required")
}

/**
 * Kotlin port of packages/hf-storage/src/registry.js's capacity logic. The registry
 * (catalog/shards.json) itself is read/written through GitHubContentsClient — this class
 * only owns the "is the active shard full, and if so create a new Hugging Face dataset
 * repo for the next one (rotating to a different registered Hugging Face account if the
 * current one is out of storage)" decision, plus usage bookkeeping and the whoami lookup
 * used by the "add Hugging Face account" settings flow.
 */
class ShardRegistryManager(
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getActiveShard(registry: ShardRegistry): ShardEntry =
        registry.shards.firstOrNull { it.active }
            ?: throw IllegalStateException("shards.json icinde aktif shard yok — registry bozuk olabilir")

    private fun nextShardIdForNamespace(registry: ShardRegistry, namespace: String): String {
        val prefixMatch = "$namespace/${registry.prefix}-"
        val numbers = registry.shards
            .filter { it.id.startsWith(prefixMatch) }
            .map { shard -> SHARD_NUMBER_SUFFIX.find(shard.id)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        val next = (numbers.maxOrNull() ?: 0) + 1
        return "$prefixMatch${next.toString().padStart(2, '0')}"
    }

    /**
     * If the active shard is at/over the size threshold (or `force` is set, e.g. a real
     * upload quota error was just observed), creates a new Hugging Face dataset repo and
     * returns an updated registry with the new shard active. Otherwise returns the
     * registry unchanged.
     *
     * `accounts` is the user's registered Hugging Face accounts, in priority order. This
     * first tries to keep using the CURRENTLY active shard's own account (a new repo under
     * the same namespace); if that account's storage is exhausted (isQuotaExceededError),
     * it moves to the next DIFFERENT registered account and creates the first shard there
     * instead. If every account is full (or none are registered), throws a clear,
     * actionable error telling the user to add a new Hugging Face account from Settings.
     *
     * Caller is responsible for persisting the result via GitHubContentsClient.putShardRegistry.
     */
    suspend fun ensureCapacity(
        registry: ShardRegistry,
        accounts: List<HfAccountEntry>,
        force: Boolean = false,
    ): ShardCapacityResult {
        val active = getActiveShard(registry)
        if (!force && active.usedBytesApprox < registry.sizeThresholdBytes) {
            return ShardCapacityResult(registry = registry, created = false, shard = active, rotatedAccount = false)
        }

        if (accounts.isEmpty()) {
            throw IllegalStateException(
                "Kayitli hicbir Hugging Face hesabi yok. Studio Ayarlar ekranindan en az bir hesap eklemelisiniz.",
            )
        }

        val activeNamespace = namespaceOf(active.id)
        val orderedAccounts = accounts.filter { it.namespace == activeNamespace } +
            accounts.filter { it.namespace != activeNamespace }

        var lastError: Throwable? = null
        for (account in orderedAccounts) {
            val newId = nextShardIdForNamespace(registry, account.namespace)
            try {
                createDatasetRepo(newId, account.token)
            } catch (err: Throwable) {
                lastError = err
                if (isQuotaExceededError(err)) continue // bu hesap dolu, siradakine gec
                throw err // baska (auth/network/vb) gercek bir hata — sessizce atlanmamali
            }

            val newShard = ShardEntry(
                id = newId,
                repoType = "dataset",
                active = true,
                usedBytesApprox = 0,
                createdAt = Instant.now().toString(),
            )
            val updatedShards = registry.shards.map { if (it.id == active.id) it.copy(active = false) else it } +
                newShard
            return ShardCapacityResult(
                registry = registry.copy(shards = updatedShards),
                created = true,
                shard = newShard,
                rotatedAccount = account.namespace != activeNamespace,
            )
        }

        throw IllegalStateException(
            "Tum kayitli Hugging Face hesaplari dolu (ya da erisim hatasi verdi). " +
                "Studio Ayarlar ekranindan yeni bir Hugging Face hesabi ekleyin." +
                (lastError?.let { " Son hata: ${it.message}" } ?: ""),
        )
    }

    /** Adds bytesAdded to the given shard's usage counter, returning an updated registry. */
    fun recordUsage(registry: ShardRegistry, shardId: String, bytesAdded: Long): ShardRegistry {
        val updatedShards = registry.shards.map { shard ->
            if (shard.id == shardId) shard.copy(usedBytesApprox = shard.usedBytesApprox + bytesAdded) else shard
        }
        return registry.copy(shards = updatedShards)
    }

    /**
     * Kotlin port of packages/hf-storage/src/accounts.js's resolveHfAccount. Validates a
     * pasted Hugging Face token and identifies which account it belongs to (via whoami),
     * so the Studio "add account" flow never requires the user to type a namespace by hand.
     */
    suspend fun resolveHfAccount(token: String): HfAccountInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://huggingface.co/api/whoami-v2")
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        val whoAmI = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HfUploadException(
                    "Hugging Face token dogrulanamadi (${response.code}): ${response.body?.string().orEmpty()}",
                    statusCode = response.code,
                )
            }
            json.decodeFromString(WhoAmIResponse.serializer(), response.body?.string().orEmpty())
        }

        if (whoAmI.name.isBlank()) {
            throw HfUploadException("Hugging Face token gecersiz gorunuyor (kullanici adi alinamadi).")
        }
        HfAccountInfo(namespace = whoAmI.name, fullname = whoAmI.fullname)
    }

    @Serializable
    private data class CreateRepoRequest(
        val type: String = "dataset",
        val name: String,
        val private: Boolean = false,
    )

    @Serializable
    private data class WhoAmIResponse(
        val name: String,
        val fullname: String? = null,
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
                    statusCode = response.code,
                )
            }
        }
    }

    companion object {
        private val SHARD_NUMBER_SUFFIX = Regex("-(\\d+)$")
    }
}
