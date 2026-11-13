package dev.lumen.feature.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.core.Raid
import dev.lumen.data.messageRes
import dev.lumen.designsystem.Avatar
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.MessageState
import dev.lumen.designsystem.formatUptime
import dev.lumen.designsystem.formatViewers
import dev.lumen.designsystem.requestWhenAttached
import kotlinx.coroutines.delay
import dev.lumen.data.R as DataR

private enum class Overlay { Hidden, Info, Actions }

private const val OVERLAY_TIMEOUT_MS = 5_000L

/** Place laissée au bouton agrandi par le focus, que la zone défilante rognerait. */
private val FOCUS_ROOM = 16.dp

/** Rangée défilante (quand le chat réduit la largeur) dont les bords ne rognent pas le bouton focalisé. */
@Composable
private fun Modifier.actionRow() = offset(x = -FOCUS_ROOM)
    .horizontalScroll(rememberScrollState())
    .padding(horizontal = FOCUS_ROOM, vertical = 6.dp)

/**
 * Télécommande, comme une télé : ▲ ▼ changent de chaîne suivie, ◀ ouvre la liste des lives,
 * ▶ affiche ou masque le chat, OK ouvre la barre d'actions (écrire, chat, qualité, multi-view,
 * page de la chaîne). Retour ferme d'abord ce qui est ouvert.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerRoute(
    onMultiView: (logins: List<String>) -> Unit = {},
    onOpenChannel: (login: String) -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chat by viewModel.chat.collectAsStateWithLifecycle()
    val canSend by viewModel.canSend.collectAsStateWithLifecycle()
    // Masqué par défaut (réglable) : la vidéo d'abord, Bas affiche le chat.
    var chatVisible by rememberSaveable { mutableStateOf(viewModel.chatByDefault) }
    var composing by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onStart() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }

    var overlay by remember { mutableStateOf(Overlay.Hidden) }
    var interactions by remember { mutableIntStateOf(0) }
    val rootFocus = remember { FocusRequester() }
    val controlsFocus = remember { FocusRequester() }
    val writeFocus = remember { FocusRequester() }
    var writeFocused by remember { mutableStateOf(false) }
    val canWrite = canSend && state.stream != null

    // Chaque changement de chaîne affiche brièvement son nom : on sait où l'on a zappé.
    LaunchedEffect(state.login) { if (overlay == Overlay.Hidden) overlay = Overlay.Info }
    // Le focus ne bouge qu'au changement de panneau : chaque touche relance le minuteur, pas le focus.
    LaunchedEffect(overlay) {
        if (overlay == Overlay.Actions) controlsFocus.requestWhenAttached() else if (!listing) rootFocus.requestWhenAttached()
    }
    LaunchedEffect(overlay, interactions) {
        if (overlay != Overlay.Hidden) {
            delay(OVERLAY_TIMEOUT_MS)
            overlay = Overlay.Hidden
        }
    }
    BackHandler(enabled = overlay == Overlay.Actions) { overlay = Overlay.Hidden }
    BackHandler(enabled = listing) { listing = false }
    BackHandler(enabled = writeFocused) { rootFocus.requestFocus() }
    BackHandler(enabled = state.raid != null) { viewModel.cancelRaid() }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown && overlay != Overlay.Hidden) interactions++
                false
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || overlay == Overlay.Actions || listing || writeFocused) return@onKeyEvent false
                val ok = event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter
                if (ok && state.raid != null) {
                    viewModel.followRaid()
                    return@onKeyEvent true
                }
                when (event.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (state.status is PlayerStatus.Failed) viewModel.retry() else overlay = Overlay.Actions
                        true
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        // Sans chaîne voisine, on montre au moins les infos et l'aide des touches.
                        if (state.canZap) viewModel.zap(if (event.key == Key.DirectionUp) 1 else -1) else overlay = Overlay.Info
                        true
                    }
                    Key.DirectionLeft -> {
                        overlay = Overlay.Hidden
                        listing = true
                        true
                    }
                    Key.DirectionRight -> {
                        // Chat affiché et compte connecté : ▶ va sur « Écrire » ; un second ▶ masque le chat.
                        if (chatVisible && canWrite) writeFocus.requestFocus() else chatVisible = !chatVisible
                        true
                    }
                    else -> false
                }
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
                text = stringResource(status.error.messageRes),
                actionLabel = stringResource(DataR.string.retry),
                onAction = viewModel::retry,
                modifier = Modifier.align(Alignment.Center),
            )
            else -> Unit
        }

        state.raid?.let { RaidBanner(it, Modifier.align(Alignment.TopStart)) }

        if (overlay != Overlay.Hidden) {
            InfoPanel(
                state = state,
                showActions = overlay == Overlay.Actions,
                controlsFocus = controlsFocus,
                chatVisible = chatVisible,
                onToggleChat = { chatVisible = !chatVisible },
                onQuality = viewModel::selectQuality,
                onMulti = if (viewModel.maxTiles > 1) {
                    {
                        overlay = Overlay.Hidden
                        picking = true
                    }
                } else {
                    null
                },
                onChannel = { onOpenChannel(state.login) },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
        if (listing) {
            LiveList(
                load = viewModel::liveList,
                current = state.login,
                onPick = {
                    listing = false
                    viewModel.open(it)
                },
                onDismiss = { listing = false },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
      }
        if (chatVisible) {
            Column(Modifier.width(340.dp).fillMaxHeight().background(LumenColors.Background)) {
                ChatPanel(chat, Modifier.weight(1f).fillMaxWidth())
                if (canWrite) {
                    WriteButton(
                        onClick = {
                            overlay = Overlay.Hidden
                            composing = true
                        },
                        onLeave = { rootFocus.requestFocus() },
                        onHideChat = {
                            rootFocus.requestFocus()
                            chatVisible = false
                        },
                        modifier = Modifier
                            .focusRequester(writeFocus)
                            .onFocusChanged { writeFocused = it.isFocused },
                    )
                }
            }
        }
    }

    if (composing) {
        ChatComposer(
            onSend = {
                viewModel.sendChat(it)
                composing = false
            },
            onDismiss = { composing = false },
        )
    }
    if (picking) {
        MultiPicker(
            load = viewModel::multiCandidates,
            maxExtra = viewModel.maxTiles - 1,
            onLaunch = {
                picking = false
                onMultiView(listOf(state.login) + it)
            },
            onDismiss = { picking = false },
        )
    }
    // Une boîte fermée, la télécommande revient au lecteur.
    LaunchedEffect(composing, picking, listing) { if (!composing && !picking && !listing) rootFocus.requestWhenAttached() }
}

@kotlin.OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun InfoPanel(
    state: PlayerUiState,
    showActions: Boolean,
    controlsFocus: FocusRequester,
    chatVisible: Boolean,
    onToggleChat: () -> Unit,
    onQuality: (String?) -> Unit,
    onMulti: (() -> Unit)?,
    onChannel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showQuality by remember(showActions) { mutableStateOf(false) }
    val qualityFocus = remember { FocusRequester() }
    LaunchedEffect(showQuality) { if (showQuality) qualityFocus.requestWhenAttached() }
    val stream = state.stream
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Avatar(stream?.avatarUrl.orEmpty(), stream?.displayName ?: state.login, size = 56.dp)
            Column(Modifier.weight(1f)) {
                Text(stream?.displayName ?: state.login, style = MaterialTheme.typography.headlineSmall)
                if (stream != null) {
                    Text(
                        text = stream.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOf(
                        stream.categoryName,
                        formatViewers(stream.viewers.toInt()),
                        formatUptime(stream.startedAt),
                    ).filter { it.isNotEmpty() }
                    Text(
                        text = details.joinToString("  ·  "),
                        style = MaterialTheme.typography.titleSmall,
                        color = LumenColors.Accent,
                    )
                }
            }
        }
        if (showActions) {
            Row(Modifier.actionRow(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onToggleChat, modifier = Modifier.focusRequester(controlsFocus)) {
                    Text(stringResource(if (chatVisible) R.string.player_chat_hide else R.string.player_chat_show))
                }
                if (state.variants.isNotEmpty()) {
                    Button(onClick = { showQuality = !showQuality }) {
                        Text(stringResource(R.string.player_quality, state.quality ?: stringResource(R.string.player_quality_auto)))
                    }
                }
                if (onMulti != null) Button(onClick = onMulti) { Text(stringResource(R.string.player_multi)) }
                Button(onClick = onChannel) { Text(stringResource(R.string.player_channel)) }
            }
            if (showQuality) {
                Row(Modifier.actionRow(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val options = listOf<String?>(null) + state.variants.map { it.name }
                    options.forEach { name ->
                        val selected = name == state.quality
                        FilterChip(
                            selected = selected,
                            onClick = { onQuality(name) },
                            modifier = if (selected) Modifier.focusRequester(qualityFocus) else Modifier,
                        ) {
                            Text(name ?: stringResource(R.string.player_quality_auto))
                        }
                    }
                }
            }
        }
        // L'aide des touches reste visible : rien n'est caché derrière une touche inconnue.
        Text(
            text = stringResource(if (state.canZap) R.string.player_keys_zap else R.string.player_keys),
            style = MaterialTheme.typography.labelLarge,
            color = LumenColors.OnSurfaceMuted,
        )
    }
}

/** « Écrire » en bas du chat : OK ouvre la saisie, ◀ revient à la vidéo, ▶ masque le chat. */
@Composable
private fun WriteButton(onClick: () -> Unit, onLeave: () -> Unit, onHideChat: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            // Marge assez large pour le bouton agrandi au focus.
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (it.key) {
                    Key.DirectionLeft -> onLeave()
                    Key.DirectionRight -> onHideChat()
                    // Seul élément du panneau : ▲ ▼ ne doivent pas zapper par-dessous.
                    Key.DirectionUp, Key.DirectionDown -> Unit
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        Text(stringResource(R.string.player_write))
    }
}

/** Compte à rebours affiché ; c'est le ViewModel qui ouvre la chaîne à la fin. */
@Composable
private fun RaidBanner(raid: Raid, modifier: Modifier = Modifier) {
    var remaining by remember(raid) { mutableIntStateOf(RAID_DELAY_S) }
    LaunchedEffect(raid) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    Column(
        modifier = modifier
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical)
            .background(Color.Black.copy(alpha = 0.8f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = stringResource(R.string.raid_title, raid.toName, formatViewers(raid.viewers.toInt())),
            style = MaterialTheme.typography.titleMedium,
            color = LumenColors.Accent,
        )
        Text(
            text = stringResource(R.string.raid_hint, remaining),
            style = MaterialTheme.typography.bodyMedium,
            color = LumenColors.OnSurfaceMuted,
        )
    }
}
