package com.apexlions.film2.player.userdata

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class SubtitleTextColor {
    @SerialName("white") WHITE,
    @SerialName("yellow") YELLOW,
    @SerialName("cyan") CYAN,
}

@Serializable
enum class SubtitleBackground {
    @SerialName("none") NONE,
    @SerialName("soft") SOFT,
    @SerialName("strong") STRONG,
}

@Serializable
enum class SubtitleEdge {
    @SerialName("outline") OUTLINE,
    @SerialName("shadow") SHADOW,
    @SerialName("none") NONE,
}

@Serializable
enum class VideoLayoutMode {
    /** Kaynagin kendi oranini korur; siyah bant olabilir. */
    @SerialName("original_fit") ORIGINAL_FIT,
    /** Cihazin tum ekranini oran bozmadan doldurur; kenarlardan kirpabilir. */
    @SerialName("screen_crop") SCREEN_CROP,
    /** Tum ekrana esnetir; oran bozulabilir. */
    @SerialName("stretch") STRETCH,
    @SerialName("ratio_16_9") RATIO_16_9,
    @SerialName("ratio_4_3") RATIO_4_3,
    @SerialName("ratio_21_9") RATIO_21_9,
}

@Serializable
data class PlaybackAppearanceState(
    /** Media3 varsayilanina gore carpandir. */
    val subtitleSizeScale: Float = 1.08f,
    /** Windows Player'daki bottomPercent ile ayni mantik: 0.04 = %4, 0.32 = %32. */
    val subtitleBottomPaddingFraction: Float = 0.12f,
    val subtitleTextColor: SubtitleTextColor = SubtitleTextColor.WHITE,
    val subtitleBackground: SubtitleBackground = SubtitleBackground.SOFT,
    val subtitleEdge: SubtitleEdge = SubtitleEdge.OUTLINE,
    val videoLayoutMode: VideoLayoutMode = VideoLayoutMode.ORIGINAL_FIT,
)

class PlaybackAppearanceRepository(context: Context) {
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Any()

    private val _state = MutableStateFlow(readState())
    val state: StateFlow<PlaybackAppearanceState> = _state.asStateFlow()

    fun setSubtitleSizeScale(value: Float) = mutate {
        it.copy(subtitleSizeScale = value.coerceIn(0.65f, 1.75f))
    }

    fun setSubtitleBottomPaddingFraction(value: Float) = mutate {
        it.copy(subtitleBottomPaddingFraction = value.coerceIn(0.04f, 0.32f))
    }

    fun setSubtitleTextColor(value: SubtitleTextColor) = mutate { it.copy(subtitleTextColor = value) }
    fun setSubtitleBackground(value: SubtitleBackground) = mutate { it.copy(subtitleBackground = value) }
    fun setSubtitleEdge(value: SubtitleEdge) = mutate { it.copy(subtitleEdge = value) }
    fun setVideoLayoutMode(value: VideoLayoutMode) = mutate { it.copy(videoLayoutMode = value) }

    fun resetSubtitles() = mutate {
        it.copy(
            subtitleSizeScale = PlaybackAppearanceState().subtitleSizeScale,
            subtitleBottomPaddingFraction = PlaybackAppearanceState().subtitleBottomPaddingFraction,
            subtitleTextColor = PlaybackAppearanceState().subtitleTextColor,
            subtitleBackground = PlaybackAppearanceState().subtitleBackground,
            subtitleEdge = PlaybackAppearanceState().subtitleEdge,
        )
    }

    private fun mutate(transform: (PlaybackAppearanceState) -> PlaybackAppearanceState) {
        synchronized(lock) {
            val next = transform(_state.value)
            if (next == _state.value) return
            _state.value = next
            preferences.edit()
                .putString(KEY_STATE, json.encodeToString(PlaybackAppearanceState.serializer(), next))
                .apply()
        }
    }

    private fun readState(): PlaybackAppearanceState {
        val raw = preferences.getString(KEY_STATE, null) ?: return PlaybackAppearanceState()
        val decoded = runCatching { json.decodeFromString(PlaybackAppearanceState.serializer(), raw) }
            .getOrDefault(PlaybackAppearanceState())
        return decoded.copy(
            subtitleSizeScale = decoded.subtitleSizeScale.coerceIn(0.65f, 1.75f),
            subtitleBottomPaddingFraction = decoded.subtitleBottomPaddingFraction.coerceIn(0.04f, 0.32f),
        )
    }

    companion object {
        private const val PREFS_NAME = "film2_player_appearance"
        private const val KEY_STATE = "state_v1"
    }
}
