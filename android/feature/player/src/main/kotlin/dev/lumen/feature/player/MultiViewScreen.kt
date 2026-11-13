package dev.lumen.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.data.messageRes
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.requestWhenAttached
import kotlinx.coroutines.delay

private const val HINT_MS = 5_000L

/**
 * Multi-view : les flèches déplacent le son d'une tuile à l'autre, OK passe la tuile en plein
 * écran, un appui long sur OK la retire. Retour quitte le multi-view.
 */
@Composable
fun MultiViewRoute(onOpenSingle: (login: String) -> Unit, viewModel: MultiViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    var hint by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(HINT_MS)
        hint = false
    }

    // Suivi de l'appui sur OK à l'échelle de l'écran : après un retrait, le relâchement
    // arrive sur la tuile voisine, qui ne doit pas s'ouvrir pour autant.
    val press = remember { OkPress() }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { firstFocus.requestWhenAttached() }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        // 2 tuiles côte à côte, 3 ou 4 en grille 2 × 2 : des cellules 16:9 qui remplissent l'écran.
        Column(Modifier.fillMaxWidth()) {
            state.tiles.chunked(2).forEachIndexed { row, tiles ->
                Row(Modifier.fillMaxWidth()) {
                    tiles.forEachIndexed { column, tile ->
                        val index = row * 2 + column
                        Tile(
                            tile = tile,
                            player = viewModel.player(tile.login),
                            focused = index == state.focused,
                            press = press,
                            onFocus = { viewModel.focus(index) },
                            onOpen = { onOpenSingle(tile.login) },
                            onRetry = { viewModel.retry(tile.login) },
                            onRemove = { if (!viewModel.remove(tile.login)) onOpenSingle(tile.login) },
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(16f / 9f)
                                .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier),
                        )
                    }
                    // Grille de 3 : la dernière cellule reste noire, à la même taille.
                    if (tiles.size == 1 && state.tiles.size > 2) Box(Modifier.weight(1f).aspectRatio(16f / 9f))
                }
            }
        }
        if (hint) {
            Text(
                text = stringResource(R.string.multi_hint),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun Tile(
    tile: TileUiState,
    player: ExoPlayer?,
    focused: Boolean,
    press: OkPress,
    onFocus: () -> Unit,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(if (focused) LumenColors.Accent else Color.Black)
            .padding(4.dp)
            .onFocusChanged { if (it.isFocused) onFocus() }
            .onKeyEvent { event ->
                val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (!ok) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> when {
                        event.nativeKeyEvent.repeatCount == 0 -> press.state = OkPress.DOWN
                        press.state == OkPress.DOWN -> {
                            press.state = OkPress.CONSUMED
                            onRemove()
                        }
                    }
                    KeyEventType.KeyUp -> {
                        val wasShort = press.state == OkPress.DOWN
                        press.state = OkPress.IDLE
                        if (wasShort) if (tile.status is TileStatus.Failed) onRetry() else onOpen()
                    }
                }
                true
            }
            .focusable(),
    ) {
        if (player != null) {
            AndroidView(
                factory = { context ->
                    PlayerView(context).apply {
                        useController = false
                        isFocusable = false
                        keepScreenOn = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    }
                },
                update = { it.player = player },
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize().background(Color.Black),
            )
        }
        val message = when (val status = tile.status) {
            is TileStatus.Failed -> stringResource(status.error.messageRes)
            TileStatus.DecoderBusy -> stringResource(R.string.multi_decoder_busy)
            else -> null
        }
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleSmall,
                color = LumenColors.OnSurfaceMuted,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tile.stream?.displayName ?: tile.login,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (focused) Text("♪", style = MaterialTheme.typography.labelLarge, color = LumenColors.Accent)
        }
    }
}

/** Appui court = plein écran, appui long = retirer : on tranche au relâchement. */
private class OkPress {
    var state = IDLE

    companion object {
        const val IDLE = 0
        const val DOWN = 1
        const val CONSUMED = 2
    }
}
