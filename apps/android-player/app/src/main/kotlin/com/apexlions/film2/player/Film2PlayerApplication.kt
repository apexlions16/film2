package com.apexlions.film2.player

import android.app.Application
import com.apexlions.film2.player.catalog.CatalogRepository

/**
 * Minimal app-wide container. No DI framework — the catalog is tiny and the app is
 * simple enough that a couple of hand-rolled singletons are clearer than pulling in
 * Hilt/Koin for two screens' worth of dependencies.
 */
class Film2PlayerApplication : Application() {
    val catalogRepository: CatalogRepository by lazy { CatalogRepository() }
}
