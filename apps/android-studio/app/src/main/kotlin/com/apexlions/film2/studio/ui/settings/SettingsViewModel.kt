package com.apexlions.film2.studio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.studio.settings.SettingsRepository
import com.apexlions.film2.studio.settings.StudioTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {

    val tokens: StateFlow<StudioTokens> = repository.tokens.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StudioTokens(),
    )

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    fun save(tmdbApiKey: String, huggingFaceToken: String, githubPat: String) {
        viewModelScope.launch {
            repository.setTmdbApiKey(tmdbApiKey.trim())
            repository.setHuggingFaceToken(huggingFaceToken.trim())
            repository.setGithubPat(githubPat.trim())
            _saved.value = true
        }
    }

    fun clearSavedFlag() {
        _saved.value = false
    }

    class Factory(private val repository: SettingsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(repository) as T
        }
    }
}
