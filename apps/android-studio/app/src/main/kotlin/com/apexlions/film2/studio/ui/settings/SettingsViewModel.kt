package com.apexlions.film2.studio.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.studio.hf.ShardRegistryManager
import com.apexlions.film2.studio.settings.HfAccountToken
import com.apexlions.film2.studio.settings.SettingsRepository
import com.apexlions.film2.studio.settings.StudioTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val repository: SettingsRepository,
    private val shardRegistryManager: ShardRegistryManager,
) : ViewModel() {

    val tokens: StateFlow<StudioTokens> = repository.tokens.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = StudioTokens(),
    )

    val hfAccounts: StateFlow<List<HfAccountToken>> = repository.hfAccounts.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList(),
    )

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    private val _addingAccount = MutableStateFlow(false)
    val addingAccount: StateFlow<Boolean> = _addingAccount

    private val _accountError = MutableStateFlow<String?>(null)
    val accountError: StateFlow<String?> = _accountError

    fun save(tmdbApiKey: String, githubPat: String) {
        viewModelScope.launch {
            repository.setTmdbApiKey(tmdbApiKey.trim())
            repository.setGithubPat(githubPat.trim())
            _saved.value = true
        }
    }

    fun clearSavedFlag() {
        _saved.value = false
    }

    /** Validates the pasted token via whoami (auto-detects the namespace), then registers
     *  it as a Hugging Face account. Mirrors the desktop app's addHfAccount flow. */
    fun addHfAccount(token: String, onDone: () -> Unit = {}) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        _accountError.value = null
        _addingAccount.value = true
        viewModelScope.launch {
            try {
                val info = shardRegistryManager.resolveHfAccount(trimmed)
                repository.addHfAccount(namespace = info.namespace, fullname = info.fullname, token = trimmed)
                onDone()
            } catch (t: Throwable) {
                _accountError.value = t.message ?: "Hesap eklenemedi"
            } finally {
                _addingAccount.value = false
            }
        }
    }

    fun removeHfAccount(namespace: String) {
        _accountError.value = null
        viewModelScope.launch {
            try {
                repository.removeHfAccount(namespace)
            } catch (t: Throwable) {
                _accountError.value = t.message ?: "Hesap kaldirilamadi"
            }
        }
    }

    class Factory(
        private val repository: SettingsRepository,
        private val shardRegistryManager: ShardRegistryManager,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SettingsViewModel::class.java))
            return SettingsViewModel(repository, shardRegistryManager) as T
        }
    }
}
