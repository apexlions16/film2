package com.apexlions.film2.studio.hf

import android.net.Uri
import com.apexlions.film2.studio.catalog.ShardEntry
import com.apexlions.film2.studio.catalog.ShardRegistry

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

/** Uploads with the active account and rotates to the next registered account on quota errors. */
suspend fun uploadFileWithFailover(
    uploader: HfUploader,
    shardRegistryManager: ShardRegistryManager,
    localUri: Uri,
    repoPath: String,
    registry: ShardRegistry,
    accounts: List<HfAccountEntry>,
    onProgress: (HfUploadProgress) -> Unit = {},
): FailoverUploadResult {
    val shard = shardRegistryManager.getActiveShard(registry)
    val token = tokenFor(accounts, namespaceOf(shard.id))

    var lastTotal = 0L
    try {
        val url = uploader.uploadFile(token, shard.id, repoPath, localUri) { progress ->
            lastTotal = progress.totalBytes
            onProgress(progress)
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
        onProgress(
            HfUploadProgress(
                stage = HfUploadStage.CHECKING,
                bytesProcessed = 0,
                totalBytes = 0,
                message = "Depolama kotasi doldu; ${namespaceOf(newShard.id)} hesabina geciliyor",
            ),
        )
        val url = uploader.uploadFile(newToken, newShard.id, repoPath, localUri) { progress ->
            lastTotal = progress.totalBytes
            onProgress(progress)
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
