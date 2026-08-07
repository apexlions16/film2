package com.apexlions.film2.player.userdata

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Cihazdaki kullaniciya ait Netflix-benzeri durumlar.
 *
 * Bu veri katalogdan bagimsizdir: uygulama guncellense veya katalog yeniden cekilse bile
 * ayni applicationId ile kurulu uygulamanin SharedPreferences alani korunur.
 */
@Serializable
data class PlaybackRecord(
    val key: String,
    val titleId: String,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    val subtitlesDisabled: Boolean = false,
    val qualityHeight: Int? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
) {
    val progressFraction: Float
        get() = if (durationMs > 0L) {
            (positionMs.toDouble() / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }

    val completed: Boolean
        get() = durationMs > 0L && progressFraction >= 0.95f

    val hasMeaningfulProgress: Boolean
        get() = durationMs > 0L && positionMs >= 30_000L && !completed

    fun resumePositionMs(): Long = if (hasMeaningfulProgress) positionMs else 0L
}

@Serializable
data class UserCollection(
    val id: String,
    val name: String,
    val titleIds: List<String> = emptyList(),
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

@Serializable
data class UserLibraryState(
    val playback: Map<String, PlaybackRecord> = emptyMap(),
    /** Netflix'teki tek tiklik "Listem". */
    val myListTitleIds: List<String> = emptyList(),
    /** Kullanici tarafindan isimlendirilmis ek listeler. */
    val customLists: List<UserCollection> = emptyList(),
) {
    fun record(titleId: String, seasonNumber: Int? = null, episodeNumber: Int? = null): PlaybackRecord? =
        playback[contentKey(titleId, seasonNumber, episodeNumber)]

    fun latestForTitle(titleId: String): PlaybackRecord? = playback.values
        .asSequence()
        .filter { it.titleId == titleId }
        .maxByOrNull { it.updatedAtEpochMs }

    fun continueWatching(): List<PlaybackRecord> = playback.values
        .asSequence()
        .filter { it.hasMeaningfulProgress }
        .sortedByDescending { it.updatedAtEpochMs }
        .toList()
}

fun contentKey(titleId: String, seasonNumber: Int?, episodeNumber: Int?): String =
    "$titleId|${seasonNumber ?: -1}|${episodeNumber ?: -1}"

class UserLibraryRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lock = Any()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<UserLibraryState> = _state.asStateFlow()

    fun record(titleId: String, seasonNumber: Int? = null, episodeNumber: Int? = null): PlaybackRecord? =
        _state.value.record(titleId, seasonNumber, episodeNumber)

    fun savePlaybackSnapshot(
        titleId: String,
        seasonNumber: Int?,
        episodeNumber: Int?,
        positionMs: Long,
        durationMs: Long,
        audioLanguage: String?,
        subtitleLanguage: String?,
        subtitlesDisabled: Boolean,
        qualityHeight: Int?,
    ) {
        val key = contentKey(titleId, seasonNumber, episodeNumber)
        mutate { current ->
            val old = current.playback[key]
            val normalizedDuration = durationMs.coerceAtLeast(old?.durationMs ?: 0L)
            val safePosition = when {
                normalizedDuration > 0L -> positionMs.coerceIn(0L, normalizedDuration)
                else -> positionMs.coerceAtLeast(0L)
            }
            val updated = PlaybackRecord(
                key = key,
                titleId = titleId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                positionMs = safePosition,
                durationMs = normalizedDuration,
                audioLanguage = audioLanguage,
                subtitleLanguage = subtitleLanguage,
                subtitlesDisabled = subtitlesDisabled,
                qualityHeight = qualityHeight,
                updatedAtEpochMs = System.currentTimeMillis(),
            )
            current.copy(playback = current.playback + (key to updated))
        }
    }

    fun clearProgress(titleId: String, seasonNumber: Int? = null, episodeNumber: Int? = null) {
        val key = contentKey(titleId, seasonNumber, episodeNumber)
        mutate { current ->
            val existing = current.playback[key] ?: return@mutate current
            current.copy(
                playback = current.playback + (
                    key to existing.copy(
                        positionMs = 0L,
                        durationMs = existing.durationMs,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                ),
            )
        }
    }

    fun toggleMyList(titleId: String) {
        mutate { current ->
            val next = current.myListTitleIds.toMutableList()
            if (titleId in next) next.removeAll { it == titleId } else next.add(titleId)
            current.copy(myListTitleIds = next.distinct())
        }
    }

    fun createList(name: String): UserCollection? {
        val clean = name.trim().take(48)
        if (clean.isBlank()) return null
        var created: UserCollection? = null
        mutate { current ->
            val collection = UserCollection(
                id = UUID.randomUUID().toString(),
                name = clean,
            )
            created = collection
            current.copy(customLists = current.customLists + collection)
        }
        return created
    }

    fun renameList(listId: String, name: String) {
        val clean = name.trim().take(48)
        if (clean.isBlank()) return
        mutate { current ->
            current.copy(
                customLists = current.customLists.map { list ->
                    if (list.id == listId) list.copy(name = clean) else list
                },
            )
        }
    }

    fun deleteList(listId: String) {
        mutate { current -> current.copy(customLists = current.customLists.filterNot { it.id == listId }) }
    }

    fun toggleTitleInList(listId: String, titleId: String) {
        mutate { current ->
            current.copy(
                customLists = current.customLists.map { list ->
                    if (list.id != listId) return@map list
                    val next = list.titleIds.toMutableList()
                    if (titleId in next) next.removeAll { it == titleId } else next.add(titleId)
                    list.copy(titleIds = next.distinct())
                },
            )
        }
    }

    private fun mutate(transform: (UserLibraryState) -> UserLibraryState) {
        synchronized(lock) {
            val next = transform(_state.value)
            if (next == _state.value) return
            _state.value = next
            preferences.edit()
                .putString(KEY_STATE, json.encodeToString(UserLibraryState.serializer(), next))
                .apply()
        }
    }

    private fun readState(): UserLibraryState {
        val raw = preferences.getString(KEY_STATE, null) ?: return UserLibraryState()
        return runCatching { json.decodeFromString(UserLibraryState.serializer(), raw) }
            .getOrDefault(UserLibraryState())
    }

    companion object {
        private const val PREFS_NAME = "film2_player_user_library"
        private const val KEY_STATE = "state_v1"
    }
}
