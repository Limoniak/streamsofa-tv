package dev.lumen.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.core.Channel
import dev.lumen.core.LiveStream
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.loadCatching
import dev.lumen.data.messageRes
import dev.lumen.data.toLoadError
import dev.lumen.designsystem.ChannelCard
import dev.lumen.designsystem.LumenCardSize
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.MessageState
import dev.lumen.designsystem.StreamCard
import dev.lumen.designsystem.StreamCardSkeleton
import dev.lumen.designsystem.rememberFocusMemory
import dev.lumen.designsystem.requestWhenAttached
import dev.lumen.designsystem.restoreFocusHere
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.lumen.data.R as DataR

data class GridUiState(
    val items: List<LiveStream> = emptyList(),
    val loading: Boolean = true,
    val error: LoadError? = null,
    /** `null` : plus de page à charger. */
    val cursor: String? = null,
)

/**
 * Lives suivis puis toutes les chaînes suivies (hors ligne comprises) : deux requêtes,
 * le cœur parcourt déjà toutes les pages.
 */
@HiltViewModel
class FollowingViewModel @Inject constructor(private val catalog: CatalogRepository) : ViewModel() {
    private val _state = MutableStateFlow(GridUiState())
    val state: StateFlow<GridUiState> = _state.asStateFlow()
    private val _channels = MutableStateFlow<List<Channel>>(emptyList())
    val channels: StateFlow<List<Channel>> = _channels.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // Secondaires : en cas d'échec, les lives suffisent.
            launch { loadCatching { catalog.followedChannels() }.onSuccess { _channels.value = it } }
            loadCatching { catalog.followedStreams() }
                .onSuccess { items -> _state.value = GridUiState(items = items, loading = false) }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toLoadError()) } }
        }
    }
}

/** Lives d'une catégorie, page par page à l'approche de la fin de la grille. */
@HiltViewModel
class CategoryViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val categoryId: String = checkNotNull(savedState["id"])
    val name: String = savedState["name"] ?: ""

    private val _state = MutableStateFlow(GridUiState())
    val state: StateFlow<GridUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        reload()
    }

    fun reload() {
        job?.cancel()
        _state.value = GridUiState()
        load(null)
    }

    fun loadMore() {
        val current = _state.value
        val cursor = current.cursor ?: return
        if (current.loading || job?.isActive == true) return
        _state.update { it.copy(loading = true) }
        load(cursor)
    }

    private fun load(cursor: String?) {
        job = viewModelScope.launch {
            loadCatching { catalog.categoryStreams(categoryId, cursor) }
                .onSuccess { page ->
                    _state.update { old ->
                        // Une chaîne peut glisser d'une page à l'autre entre deux requêtes.
                        val items = (old.items + page.items).distinctBy { it.login }
                        old.copy(items = items, loading = false, error = null, cursor = page.cursor)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toLoadError()) } }
        }
    }
}

@Composable
fun FollowingRoute(
    onOpenStream: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onSignIn: () -> Unit,
    viewModel: FollowingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    StreamGridScreen(
        title = stringResource(R.string.following_title),
        state = state,
        emptyText = stringResource(R.string.home_no_followed),
        onOpenStream = onOpenStream,
        onOpenChannel = onOpenChannel,
        channels = channels,
        onRetry = viewModel::reload,
        onNearEnd = {},
        onSignIn = onSignIn,
    )
}

@Composable
fun CategoryRoute(
    onOpenStream: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    viewModel: CategoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StreamGridScreen(
        title = viewModel.name,
        state = state,
        emptyText = stringResource(R.string.category_empty),
        onOpenStream = onOpenStream,
        onOpenChannel = onOpenChannel,
        onRetry = viewModel::reload,
        onNearEnd = viewModel::loadMore,
        onSignIn = {},
    )
}

@Composable
fun StreamGridScreen(
    title: String,
    state: GridUiState,
    emptyText: String,
    onOpenStream: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onRetry: () -> Unit,
    onNearEnd: () -> Unit,
    onSignIn: () -> Unit,
    channels: List<Channel> = emptyList(),
) {
    val memory = rememberFocusMemory()
    val keys = state.items.map { it.login } + channels.map { "ch:${it.login}" }
    val target = memory.target(keys, keys.firstOrNull())
    LaunchedEffect(target != null) { if (target != null) memory.requester.requestWhenAttached() }

    Column(Modifier.fillMaxSize().padding(top = LumenInsets.vertical)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = LumenInsets.horizontal),
        )
        when {
            state.items.isEmpty() && state.error != null -> CenteredMessage {
                val signIn = state.error == LoadError.NotSignedIn
                MessageState(
                    text = stringResource(state.error.messageRes),
                    actionLabel = stringResource(if (signIn) R.string.sign_in else DataR.string.retry),
                    onAction = if (signIn) onSignIn else onRetry,
                    takeFocus = true,
                )
            }
            state.items.isEmpty() && !state.loading && channels.isEmpty() -> CenteredMessage {
                MessageState(emptyText, actionLabel = stringResource(DataR.string.retry), onAction = onRetry, takeFocus = true)
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(LumenCardSize.streamWidth),
                contentPadding = PaddingValues(horizontal = LumenInsets.horizontal, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (state.items.isEmpty()) {
                    items(8) { StreamCardSkeleton() }
                }
                itemsIndexed(state.items, key = { _, it -> it.login }) { index, stream ->
                    if (index >= state.items.size - 8) LaunchedEffect(Unit) { onNearEnd() }
                    StreamCard(
                        stream = stream.toCard("g"),
                        onClick = {
                            memory.key = stream.login
                            onOpenStream(stream.login)
                        },
                        onLongClick = {
                            memory.key = stream.login
                            onOpenChannel(stream.login)
                        },
                        modifier = Modifier.restoreFocusHere(memory, stream.login == target),
                    )
                }
                if (state.items.isEmpty() && !state.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(emptyText, style = MaterialTheme.typography.bodyLarge, color = LumenColors.OnSurfaceMuted)
                    }
                }
                if (channels.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = stringResource(R.string.following_channels),
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    items(channels, key = { "ch:${it.login}" }) { channel ->
                        val key = "ch:${channel.login}"
                        ChannelCard(
                            channel = channel.toCard(),
                            onClick = {
                                memory.key = key
                                onOpenChannel(channel.login)
                            },
                            modifier = Modifier.restoreFocusHere(memory, key == target),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
