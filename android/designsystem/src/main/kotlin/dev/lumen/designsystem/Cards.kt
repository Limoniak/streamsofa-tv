package dev.lumen.designsystem

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/** Dimensions communes : les rangées et les grilles s'alignent sur ces valeurs. */
object LumenCardSize {
    val streamWidth = 224.dp
    val categoryWidth = 148.dp
}

private val CardShape = RoundedCornerShape(12.dp)
private const val FIVE_MINUTES_MS = 5 * 60 * 1000L

/**
 * Focus très visible à distance : la carte grossit, s'éclaircit et prend un liseré d'accent.
 * Le texte reste en couleur pleine (il ne s'atténue jamais au focus).
 */
@Composable
private fun lumenCardColors() = CardDefaults.colors(
    containerColor = LumenColors.Surface,
    contentColor = LumenColors.OnSurface,
    focusedContainerColor = LumenColors.SurfaceRaised,
    focusedContentColor = LumenColors.OnSurface,
    pressedContainerColor = LumenColors.SurfaceRaised,
    pressedContentColor = LumenColors.OnSurface,
)

@Composable
private fun lumenCardBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, LumenColors.Accent), shape = CardShape),
)

@Composable
fun LumenCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier,
        shape = CardDefaults.shape(CardShape),
        colors = lumenCardColors(),
        border = lumenCardBorder(),
        scale = CardDefaults.scale(focusedScale = 1.06f),
    ) {
        content()
    }
}

/** Données minimales d'une carte de live : le module design ne dépend pas du cœur. */
data class StreamCardModel(
    val key: String,
    val title: String,
    val channelName: String,
    val categoryName: String,
    val viewers: Int,
    val thumbnailTemplate: String,
    val avatarUrl: String,
)

@Composable
fun StreamCard(stream: StreamCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val thumbWidth = LumenCardSize.streamWidth
    val thumbHeight = thumbWidth * 9 / 16
    val url = rememberSizedUrl(stream.thumbnailTemplate, thumbWidth, thumbHeight, FIVE_MINUTES_MS)

    LumenCard(onClick = onClick, modifier = modifier.width(thumbWidth)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(LumenColors.Skeleton),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                ViewerBadge(stream.viewers, Modifier.align(Alignment.BottomStart).padding(8.dp))
            }
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Avatar(stream.avatarUrl, stream.channelName, size = 36.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stream.channelName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stream.title,
                        style = MaterialTheme.typography.bodySmall,
                        color = LumenColors.OnSurfaceMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (stream.categoryName.isNotEmpty()) {
                        Text(
                            text = stream.categoryName,
                            style = MaterialTheme.typography.labelSmall,
                            color = LumenColors.Accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

data class CategoryCardModel(val key: String, val name: String, val viewers: Int, val boxArtTemplate: String)

@Composable
fun CategoryCard(category: CategoryCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val width = LumenCardSize.categoryWidth
    val height = width * 4 / 3
    val url = rememberSizedUrl(category.boxArtTemplate, width, height, 0)

    LumenCard(onClick = onClick, modifier = modifier.width(width)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(LumenColors.Skeleton),
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (category.viewers > 0) {
                    Text(
                        text = formatViewers(category.viewers),
                        style = MaterialTheme.typography.labelSmall,
                        color = LumenColors.OnSurfaceMuted,
                    )
                }
            }
        }
    }
}

@Composable
fun Avatar(url: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(LumenColors.SurfaceRaised),
        contentAlignment = Alignment.Center,
    ) {
        // L'initiale reste visible tant que l'image n'est pas chargée, ou si elle échoue.
        Text(
            text = name.take(1).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = LumenColors.Accent,
        )
        if (url.isNotEmpty()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
fun ViewerBadge(viewers: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(LumenColors.Background.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(LumenColors.Live, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text = formatViewers(viewers), style = MaterialTheme.typography.labelMedium, color = LumenColors.OnSurface)
    }
}

@Composable
private fun rememberSizedUrl(template: String, width: Dp, height: Dp, freshnessMs: Long): String {
    val density = LocalDensity.current
    return remember(template, width, height, density) {
        with(density) { sizedImageUrl(template, width.roundToPx(), height.roundToPx(), freshnessMs) }
    }
}

@Composable
fun SkeletonBox(modifier: Modifier = Modifier) {
    Box(modifier.background(LumenColors.Skeleton, RoundedCornerShape(8.dp)))
}

/** Emplacement d'une carte de live pendant le chargement. */
@Composable
fun StreamCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.width(LumenCardSize.streamWidth)) {
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Spacer(Modifier.height(12.dp))
        SkeletonBox(Modifier.width(160.dp).height(14.dp))
        Spacer(Modifier.height(8.dp))
        SkeletonBox(Modifier.width(220.dp).height(12.dp))
    }
}

@Composable
fun CategoryCardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.width(LumenCardSize.categoryWidth)) {
        SkeletonBox(Modifier.fillMaxWidth().aspectRatio(3f / 4f))
        Spacer(Modifier.height(8.dp))
        SkeletonBox(Modifier.width(100.dp).height(12.dp))
    }
}
