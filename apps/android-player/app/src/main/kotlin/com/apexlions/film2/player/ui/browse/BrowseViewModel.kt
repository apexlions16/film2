package com.apexlions.film2.player.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.player.catalog.CatalogRepository
import com.apexlions.film2.player.catalog.CatalogResult
import com.apexlions.film2.player.catalog.DemoContent
import com.apexlions.film2.player.catalog.HomeConfig
import com.apexlions.film2.player.catalog.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class BrowseViewModel(private val repository: CatalogRepository) : ViewModel() {

    private val _state = MutableStateFlow<CatalogResult>(CatalogResult.Loading)
    val state: StateFlow<CatalogResult> = _state.asStateFlow()

    private val _homeConfig = MutableStateFlow(HomeConfig.DEFAULT)
    val homeConfig: StateFlow<HomeConfig> = _homeConfig.asStateFlow()

    /** Her manuel/gercek katalog yenilemesinde artwork havuzundan yeni secim yapilmasini saglar. */
    private val _artworkNonce = MutableStateFlow(System.currentTimeMillis())
    val artworkNonce: StateFlow<Long> = _artworkNonce.asStateFlow()

    private val refreshMutex = Mutex()
    private var lastRevision: String? = null

    val demoTitle: Title = DemoContent.demoTitle

    init {
        refresh()
        viewModelScope.launch {
            lastRevision = repository.fetchRevision()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            performRefresh(showLoading = true)
            lastRevision = repository.fetchRevision() ?: lastRevision
        }
    }

    suspend fun refreshIfChanged() {
        val revision = repository.fetchRevision() ?: return
        val previous = lastRevision
        if (previous == null) {
            lastRevision = revision
            return
        }
        if (revision == previous) return

        performRefresh(showLoading = false)
        lastRevision = revision
    }

    private suspend fun performRefresh(showLoading: Boolean) {
        refreshMutex.withLock {
            if (showLoading) _state.value = CatalogResult.Loading
            val result = repository.fetchTitles()
            if (showLoading || result is CatalogResult.Success) {
                _state.value = result
            }
            if (result is CatalogResult.Success) {
                _homeConfig.value = repository.fetchHomeConfig()
                _artworkNonce.value = System.currentTimeMillis()
            }
        }
    }

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(BrowseViewModel::class.java))
            return BrowseViewModel(repository) as T
        }
    }
}
