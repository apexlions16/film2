package com.apexlions.film2.studio.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.settingsDataStore: androidx.datastore.core.DataStore<Preferences> by preferencesDataStore(
    name = "film2_studio_settings",
)

data class StudioTokens(
    val tmdbApiKey: String? = null,
    val githubPat: String? = null,
)

/**
 * A single registered Hugging Face account, as stored locally. Kotlin equivalent of the
 * desktop app's `StoredHfAccount` (apps/desktop-studio/src/main/settings.ts). Multiple of
 * these can be registered so uploads can fail over to a different Hugging Face account
 * when the active one runs out of storage — see ShardRegistryManager.ensureCapacity /
 * uploadFileWithFailover.
 */
@Serializable
data class HfAccountToken(
    val namespace: String,
    val fullname: String? = null,
    val token: String,
)

/**
 * Persists the API credentials the app needs (TMDB, Hugging Face accounts, GitHub PAT) via
 * Preferences DataStore. Never logged (no Log.d/println of these values anywhere in the
 * app) and only ever sent as Authorization headers to the official TMDB / huggingface.co /
 * api.github.com endpoints — see TmdbClient, HuggingFaceUploader, GitHubContentsClient,
 * PackageMediaDispatcher.
 *
 * Hugging Face is stored as an ORDERED LIST of accounts (not a single token) so that when
 * the active account's storage quota is exceeded, uploads can automatically fail over to a
 * different, genuinely separate Hugging Face account the user has registered — see
 * packages/hf-storage/src/registry.js (the reference implementation this ports) and
 * apps/desktop-studio/src/main/settings.ts (the desktop equivalent).
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    val tokens: Flow<StudioTokens> = context.settingsDataStore.data.map { prefs ->
        StudioTokens(
            tmdbApiKey = prefs[KEY_TMDB],
            githubPat = prefs[KEY_GITHUB],
        )
    }

    val hfAccounts: Flow<List<HfAccountToken>> = context.settingsDataStore.data.map { prefs ->
        decodeAccounts(prefs[KEY_HF_ACCOUNTS])
    }

    suspend fun currentTokens(): StudioTokens = tokens.first()

    suspend fun currentHfAccounts(): List<HfAccountToken> = hfAccounts.first()

    suspend fun setTmdbApiKey(value: String) {
        context.settingsDataStore.edit { it[KEY_TMDB] = value }
    }

    suspend fun setGithubPat(value: String) {
        context.settingsDataStore.edit { it[KEY_GITHUB] = value }
    }

    /** Upserts (by namespace) a Hugging Face account. Callers should resolve the namespace
     *  via ShardRegistryManager.resolveHfAccount (whoami) before calling this, so the user
     *  only ever pastes a token — never types a namespace by hand. */
    suspend fun addHfAccount(namespace: String, fullname: String?, token: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[KEY_HF_ACCOUNTS]).toMutableList()
            val existingIndex = current.indexOfFirst { it.namespace == namespace }
            val entry = HfAccountToken(namespace = namespace, fullname = fullname, token = token)
            if (existingIndex >= 0) {
                current[existingIndex] = entry
            } else {
                current.add(entry)
            }
            prefs[KEY_HF_ACCOUNTS] = json.encodeToString(ListSerializer(HfAccountToken.serializer()), current)
        }
    }

    suspend fun removeHfAccount(namespace: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[KEY_HF_ACCOUNTS]).filterNot { it.namespace == namespace }
            prefs[KEY_HF_ACCOUNTS] = json.encodeToString(ListSerializer(HfAccountToken.serializer()), current)
        }
    }

    private fun decodeAccounts(raw: String?): List<HfAccountToken> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(HfAccountToken.serializer()), raw)
        } catch (t: Throwable) {
            emptyList()
        }
    }

    companion object {
        private val KEY_TMDB = stringPreferencesKey("tmdb_api_key")
        private val KEY_GITHUB = stringPreferencesKey("github_pat")
        private val KEY_HF_ACCOUNTS = stringPreferencesKey("huggingface_accounts")
    }
}
