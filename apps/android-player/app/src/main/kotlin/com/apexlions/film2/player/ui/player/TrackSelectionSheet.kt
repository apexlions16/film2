@file:OptIn(ExperimentalMaterial3Api::class)

package com.apexlions.film2.player.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import com.apexlions.film2.player.catalog.VideoVariant
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
    onSelectQuality: (VideoVariant) -> Unit,
    onSelectAudio: (Tracks.Group, Int) -> Unit,
    onSelectSubtitle: (Tracks.Group, Int) -> Unit,
    onDisableSubtitles: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
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
                item {
                    Text(
                        "Goruntu Kalitesi",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                items(videoVariants.sortedByDescending { it.height }, key = { "quality-${it.height}-${it.url}" }) { variant ->
                    TrackRow(
                        label = if (variant.source) "${variant.label} (Kaynak)" else variant.label,
                        selected = selectedQualityHeight == variant.height,
                        onClick = { onSelectQuality(variant) },
                    )
                }
                item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }
            }

            item {
                Text(
                    "Ses Dili",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (audioOptions.isEmpty()) {
                item {
                    Text(
                        "Ses track'i bulunamadi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(audioOptions) { option ->
                TrackRow(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelectAudio(option.group, option.indexInGroup) },
                )
            }

            item { Divider(modifier = Modifier.padding(vertical = 16.dp)) }

            item {
                Text(
                    "Altyazi",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                TrackRow(
                    label = "Kapali",
                    selected = subtitlesOff,
                    onClick = onDisableSubtitles,
                )
            }
            if (subtitleOptions.isEmpty()) {
                item {
                    Text(
                        "Altyazi bulunamadi",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(subtitleOptions) { option ->
                TrackRow(
                    label = option.label,
                    selected = option.selected,
                    onClick = { onSelectSubtitle(option.group, option.indexInGroup) },
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        }
    }
}
