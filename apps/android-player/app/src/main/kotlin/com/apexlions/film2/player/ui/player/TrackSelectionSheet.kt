@file:OptIn(ExperimentalMaterial3Api::class)

package com.apexlions.film2.player.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.apexlions.film2.player.catalog.VideoVariant
import com.apexlions.film2.player.userdata.PlaybackAppearanceState
import com.apexlions.film2.player.userdata.SubtitleBackground
import com.apexlions.film2.player.userdata.SubtitleEdge
import com.apexlions.film2.player.userdata.SubtitleTextColor
import com.apexlions.film2.player.userdata.VideoLayoutMode
import java.util.Locale

private data class TrackOption(
    val group: Tracks.Group,
    val indexInGroup: Int,
    val label: String,
    val selected: Boolean,
)

private fun trackLabel(format: Format, fallbackIndex: Int): String {
    val languageTag = format.language
    val displayName = languageTag?.let {
        runCatching { Locale.forLanguageTag(it).displayLanguage }.getOrNull()
    }
    return when {
        !format.label.isNullOrBlank() -> format.label!!
        !displayName.isNullOrBlank() -> displayName.replaceFirstChar { it.uppercaseChar() }
        !languageTag.isNullOrBlank() -> languageTag
        else -> "Ses ${fallbackIndex + 1}"
    }
}

private fun optionsForType(tracks: Tracks, type: Int): List<TrackOption> =
    tracks.groups
        .filter { it.type == type }
        .flatMap { group ->
            (0 until group.length).map { i ->
                TrackOption(
                    group = group,
                    indexInGroup = i,
                    label = trackLabel(group.getTrackFormat(i), i),
                    selected = group.isTrackSelected(i),
                )
            }
        }

@Composable
fun TrackSelectionSheet(
    tracks: Tracks,
    videoVariants: List<VideoVariant> = emptyList(),
    selectedQualityHeight: Int? = null,
    appearance: PlaybackAppearanceState,
    onSelectQuality: (VideoVariant) -> Unit,
    onSelectAudio: (Tracks.Group, Int) -> Unit,
    onSelectSubtitle: (Tracks.Group, Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onSubtitleSize: (Float) -> Unit,
    onSubtitlePosition: (Float) -> Unit,
    onSubtitleTextColor: (SubtitleTextColor) -> Unit,
    onSubtitleBackground: (SubtitleBackground) -> Unit,
    onSubtitleEdge: (SubtitleEdge) -> Unit,
    onVideoLayout: (VideoLayoutMode) -> Unit,
    onResetSubtitles: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
) {
    val audioOptions = optionsForType(tracks, C.TRACK_TYPE_AUDIO)
    val subtitleOptions = optionsForType(tracks, C.TRACK_TYPE_TEXT)
    val subtitlesOff = subtitleOptions.none { it.selected }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        LazyColumn(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            if (videoVariants.size > 1) {
                item { SectionTitle("Görüntü Kalitesi") }
                items(videoVariants.sortedByDescending { it.height }, key = { "quality-${it.height}-${it.url}" }) { variant ->
                    TrackRow(
                        label = if (variant.source) "${variant.label} (Kaynak)" else variant.label,
                        selected = selectedQualityHeight == variant.height,
                        onClick = { onSelectQuality(variant) },
                    )
                }
                item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }
            }

            item { SectionTitle("Ekran Oranı / Yerleşim") }
            item {
                LayoutChoiceRow(
                    selected = appearance.videoLayoutMode,
                    onSelect = onVideoLayout,
                )
            }
            item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }

            item { SectionTitle("Ses Dili") }
            if (audioOptions.isEmpty()) {
                item { EmptyMessage("Ses track'i bulunamadı") }
            }
            items(audioOptions) { option ->
                TrackRow(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelectAudio(option.group, option.indexInGroup) },
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }
            item { SectionTitle("Altyazı") }
            item { TrackRow(label = "Kapalı", selected = subtitlesOff, onClick = onDisableSubtitles) }
            if (subtitleOptions.isEmpty()) item { EmptyMessage("Altyazı bulunamadı") }
            items(subtitleOptions) { option ->
                TrackRow(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelectSubtitle(option.group, option.indexInGroup) },
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }
            item { SectionTitle("Altyazı Görünümü") }
            item { SubtitlePreview(appearance) }
            item {
                Text("Boyut  •  %${(appearance.subtitleSizeScale * 100).toInt()}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = appearance.subtitleSizeScale,
                    onValueChange = onSubtitleSize,
                    valueRange = 0.65f..1.75f,
                )
            }
            item {
                val heightPercent = (appearance.subtitleBottomPaddingFraction * 100f).toInt()
                Text("Yükseklik  •  %$heightPercent", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = appearance.subtitleBottomPaddingFraction.coerceIn(0.04f, 0.32f),
                    onValueChange = onSubtitlePosition,
                    valueRange = 0.04f..0.32f,
                    steps = 27,
                )
                Text(
                    "Sola doğru en alta, sağa doğru yukarı taşır. VTT içindeki konum bilgisi bu ayarı artık ezmez.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                ChoiceRow(
                    title = "Yazı rengi",
                    options = listOf(
                        "Beyaz" to SubtitleTextColor.WHITE,
                        "Sarı" to SubtitleTextColor.YELLOW,
                        "Cyan" to SubtitleTextColor.CYAN,
                    ),
                    selected = appearance.subtitleTextColor,
                    onSelect = onSubtitleTextColor,
                )
            }
            item {
                ChoiceRow(
                    title = "Arka plan",
                    options = listOf(
                        "Yok" to SubtitleBackground.NONE,
                        "Yumuşak" to SubtitleBackground.SOFT,
                        "Koyu" to SubtitleBackground.STRONG,
                    ),
                    selected = appearance.subtitleBackground,
                    onSelect = onSubtitleBackground,
                )
            }
            item {
                ChoiceRow(
                    title = "Kenar",
                    options = listOf(
                        "Outline" to SubtitleEdge.OUTLINE,
                        "Gölge" to SubtitleEdge.SHADOW,
                        "Yok" to SubtitleEdge.NONE,
                    ),
                    selected = appearance.subtitleEdge,
                    onSelect = onSubtitleEdge,
                )
            }
            item {
                OutlinedButton(
                    onClick = onResetSubtitles,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 28.dp),
                ) {
                    Text("Altyazı görünümünü sıfırla")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(value: String) {
    Text(
        value,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun EmptyMessage(value: String) {
    Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun LayoutChoiceRow(selected: VideoLayoutMode, onSelect: (VideoLayoutMode) -> Unit) {
    val options = listOf(
        "Orijinal" to VideoLayoutMode.ORIGINAL_FIT,
        "Tam Ekran" to VideoLayoutMode.SCREEN_CROP,
        "Esnet" to VideoLayoutMode.STRETCH,
        "16:9" to VideoLayoutMode.RATIO_16_9,
        "4:3" to VideoLayoutMode.RATIO_4_3,
        "21:9" to VideoLayoutMode.RATIO_21_9,
    )
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 6.dp)) {
        items(options) { (label, mode) ->
            AssistChip(
                onClick = { onSelect(mode) },
                label = { Text(label) },
                leadingIcon = if (selected == mode) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
            )
        }
    }
}

@Composable
private fun SubtitlePreview(appearance: PlaybackAppearanceState) {
    val textColor = when (appearance.subtitleTextColor) {
        SubtitleTextColor.WHITE -> Color.White
        SubtitleTextColor.YELLOW -> Color(0xFFFFEB3B)
        SubtitleTextColor.CYAN -> Color(0xFF80DEEA)
    }
    val background = when (appearance.subtitleBackground) {
        SubtitleBackground.NONE -> Color.Transparent
        SubtitleBackground.SOFT -> Color.Black.copy(alpha = 0.55f)
        SubtitleBackground.STRONG -> Color.Black.copy(alpha = 0.86f)
    }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .padding(vertical = 8.dp)
            .background(Color(0xFF252525), RoundedCornerShape(10.dp)),
    ) {
        val fraction = appearance.subtitleBottomPaddingFraction.coerceIn(0.04f, 0.32f)
        val bottomPadding = maxHeight * fraction
        Text(
            "Film2 altyazı önizlemesi",
            color = textColor,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 8.dp)
                .padding(bottom = bottomPadding)
                .background(background, RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Pair<String, T>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            options.forEach { (label, value) ->
                AssistChip(
                    onClick = { onSelect(value) },
                    label = { Text(label) },
                    leadingIcon = if (selected == value) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
