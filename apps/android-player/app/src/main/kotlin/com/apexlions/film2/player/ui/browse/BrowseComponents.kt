package com.apexlions.film2.player.ui.browse

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.apexlions.film2.player.catalog.AssetStatus
import com.apexlions.film2.player.catalog.Title
import com.apexlions.film2.player.userdata.PlaybackRecord
import kotlin.math.absoluteValue

private val WatchProgressRed = Color(0xFFE50914)

data class ContinueWatchingUiItem(
    val title: Title,
    val record: PlaybackRecord,
    val imageUrl: String?,
    val subtitle: String?,
)

fun Title.rotatingPoster(nonce: Long = 0L): String? =
    chooseArtwork((posterUrls + listOfNotNull(posterUrl)).distinct(), nonce, "poster")

fun Title.rotatingBackdrop(nonce: Long = 0L): String? =
    chooseArtwork((backdropUrls + listOfNotNull(backdropUrl)).distinct(), nonce, "backdrop")

private fun Title.chooseArtwork(pool: List<String>, nonce: Long, salt: String): String? {
    if (pool.isEmpty()) return null
    val index = ("$id|$nonce|$salt".hashCode().toLong().absoluteValue % pool.size).toInt()
    return pool[index]
}

@Composable
fun HeroBanner(
    title: Title,
    onPlay: () -> Unit,
    onMoreInfo: () -> Unit,
    artworkNonce: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val artwork = title.rotatingBackdrop(artworkNonce)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(480.dp),
    ) {
        if (artwork != null) {
            AsyncImage(
                model = artwork,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, MaterialTheme.colorScheme.background),
                        startY = 0f,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(MaterialTheme.colorScheme.background.copy(alpha = 0.58f), Color.Transparent),
                        endX = 900f,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(0.84f)
                .padding(start = 20.dp, end = 12.dp, bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusBadge(status = title.status)
            Text(
                text = title.title,
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = title.overview,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = onPlay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Text(" Oynat", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = onMoreInfo) {
                    Icon(Icons.Outlined.Info, contentDescription = null)
                    Text(" Detaylar")
                }
            }
        }
    }
}

@Composable
fun StatusBadge(status: AssetStatus, modifier: Modifier = Modifier) {
    val (label, color) = when (status) {
        AssetStatus.READY -> "Hazir" to MaterialTheme.colorScheme.primary
        AssetStatus.PROCESSING -> "Isleniyor" to Color(0xFFB08B41)
        AssetStatus.PENDING -> "Beklemede" to MaterialTheme.colorScheme.onSurfaceVariant
        AssetStatus.ERROR -> "Hata" to MaterialTheme.colorScheme.error
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = label.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun ContinueWatchingRow(
    items: List<ContinueWatchingUiItem>,
    onPlay: (ContinueWatchingUiItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier = modifier.padding(bottom = 28.dp)) {
        Text(
            text = "Devam Et",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(items, key = { it.record.key }) { item ->
                ContinueWatchingCard(item = item, onClick = { onPlay(item) })
            }
        }
    }
}

@Composable
private fun ContinueWatchingCard(item: ContinueWatchingUiItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(210.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(
                model = item.imageUrl ?: item.title.backdropUrl ?: item.title.posterUrl,
                contentDescription = item.title.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.64f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = "Devam et", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            ProgressLine(item.record.progressFraction, Modifier.align(Alignment.BottomCenter))
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(item.title.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun GenreRow(
    genre: String,
    titles: List<Title>,
    onSelect: (Title) -> Unit,
    progressByTitle: Map<String, Float> = emptyMap(),
    artworkNonce: Long = 0L,
    modifier: Modifier = Modifier,
) {
    if (titles.isEmpty()) return
    Column(modifier = modifier.padding(bottom = 26.dp)) {
        Text(
            text = genre,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp),
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            items(titles, key = { it.id }) { title ->
                PosterCard(
                    title = title,
                    progress = progressByTitle[title.id],
                    artworkNonce = artworkNonce,
                    onClick = { onSelect(title) },
                )
            }
        }
    }
}

@Composable
fun PosterCard(
    title: Title,
    onClick: () -> Unit,
    progress: Float? = null,
    artworkNonce: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "posterScale",
    )
    val poster = title.rotatingPoster(artworkNonce)

    Column(
        modifier = modifier
            .width(120.dp)
            .scale(scale)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick),
    ) {
        Box(modifier = Modifier.aspectRatio(2f / 3f)) {
            if (poster != null) {
                AsyncImage(
                    model = poster,
                    contentDescription = title.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Image(
                    painter = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
                Text(
                    text = title.title,
                    modifier = Modifier.align(Alignment.Center).padding(6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)) { StatusBadge(status = title.status) }
            progress?.takeIf { it > 0.005f }?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter)) }
        }
        Text(
            text = title.title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ProgressLine(progress: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.7f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(WatchProgressRed),
        )
    }
}
