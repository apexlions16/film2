package com.apexlions.film2.studio.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(
    name = "film2_studio_settings",
)

data class StudioTokens(
    val tmdbApiKey: String? = null,
    val huggingFaceToken: String? = null,
    val githubPat: String? = null,
)

/**
 * Persists the three API credentials the app needs (TMDB, Hugging Face, GitHub PAT) via
 * Preferences DataStore. Never logged (no Log.d/println of these values anywhere in the
 * app) and only ever sent as Authorization headers to the official TMDB / huggingface.co /
 * api.github.com endpoints — see TmdbClient, HuggingFaceUploader, GitHubContentsClient,
 * PackageMediaDispatcher.
 */
class SettingsRepository(private val context: Context) {

    val tokens: Flow<StudioTokens> = context.settingsDataStore.data.map { prefs ->
        StudioTokens(
            tmdbApiKey = prefs[KEY_TMDB],
            huggingFaceToken = prefs[KEY_HF],
            githubPat = prefs[KEY_GITHUB],
        )
    }

    suspend fun currentTokens(): StudioTokens = tokens.first()

    suspend fun setTmdbApiKey(value: String) {
        context.settingsDataStore.edit { it[KEY_TMDB] = value }
    }

    suspend fun setHuggingFaceToken(value: String) {
        context.settingsDataStore.edit { it[KEY_HF] = value }
    }

    suspend fun setGithubPat(value: String) {
        context.settingsDataStore.edit { it[KEY_GITHUB] = value }
    }

    companion object {
        private val KEY_TMDB = stringPreferencesKey("tmdb_api_key")
        private val KEY_HF = stringPreferencesKey("huggingface_token")
        private val KEY_GITHUB = stringPreferencesKey("github_pat")
    }
}
