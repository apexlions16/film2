package com.apexlions.film2.player.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.player.catalog.CatalogRepository
import com.apexlions.film2.player.catalog.CatalogResult
import com.apexlions.film2.player.catalog.DemoContent
import com.apexlions.film2.player.catalog.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BrowseViewModel(private val repository: CatalogRepository) : ViewModel() {

    private val _state = MutableStateFlow<CatalogResult>(CatalogResult.Loading)
    val state: StateFlow<CatalogResult> = _state.asStateFlow()

    /** The demo row is always present regardless of catalog fetch outcome. */
    val demoTitle: Title = DemoContent.demoTitle

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = CatalogResult.Loading
            _state.value = repository.fetchTitles()
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
