package com.apexlions.film2.player.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.player.catalog.CatalogRepository
import com.apexlions.film2.player.catalog.DemoContent
import com.apexlions.film2.player.catalog.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TitleDetailState {
    data object Loading : TitleDetailState
    data class Loaded(val title: Title) : TitleDetailState
    data class Error(val message: String) : TitleDetailState
}

class TitleDetailViewModel(
    private val titleId: String,
    private val repository: CatalogRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<TitleDetailState>(TitleDetailState.Loading)
    val state: StateFlow<TitleDetailState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        if (titleId == DemoContent.DEMO_TITLE_ID) {
            _state.value = TitleDetailState.Loaded(DemoContent.demoTitle)
            return
        }
        viewModelScope.launch {
            _state.value = TitleDetailState.Loading
            val title = repository.fetchTitle(titleId)
            _state.value = if (title != null) {
                TitleDetailState.Loaded(title)
            } else {
                TitleDetailState.Error("Icerik bulunamadi")
            }
        }
    }

    class Factory(
        private val titleId: String,
        private val repository: CatalogRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(TitleDetailViewModel::class.java))
            return TitleDetailViewModel(titleId, repository) as T
        }
    }
}
