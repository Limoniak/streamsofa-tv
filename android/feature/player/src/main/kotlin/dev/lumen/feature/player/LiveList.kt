package dev.lumen.feature.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.core.LiveStream
import dev.lumen.designsystem.Avatar
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.formatViewers
import dev.lumen.designsystem.requestWhenAttached

/** Colonne des lives à gauche du lecteur : ▲ ▼ pour choisir, OK pour regarder, ◀ ou Retour pour fermer. */
@Composable
internal fun LiveList(
    load: suspend () -> List<LiveStream>,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var streams by remember { mutableStateOf<List<LiveStream>?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { streams = load() }
    LaunchedEffect(streams) { if (!streams.isNullOrEmpty()) focus.requestWhenAttached() }

    Column(
        modifier = modifier
            .width(440.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.88f))
            .padding(vertical = LumenInsets.vertical)
            .onPreviewKeyEvent {
                val left = it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft
                if (left) onDismiss()
                left
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.player_lives_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = LumenInsets.horizontal),
        )
        val list = streams
        when {
            list == null -> Hint(stringResource(R.string.multi_pick_loading))
            list.isEmpty() -> Hint(stringResource(R.string.multi_pick_empty))
            else -> {
                val start = list.indexOfFirst { it.login == current }.coerceAtLeast(0)
                val listState = rememberLazyListState(initialFirstVisibleItemIndex = start)
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(list, key = { it.login }) { stream ->
                        ListItem(
                            selected = stream.login == current,
                            onClick = { onPick(stream.login) },
                            leadingContent = { Avatar(stream.avatarUrl, stream.displayName, size = 40.dp) },
                            headlineContent = { Text(stream.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    text = listOf(stream.categoryName, formatViewers(stream.viewers.toInt()))
                                        .filter { it.isNotEmpty() }.joinToString("  ·  "),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            modifier = if (stream == list[start]) Modifier.focusRequester(focus) else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LumenColors.OnSurfaceMuted, modifier = Modifier.padding(horizontal = LumenInsets.horizontal))
}
