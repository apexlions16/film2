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

@Serializable
data class HfAccountToken(
    val namespace: String,
    val fullname: String? = null,
    val token: String,
)

/** Portable one-time backup used when Android cannot update an APK because its signer changed. */
@Serializable
data class StudioSettingsBackup(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long,
    val tmdbApiKey: String? = null,
    val githubPat: String? = null,
    val huggingFaceAccounts: List<HfAccountToken> = emptyList(),
)

/**
 * Persists credentials via Preferences DataStore. In-place Android updates keep this data
 * as long as applicationId and signing certificate stay the same. Export/import provides
 * a deliberate migration path before an uninstall or signer change.
 */
class SettingsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private val backupJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

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
        context.settingsDataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_TMDB) else prefs[KEY_TMDB] = value
        }
    }

    suspend fun setGithubPat(value: String) {
        context.settingsDataStore.edit { prefs ->
            if (value.isBlank()) prefs.remove(KEY_GITHUB) else prefs[KEY_GITHUB] = value
        }
    }

    suspend fun addHfAccount(namespace: String, fullname: String?, token: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[KEY_HF_ACCOUNTS]).toMutableList()
            val existingIndex = current.indexOfFirst { it.namespace == namespace }
            val entry = HfAccountToken(namespace = namespace, fullname = fullname, token = token)
            if (existingIndex >= 0) current[existingIndex] = entry else current.add(entry)
            prefs[KEY_HF_ACCOUNTS] = encodeAccounts(current)
        }
    }

    suspend fun removeHfAccount(namespace: String) {
        context.settingsDataStore.edit { prefs ->
            val current = decodeAccounts(prefs[KEY_HF_ACCOUNTS]).filterNot { it.namespace == namespace }
            if (current.isEmpty()) prefs.remove(KEY_HF_ACCOUNTS) else prefs[KEY_HF_ACCOUNTS] = encodeAccounts(current)
        }
    }

    suspend fun exportBackup(): String {
        val currentTokens = currentTokens()
        return backupJson.encodeToString(
            StudioSettingsBackup.serializer(),
            StudioSettingsBackup(
                exportedAtEpochMs = System.currentTimeMillis(),
                tmdbApiKey = currentTokens.tmdbApiKey,
                githubPat = currentTokens.githubPat,
                huggingFaceAccounts = currentHfAccounts(),
            ),
        )
    }

    suspend fun importBackup(raw: String) {
        val backup = try {
            backupJson.decodeFromString(StudioSettingsBackup.serializer(), raw)
        } catch (t: Throwable) {
            throw IllegalArgumentException("Yedek dosyasi okunamadi: ${t.message}", t)
        }
        require(backup.schemaVersion == 1) {
            "Desteklenmeyen yedek surumu: ${backup.schemaVersion}"
        }
        require(backup.huggingFaceAccounts.all { it.namespace.isNotBlank() && it.token.isNotBlank() }) {
            "Yedekte gecersiz Hugging Face hesap bilgisi var"
        }

        val deduplicatedAccounts = backup.huggingFaceAccounts.distinctBy { it.namespace }
        context.settingsDataStore.edit { prefs ->
            setOrRemove(prefs, KEY_TMDB, backup.tmdbApiKey)
            setOrRemove(prefs, KEY_GITHUB, backup.githubPat)
            if (deduplicatedAccounts.isEmpty()) {
                prefs.remove(KEY_HF_ACCOUNTS)
            } else {
                prefs[KEY_HF_ACCOUNTS] = encodeAccounts(deduplicatedAccounts)
            }
        }
    }

    private fun setOrRemove(prefs: androidx.datastore.preferences.core.MutablePreferences, key: Preferences.Key<String>, value: String?) {
        if (value.isNullOrBlank()) prefs.remove(key) else prefs[key] = value
    }

    private fun encodeAccounts(accounts: List<HfAccountToken>): String =
        json.encodeToString(ListSerializer(HfAccountToken.serializer()), accounts)

    private fun decodeAccounts(raw: String?): List<HfAccountToken> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(ListSerializer(HfAccountToken.serializer()), raw)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        private val KEY_TMDB = stringPreferencesKey("tmdb_api_key")
        private val KEY_GITHUB = stringPreferencesKey("github_pat")
        private val KEY_HF_ACCOUNTS = stringPreferencesKey("huggingface_accounts")
    }
}
