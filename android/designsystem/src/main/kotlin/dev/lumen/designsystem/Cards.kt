package dev.lumen.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.NativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.transform.CircleCropTransformation

/** Dimensions communes : les rangées et les grilles s'alignent sur ces valeurs. */
object LumenCardSize {
    val streamWidth = 224.dp
    val categoryWidth = 148.dp
}

private val CardCorner = 12.dp
private val CardShape = RoundedCornerShape(CardCorner)
private const val FIVE_MINUTES_MS = 5 * 60 * 1000L

private const val FOCUSED_SCALE = 1.06f
private val FocusBorderWidth = 3.dp
/** Courbe et durées du zoom de focus de tv-material, pour garder le même mouvement. */
private val ScaleEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

/**
 * Carte focalisable. Focus très visible à distance : la carte grossit, s'éclaircit et prend un liseré
 * d'accent. Le texte reste en couleur pleine (il ne s'atténue jamais au focus).
 *
 * Même aspect et mêmes touches que la `Card` de tv-material, qu'elle remplace pour alléger le rendu :
 * un seul calque graphique par carte au lieu de trois (zoom, découpe arrondie, opacité du contenu), et
 * un zoom animé dans ce calque, sans recomposer la carte à chaque image de l'animation.
 * Seule différence visible : le liseré de focus est tracé à l'intérieur du bord (il débordait de 1,5 dp).
 */
@Composable
fun LumenCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    // Appui long déjà servi : le relâchement de OK ne doit pas, en plus, cliquer.
    val longClicked = remember { booleanArrayOf(false) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(focused, pressed) {
        val target = if (focused && !pressed) FOCUSED_SCALE else 1f
        if (scale.targetValue != target) {
            val duration = if (pressed) 120 else if (focused) 300 else 500
            scale.animateTo(target, tween(duration, easing = ScaleEasing))
        }
    }
    val accent = LumenColors.Accent
    val container = if (focused) LumenColors.SurfaceRaised else LumenColors.Surface

    Box(
        modifier = modifier
            .onFocusChanged {
                focused = it.isFocused
                if (!it.isFocused) pressed = false
            }
            .onKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.keyCode) {
                    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> Unit
                    else -> return@onKeyEvent false
                }
                if (native.action == NativeKeyEvent.ACTION_DOWN) {
                    if (native.repeatCount == 0) {
                        pressed = true
                    } else if (native.repeatCount == 1 && onLongClick != null) {
                        longClicked[0] = true
                        pressed = false
                        onLongClick()
                    }
                } else if (native.action == NativeKeyEvent.ACTION_UP) {
                    if (longClicked[0]) {
                        longClicked[0] = false
                    } else {
                        pressed = false
                        onClick()
                    }
                }
                true
            }
            .focusable()
            .semantics(mergeDescendants = true) {
                onClick {
                    onClick()
                    true
                }
                onLongClick {
                    onLongClick?.invoke()
                    onLongClick != null
                }
            }
            // La carte focalisée, agrandie, passe au-dessus de ses voisines.
            .zIndex(if (focused) 0.5f else 0f)
            .graphicsLayer {
                val s = scale.value
                scaleX = s
                scaleY = s
                shape = CardShape
                clip = true
            }
            .drawWithContent {
                drawRect(container)
                drawContent()
                if (focused) {
                    val stroke = FocusBorderWidth.toPx()
                    val radius = CardCorner.toPx() - stroke / 2
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius, radius),
                        style = Stroke(stroke),
                    )
                }
            },
        propagateMinConstraints = true,
    ) {
        CompositionLocalProvider(LocalContentColor provides LumenColors.OnSurface) { content() }
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
fun StreamCard(
    stream: StreamCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val thumbWidth = LumenCardSize.streamWidth
    val thumbHeight = thumbWidth * 9 / 16
    val url = rememberSizedUrl(stream.thumbnailTemplate, thumbWidth, thumbHeight, FIVE_MINUTES_MS)

    LumenCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(thumbWidth)) {
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

data class VideoCardModel(
    val key: String,
    val title: String,
    /** Date et vues, déjà mises en forme. */
    val subtitle: String,
    val thumbnailTemplate: String,
    val durationS: Long,
    val positionS: Long,
    val finished: Boolean,
)

/** Carte de VOD : durée en incrustation, barre de reprise sous l'image. */
@Composable
fun VideoCard(video: VideoCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val thumbWidth = LumenCardSize.streamWidth
    val url = rememberSizedUrl(video.thumbnailTemplate, thumbWidth, thumbWidth * 9 / 16, 0)
    val progress = when {
        video.finished -> 1f
        video.durationS > 0 -> (video.positionS.toFloat() / video.durationS).coerceIn(0f, 1f)
        else -> 0f
    }

    LumenCard(onClick = onClick, modifier = modifier.width(thumbWidth)) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(LumenColors.Skeleton),
            ) {
                if (url.isNotEmpty()) {
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Text(
                    text = formatDuration(video.durationS),
                    style = MaterialTheme.typography.labelMedium,
                    color = LumenColors.OnSurface,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(LumenColors.Background.copy(alpha = 0.8f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
                if (progress > 0f) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(LumenColors.Skeleton)) {
                        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LumenColors.Accent))
                    }
                }
            }
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = video.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = LumenColors.OnSurfaceMuted,
                    maxLines = 1,
                )
            }
        }
    }
}

/** [subtitle] : par exemple « En direct · Catégorie » dans la recherche. */
data class ChannelCardModel(val key: String, val name: String, val avatarUrl: String, val subtitle: String = "")

/** Chaîne suivie, en live ou non : ouvre sa page (live et VOD). */
@Composable
fun ChannelCard(channel: ChannelCardModel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    LumenCard(onClick = onClick, modifier = modifier.width(LumenCardSize.streamWidth)) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Avatar(channel.avatarUrl, channel.name, size = 48.dp)
            Column {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (channel.subtitle.isNotEmpty()) {
                    Text(
                        text = channel.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = LumenColors.Accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

data class CategoryCardModel(val key: String, val name: String, val viewers: Int, val boxArtTemplate: String)

@Composable
fun CategoryCard(
    category: CategoryCardModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val width = LumenCardSize.categoryWidth
    val height = width * 4 / 3
    val url = rememberSizedUrl(category.boxArtTemplate, width, height, 0)

    LumenCard(onClick = onClick, onLongClick = onLongClick, modifier = modifier.width(width)) {
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

/**
 * Avatar rond. L'image est arrondie au décodage (`CircleCropTransformation`) et le fond est un disque
 * dessiné : pas de découpe, donc pas de calque graphique de plus par carte.
 */
@Composable
fun Avatar(url: String, name: String, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(size).background(LumenColors.SurfaceRaised, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        // L'initiale reste visible tant que l'image n'est pas chargée, ou si elle échoue ; ensuite elle
        // n'est plus dessinée sous l'image (un texte de moins par carte à chaque image du défilement).
        var loaded by remember(url) { mutableStateOf(false) }
        if (!loaded) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = LumenColors.Accent,
            )
        }
        if (url.isNotEmpty()) {
            val context = LocalPlatformContext.current
            val px = with(LocalDensity.current) { size.roundToPx() }
            val request = remember(url, px, context) {
                ImageRequest.Builder(context).data(sizedAvatarUrl(url, px)).transformations(CircleCropTransformation()).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                modifier = Modifier.size(size),
            )
        }
    }
}

/** Tailles d'avatar que sert le CDN de Twitch. */
private val AvatarSizes = intArrayOf(28, 50, 70, 150, 300, 600)
private val AvatarSize = Regex("-profile_image-(\\d+)x\\1\\.")

/**
 * Avatar Twitch à la taille affichée : Helix renvoie toujours du 300×300, soit jusqu'à 18 fois trop de
 * pixels à télécharger et décoder pour une pastille de 36 dp. Une adresse d'une autre forme est rendue telle quelle.
 */
internal fun sizedAvatarUrl(url: String, px: Int): String {
    val match = AvatarSize.find(url) ?: return url
    // 5 % de marge : 72 px affichés se contentent du 70×70.
    val wanted = AvatarSizes.firstOrNull { it * 105 >= px * 100 } ?: AvatarSizes.last()
    return url.replaceRange(match.range, "-profile_image-${wanted}x$wanted.")
}

@Composable
fun ViewerBadge(viewers: Int, modifier: Modifier = Modifier) {
    val spoken = stringResource(R.string.a11y_viewers, formatViewers(viewers))
    Row(
        modifier = modifier
            // TalkBack : « 1,2 k spectateurs » plutôt qu'un nombre seul.
            .clearAndSetSemantics { contentDescription = spoken }
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
