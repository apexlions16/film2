package com.apexlions.film2.player

import android.app.Application
import com.apexlions.film2.player.catalog.CatalogRepository
import com.apexlions.film2.player.offline.OfflineDownloadRepository
import com.apexlions.film2.player.userdata.PlaybackAppearanceRepository
import com.apexlions.film2.player.userdata.UserLibraryRepository

/** App-wide container kept intentionally small and dependency-framework free. */
class Film2PlayerApplication : Application() {
    val catalogRepository: CatalogRepository by lazy { CatalogRepository() }
    val userLibraryRepository: UserLibraryRepository by lazy { UserLibraryRepository(this) }
    val playbackAppearanceRepository: PlaybackAppearanceRepository by lazy { PlaybackAppearanceRepository(this) }
    val offlineDownloadRepository: OfflineDownloadRepository by lazy { OfflineDownloadRepository(this) }
}
