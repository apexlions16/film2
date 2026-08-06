package com.apexlions.film2.studio

import android.app.Application
import com.apexlions.film2.studio.catalog.GitHubContentsClient
import com.apexlions.film2.studio.dispatch.PackageMediaDispatcher
import com.apexlions.film2.studio.hf.HuggingFaceUploader
import com.apexlions.film2.studio.hf.ShardRegistryManager
import com.apexlions.film2.studio.settings.SettingsRepository
import com.apexlions.film2.studio.tmdb.TmdbClient

/**
 * App-wide container. No DI framework — a handful of hand-rolled singletons is clearer
 * than pulling in Hilt/Koin for a single-screen-family admin app like this one.
 */
class Film2StudioApplication : Application() {

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }

    val githubClient: GitHubContentsClient by lazy {
        GitHubContentsClient(tokenProvider = { settingsRepository.currentTokens().githubPat })
    }

    val tmdbClient: TmdbClient by lazy { TmdbClient() }

    val shardRegistryManager: ShardRegistryManager by lazy { ShardRegistryManager() }

    val packageMediaDispatcher: PackageMediaDispatcher by lazy { PackageMediaDispatcher() }

    /** New instance per call: HuggingFaceUploader materializes SAF Uris via this app's
     *  cacheDir and shouldn't be shared across unrelated upload jobs. */
    fun newHfUploader(): HuggingFaceUploader = HuggingFaceUploader(
        tokenProvider = { settingsRepository.currentTokens().huggingFaceToken },
        context = this,
    )
}
