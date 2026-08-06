package com.apexlions.film2.studio.hf

import android.net.Uri
import com.apexlions.film2.studio.catalog.ShardEntry
import com.apexlions.film2.studio.catalog.ShardRegistry

/** Result of an upload that may have rotated to a different shard/Hugging Face account
 *  along the way. Kotlin equivalent of packages/hf-storage/src/failover.js's
 *  uploadFileWithFailover return shape. */
data class FailoverUploadResult(
    val url: String,
    val shard: ShardEntry,
    val registry: ShardRegistry,
    val rotatedAccount: Boolean,
    val bytes: Long,
)

private fun tokenFor(accounts: List<HfAccountEntry>, namespace: String): String {
    val account = accounts.firstOrNull { it.namespace == namespace }
        ?: throw HfUploadException(
            "\"$namespace\" hesabi icin kayitli bir Hugging Face token'i yok. Studio Ayarlar ekranindan ekleyin.",
        )
    return account.token
}

/**
 * Kotlin port of packages/hf-storage/src/failover.js's uploadFileWithFailover: uploads to
 * the currently active shard using its matching registered account's token. If Hugging
 * Face responds with a real "storage quota exceeded" error (isQuotaExceededError — not
 * just our approximate byte counter), automatically rotates to the next registered Hugging
 * Face account (ShardRegistryManager.ensureCapacity with force=true) and retries the same
 * upload there. The caller (UploadWorker) is responsible for persisting the returned
 * registry.
 */
suspend fun uploadFileWithFailover(
    uploader: HfUploader,
    shardRegistryManager: ShardRegistryManager,
    localUri: Uri,
    repoPath: String,
    registry: ShardRegistry,
    accounts: List<HfAccountEntry>,
    onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> },
): FailoverUploadResult {
    val shard = shardRegistryManager.getActiveShard(registry)
    val token = tokenFor(accounts, namespaceOf(shard.id))

    var lastTotal = 0L
    try {
        val url = uploader.uploadFile(token, shard.id, repoPath, localUri) { sent, total ->
            lastTotal = total
            onProgress(sent, total)
        }
        return FailoverUploadResult(
            url = url,
            shard = shard,
            registry = registry,
            rotatedAccount = false,
            bytes = lastTotal,
        )
    } catch (err: Throwable) {
        if (!isQuotaExceededError(err)) throw err

        val capacity = shardRegistryManager.ensureCapacity(registry, accounts, force = true)
        val newShard = capacity.shard
        val newToken = tokenFor(accounts, namespaceOf(newShard.id))

        lastTotal = 0L
        val url = uploader.uploadFile(newToken, newShard.id, repoPath, localUri) { sent, total ->
            lastTotal = total
            onProgress(sent, total)
        }
        return FailoverUploadResult(
            url = url,
            shard = newShard,
            registry = capacity.registry,
            rotatedAccount = capacity.rotatedAccount,
            bytes = lastTotal,
        )
    }
}
