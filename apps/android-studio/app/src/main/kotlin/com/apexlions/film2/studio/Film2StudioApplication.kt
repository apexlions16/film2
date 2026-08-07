package com.apexlions.film2.studio

import android.app.Application
import com.apexlions.film2.studio.catalog.GitHubContentsClient
import com.apexlions.film2.studio.dispatch.PackageMediaDispatcher
import com.apexlions.film2.studio.dispatch.QualityGenerationDispatcher
import com.apexlions.film2.studio.hf.CommitRepairingHfUploader
import com.apexlions.film2.studio.hf.HfUploader
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

    val qualityGenerationDispatcher: QualityGenerationDispatcher by lazy { QualityGenerationDispatcher() }

    /** New instance per job. CommitRepairingHfUploader keeps the normal uploader as the
     * primary path and only activates its raw-NDJSON fallback for the specific Hub
     * value.summary parser error seen on a real Android upload. */
    fun newHfUploader(): HfUploader = CommitRepairingHfUploader(context = this)
}
