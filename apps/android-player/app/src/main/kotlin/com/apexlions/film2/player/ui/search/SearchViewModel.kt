package com.apexlions.film2.player.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.player.catalog.CatalogRepository
import com.apexlions.film2.player.catalog.CatalogResult
import com.apexlions.film2.player.catalog.Title
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.util.Locale

sealed interface SearchUiState {
    data object Loading : SearchUiState
    data class Error(val message: String) : SearchUiState
    data class Ready(val query: String, val results: List<Title>, val allTitlesEmpty: Boolean) : SearchUiState
}

/**
 * Katalogdaki film/dizi arama. Katalog kucuk (kisisel koleksiyon) oldugu icin bir seferde
 * cekilip yerelde (Turkce karakterlere duyarsiz — normalize edilerek) filtreleniyor; ayrica
 * bir arama API'sine gerek yok.
 */
class SearchViewModel(private val repository: CatalogRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _titles = MutableStateFlow<CatalogResult>(CatalogResult.Loading)

    val state: StateFlow<SearchUiState> = combine(_query, _titles) { query, titlesResult ->
        when (titlesResult) {
            is CatalogResult.Loading -> SearchUiState.Loading
            is CatalogResult.Error -> SearchUiState.Error(titlesResult.message)
            is CatalogResult.Success -> {
                val normalizedQuery = normalize(query)
                val results = if (normalizedQuery.isBlank()) {
                    emptyList()
                } else {
                    titlesResult.titles.filter { title ->
                        normalize(title.title).contains(normalizedQuery) ||
                            normalize(title.originalTitle.orEmpty()).contains(normalizedQuery)
                    }
                }
                SearchUiState.Ready(query = query, results = results, allTitlesEmpty = titlesResult.titles.isEmpty())
            }
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), SearchUiState.Loading)

    init {
        viewModelScope.launch {
            _titles.value = repository.fetchTitles()
        }
    }

    fun onQueryChange(value: String) {
        _query.value = value
    }

    private fun normalize(text: String): String {
        // Turkce "ı/İ/ş/ğ/ç/ö/ü" dahil aksan/kilikli harfleri sadelestirir ki "sehir"
        // yazinca "Şehir" de eslessin.
        val ascii = Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .replace('ı', 'i')
            .replace('İ', 'I')
        return ascii.lowercase(Locale.ROOT)
    }

    class Factory(private val repository: CatalogRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SearchViewModel::class.java))
            return SearchViewModel(repository) as T
        }
    }
}
