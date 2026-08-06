package com.apexlions.film2.studio.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.studio.catalog.GitHubContentsClient
import com.apexlions.film2.studio.catalog.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface CatalogListState {
    data object Loading : CatalogListState
    data class Loaded(val titles: List<Title>) : CatalogListState
    data class Error(val message: String) : CatalogListState
}

class CatalogListViewModel(private val client: GitHubContentsClient) : ViewModel() {

    private val _state = MutableStateFlow<CatalogListState>(CatalogListState.Loading)
    val state: StateFlow<CatalogListState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = CatalogListState.Loading
            _state.value = try {
                CatalogListState.Loaded(client.listTitles())
            } catch (t: Throwable) {
                CatalogListState.Error(t.message ?: "Katalog yuklenemedi")
            }
        }
    }

    class Factory(private val client: GitHubContentsClient) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(CatalogListViewModel::class.java))
            return CatalogListViewModel(client) as T
        }
    }
}
