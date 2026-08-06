package com.apexlions.film2.studio.ui.newtitle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apexlions.film2.studio.catalog.AssetStatus
import com.apexlions.film2.studio.catalog.GitHubContentsClient
import com.apexlions.film2.studio.catalog.Title
import com.apexlions.film2.studio.catalog.TitleType
import com.apexlions.film2.studio.settings.SettingsRepository
import com.apexlions.film2.studio.tmdb.TmdbClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

sealed interface NewTitleStep {
    data object Input : NewTitleStep
    data object Loading : NewTitleStep
    data class Preview(val draft: Title) : NewTitleStep
    data object Saving : NewTitleStep
    data class Saved(val title: Title) : NewTitleStep
    data class Error(val message: String) : NewTitleStep
}

class NewTitleViewModel(
    private val tmdbClient: TmdbClient,
    private val githubClient: GitHubContentsClient,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _step = MutableStateFlow<NewTitleStep>(NewTitleStep.Input)
    val step: StateFlow<NewTitleStep> = _step.asStateFlow()

    fun fetchFromImdb(link: String) {
        viewModelScope.launch {
            _step.value = NewTitleStep.Loading
            val apiKey = settingsRepository.currentTokens().tmdbApiKey
            if (apiKey.isNullOrBlank()) {
                _step.value = NewTitleStep.Error("Once Ayarlar'dan TMDB API anahtarini girin")
                return@launch
            }
            try {
                val found = tmdbClient.fetchTitleFromImdbLink(link, apiKey)
                _step.value = if (found != null) {
                    NewTitleStep.Preview(found)
                } else {
                    // TMDB has nothing for this id — fall back to a blank manual-entry form.
                    NewTitleStep.Preview(blankManualTitle(imdbId = tmdbClient.imdbLinkToId(link) ?: link))
                }
            } catch (t: Throwable) {
                _step.value = NewTitleStep.Error(t.message ?: "TMDB'den veri alinamadi")
            }
        }
    }

    fun startManualEntry() {
        _step.value = NewTitleStep.Preview(blankManualTitle(imdbId = ""))
    }

    private fun blankManualTitle(imdbId: String): Title {
        val now = Instant.now().toString()
        return Title(
            id = "",
            type = TitleType.MOVIE,
            imdbId = imdbId,
            title = "",
            overview = "",
            status = AssetStatus.PENDING,
            manualEntry = true,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun updateDraft(draft: Title) {
        _step.value = NewTitleStep.Preview(draft)
    }

    fun save(draft: Title) {
        viewModelScope.launch {
            _step.value = NewTitleStep.Saving
            try {
                val finalized = draft.copy(
                    id = draft.id.ifBlank { generateFallbackId(draft.title) },
                    updatedAt = Instant.now().toString(),
                )
                githubClient.putTitle(finalized)
                _step.value = NewTitleStep.Saved(finalized)
            } catch (t: Throwable) {
                _step.value = NewTitleStep.Error(t.message ?: "Katalog kaydedilemedi")
            }
        }
    }

    private fun generateFallbackId(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return slug.ifBlank { "title-${System.currentTimeMillis()}" }
    }

    class Factory(
        private val tmdbClient: TmdbClient,
        private val githubClient: GitHubContentsClient,
        private val settingsRepository: SettingsRepository,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NewTitleViewModel::class.java))
            return NewTitleViewModel(tmdbClient, githubClient, settingsRepository) as T
        }
    }
}
