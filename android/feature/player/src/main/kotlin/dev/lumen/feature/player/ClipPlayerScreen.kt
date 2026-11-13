package dev.lumen.feature.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.lumen.core.ClipQuality
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.SettingsRepository
import dev.lumen.data.loadCatching
import dev.lumen.data.messageRes
import dev.lumen.data.toLoadError
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.MessageState
import dev.lumen.designsystem.requestWhenAttached
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import dev.lumen.data.R as DataR

/** Meilleure qualité qui ne dépasse pas [maxHeight] (0 = sans limite), sinon la plus basse. */
fun pickClipQuality(qualities: List<ClipQuality>, maxHeight: Int): ClipQuality? =
    if (maxHeight == 0) {
        qualities.maxByOrNull { it.height }
    } else {
        qualities.filter { it.height.toInt() <= maxHeight }.maxByOrNull { it.height } ?: qualities.minByOrNull { it.height }
    }

@HiltViewModel
class ClipViewModel @Inject constructor(
    @ApplicationContext context: Context,
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    private val slug: String = checkNotNull(savedState["slug"])
    val title: String = savedState["title"] ?: ""
    private val _status = MutableStateFlow<PlayerStatus>(PlayerStatus.Loading)
    val status: StateFlow<PlayerStatus> = _status.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply { playWhenReady = true }

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) _status.value = PlayerStatus.Playing
            }

            override fun onPlayerError(error: PlaybackException) {
                _status.value = PlayerStatus.Failed(if (error.errorCode in 2000..2999) LoadError.Network else LoadError.Other)
            }
        })
        load()
    }

    fun retry() = load()

    fun togglePause() {
        // Clip fini : OK le relance depuis le début.
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekBy(deltaMs: Long) = player.seekTo((player.currentPosition + deltaMs).coerceAtLeast(0))

    fun onStop() = player.pause()

    private fun load() {
        _status.value = PlayerStatus.Loading
        viewModelScope.launch {
            loadCatching { catalog.clipPlayback(slug) }
                .onSuccess { qualities ->
                    val quality = pickClipQuality(qualities, settings.state.value.maxQuality)
                    if (quality == null) {
                        _status.value = PlayerStatus.Failed(LoadError.Unavailable)
                        return@onSuccess
                    }
                    player.setMediaItem(MediaItem.fromUri(quality.url))
                    player.prepare()
                }
                .onFailure { _status.value = PlayerStatus.Failed(it.toLoadError()) }
        }
    }

    override fun onCleared() = player.release()
}

private const val CLIP_SEEK_MS = 5_000L
private const val CLIP_TITLE_MS = 4_000L

/** Lecteur de clip : OK pause ou relance, gauche/droite ±5 s. */
@OptIn(UnstableApi::class)
@Composable
fun ClipPlayerRoute(viewModel: ClipViewModel = hiltViewModel()) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    var showTitle by remember { mutableIntStateOf(1) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    LaunchedEffect(Unit) { focus.requestWhenAttached() }
    LaunchedEffect(showTitle) {
        delay(CLIP_TITLE_MS)
        showTitle = 0
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val handled = when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                        if (status is PlayerStatus.Failed) viewModel.retry() else viewModel.togglePause()
                        true
                    }
                    Key.DirectionLeft -> {
                        viewModel.seekBy(-CLIP_SEEK_MS)
                        true
                    }
                    Key.DirectionRight -> {
                        viewModel.seekBy(CLIP_SEEK_MS)
                        true
                    }
                    else -> false
                }
                if (handled) showTitle++
                handled
            }
            .focusRequester(focus)
            .focusable(),
    ) {
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
        (status as? PlayerStatus.Failed)?.let {
            MessageState(
                text = stringResource(it.error.messageRes),
                actionLabel = stringResource(DataR.string.retry),
                onAction = viewModel::retry,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (showTitle > 0 && viewModel.title.isNotEmpty()) {
            Text(
                text = viewModel.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical)
                    .padding(top = 48.dp),
            )
        }
    }
}
