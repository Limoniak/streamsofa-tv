package dev.lumen.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.data.messageRes
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.MessageState
import dev.lumen.designsystem.formatDuration
import dev.lumen.designsystem.requestWhenAttached
import kotlinx.coroutines.delay
import dev.lumen.data.R as DataR

private const val SEEK_STEP_MS = 10_000L
/** Touche maintenue : on avance plus vite. */
private const val SEEK_FAST_MS = 60_000L
private const val VOD_OVERLAY_TIMEOUT_MS = 4_000L

/**
 * Lecteur de VOD. OK met en pause ou reprend, gauche/droite recule ou avance de 10 s
 * (1 min en maintenant), haut change la vitesse, bas affiche le replay du chat.
 * Les chapitres (changements de jeu) sont marqués sur la barre. La reprise est enregistrée en continu.
 */
@OptIn(UnstableApi::class)
@Composable
fun VodPlayerRoute(viewModel: VodViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clock by viewModel.clock.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    var chatVisible by rememberSaveable { mutableStateOf(viewModel.chatByDefault) }
    var overlay by remember { mutableStateOf(true) }
    var interactions by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    LaunchedEffect(Unit) { rootFocus.requestWhenAttached() }
    LaunchedEffect(chatVisible) { viewModel.setChatVisible(chatVisible) }
    // En pause, les infos restent affichées ; sinon elles s'effacent après quelques secondes.
    LaunchedEffect(overlay, interactions, clock.paused) {
        if (overlay && !clock.paused) {
            delay(VOD_OVERLAY_TIMEOUT_MS)
            overlay = false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val step = if (event.nativeKeyEvent.repeatCount > 0) SEEK_FAST_MS else SEEK_STEP_MS
                val handled = when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (state.status is PlayerStatus.Failed) viewModel.retry() else viewModel.togglePause()
                        true
                    }
                    Key.DirectionLeft, Key.MediaRewind -> {
                        viewModel.seekBy(-step)
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> {
                        viewModel.seekBy(step)
                        true
                    }
                    Key.DirectionDown -> {
                        chatVisible = !chatVisible
                        true
                    }
                    Key.DirectionUp -> {
                        viewModel.cycleSpeed()
                        true
                    }
                    else -> false
                }
                if (handled) {
                    overlay = true
                    interactions++
                }
                handled
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        Box(Modifier.weight(1f).fillMaxHeight()) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        isFocusable = false
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                        player = viewModel.player
                    }
                },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )

            when (val status = state.status) {
                is PlayerStatus.Failed -> MessageState(
                    text = state.detail ?: stringResource(status.error.messageRes),
                    actionLabel = stringResource(DataR.string.retry),
                    onAction = viewModel::retry,
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> Unit
            }

            if (chatVisible && viewModel.chatLook.overlay) {
                ChatPanel(chat, Modifier.align(Alignment.CenterEnd).width(viewModel.chatLook.width).fillMaxHeight(), look = viewModel.chatLook)
            }
            if (overlay) {
                VodInfo(state, clock, Modifier.align(Alignment.BottomStart))
            }
        }
        if (chatVisible && !viewModel.chatLook.overlay) {
            ChatPanel(chat, Modifier.width(viewModel.chatLook.width).fillMaxHeight(), look = viewModel.chatLook)
        }
    }
}

@Composable
private fun VodInfo(state: VodUiState, clock: VodClock, modifier: Modifier = Modifier) {
    val video = state.video
    val durationMs = clock.durationMs.takeIf { it > 0 } ?: (video?.durationS?.toLong() ?: 0) * 1000
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (video != null) {
            Text(video.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val chapter = state.chapters.takeIf { it.size > 1 }?.at(clock.positionMs)
            Text(
                text = listOfNotNull(video.channelName, chapter?.title).joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = LumenColors.Accent,
            )
        }
        val progress = if (durationMs > 0) (clock.positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(LumenColors.Skeleton, RoundedCornerShape(3.dp)),
        ) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(LumenColors.Accent, RoundedCornerShape(3.dp)))
            // Début de chaque chapitre (sauf le premier, à 0).
            if (durationMs > 0) {
                state.chapters.filter { it.positionMs > 0u }.forEach { chapter ->
                    val x = maxWidth * (chapter.positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
                    Box(Modifier.offset(x = x - 1.5.dp).width(3.dp).fillMaxHeight().background(LumenColors.OnSurface))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${formatDuration(clock.positionMs / 1000)} / ${formatDuration(durationMs / 1000)}",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state.speed != 1f) {
                Text(
                    text = stringResource(R.string.vod_speed, state.speed.toString().removeSuffix(".0")),
                    style = MaterialTheme.typography.titleMedium,
                    color = LumenColors.Accent,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
            if (clock.paused) {
                Text(
                    text = stringResource(R.string.vod_paused),
                    style = MaterialTheme.typography.titleMedium,
                    color = LumenColors.Accent,
                    modifier = Modifier.padding(start = 24.dp),
                )
            }
            Box(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.vod_seek_hint),
                style = MaterialTheme.typography.labelLarge,
                color = LumenColors.OnSurfaceMuted,
            )
        }
    }
}
