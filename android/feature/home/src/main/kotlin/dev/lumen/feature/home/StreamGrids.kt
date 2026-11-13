package dev.lumen.feature.home

import dev.lumen.designsystem.VideoCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.MaterialTheme
import java.util.Locale
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.core.Channel
import dev.lumen.core.Clip
import dev.lumen.core.LiveStream
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.SettingsRepository
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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
    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()

    init {
        reload()
    }

    fun reload() {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // Secondaires : en cas d'échec, les lives suffisent.
            launch { loadCatching { catalog.followedChannels() }.onSuccess { _channels.value = it } }
            launch { loadCatching { catalog.followedClips() }.onSuccess { _clips.value = it } }
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
    private val settings: SettingsRepository,
) : ViewModel() {
    private val categoryId: String = checkNotNull(savedState["id"])
    val name: String = savedState["name"] ?: ""

    private val _state = MutableStateFlow(GridUiState())
    val state: StateFlow<GridUiState> = _state.asStateFlow()
    private var job: Job? = null
    private val _clips = MutableStateFlow<List<Clip>>(emptyList())
    val clips: StateFlow<List<Clip>> = _clips.asStateFlow()
    private var clipsJob: Job? = null

    /** Code ISO 639-1, vide = toutes ; mémorisé pour toutes les catégories. */
    val language: StateFlow<String> = settings.state.map { it.streamLanguage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.state.value.streamLanguage)

    init {
        viewModelScope.launch {
            // Préférences lues en base avant la première page : sinon elle partirait sans filtre.
            settings.awaitLoaded()
            language.collect { reload() }
        }
    }

    fun setLanguage(code: String) = settings.update { copy(streamLanguage = code) }

    fun reload() {
        job?.cancel()
        _state.value = GridUiState()
        load(null)
        clipsJob?.cancel()
        _clips.value = emptyList()
        // Secondaires : sans clips, la grille des lives suffit.
        clipsJob = viewModelScope.launch {
            loadCatching { catalog.categoryClips(categoryId, language.value.ifEmpty { null }) }.onSuccess { _clips.value = it }
        }
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
            loadCatching { catalog.categoryStreams(categoryId, cursor, language.value.ifEmpty { null }) }
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
    onOpenClip: (String, String) -> Unit,
    viewModel: FollowingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val channels by viewModel.channels.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    StreamGridScreen(
        title = stringResource(R.string.following_title),
        state = state,
        emptyText = stringResource(R.string.home_no_followed),
        onOpenStream = onOpenStream,
        onOpenChannel = onOpenChannel,
        channels = channels,
        clips = ClipRow(stringResource(R.string.following_clips), clips, onTop = false),
        onOpenClip = onOpenClip,
        onRetry = viewModel::reload,
        onNearEnd = {},
        onSignIn = onSignIn,
    )
}

@Composable
fun CategoryRoute(
    onOpenStream: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenClip: (String, String) -> Unit,
    viewModel: CategoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language by viewModel.language.collectAsStateWithLifecycle()
    val clips by viewModel.clips.collectAsStateWithLifecycle()
    StreamGridScreen(
        title = viewModel.name,
        header = { LanguageChips(language, viewModel::setLanguage) },
        state = state,
        emptyText = stringResource(R.string.category_empty),
        onOpenStream = onOpenStream,
        onOpenChannel = onOpenChannel,
        // En tête : la grille des lives se prolonge page après page, on n'atteindrait jamais sa fin.
        clips = ClipRow(stringResource(R.string.category_clips), clips, onTop = true),
        onOpenClip = onOpenClip,
        onRetry = viewModel::reload,
        onNearEnd = viewModel::loadMore,
        onSignIn = {},
    )
}

/** Rangée de clips d'une grille : en tête ([onTop]) ou entre les lives et les chaînes. */
data class ClipRow(val title: String, val clips: List<Clip>, val onTop: Boolean)

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
    clips: ClipRow? = null,
    onOpenClip: (String, String) -> Unit = { _, _ -> },
    header: @Composable () -> Unit = {},
) {
    val memory = rememberFocusMemory()
    val clipKeys = clips?.clips.orEmpty().map { "clip:${it.slug}" }
    val keys = state.items.map { it.login } + clipKeys + channels.map { "ch:${it.login}" }
    val target = memory.target(keys, keys.firstOrNull())
    LaunchedEffect(target != null) { if (target != null) memory.requester.requestWhenAttached() }
    val gridState = rememberLazyGridState()
    // Clips en tête arrivés après les lives : la grille resterait calée sous eux.
    val clipsOnTop = clips?.onTop == true && clips.clips.isNotEmpty()
    LaunchedEffect(clipsOnTop) { if (clipsOnTop && gridState.firstVisibleItemIndex <= 1) gridState.scrollToItem(0) }

    Column(Modifier.fillMaxSize().padding(top = LumenInsets.vertical)) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = LumenInsets.horizontal),
        )
        header()
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
                state = gridState,
                contentPadding = PaddingValues(horizontal = LumenInsets.horizontal, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                val clipRow: () -> Unit = {
                    if (clips != null && clips.clips.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "clips") {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(clips.title, style = MaterialTheme.typography.titleLarge)
                                val viewsLabel = stringResource(R.string.video_views)
                                CardRow(clips.clips, key = { "clip:${it.slug}" }, padded = false) { clip ->
                                    val key = "clip:${clip.slug}"
                                    VideoCard(
                                        video = clip.toCard(viewsLabel, showChannel = true),
                                        onClick = {
                                            memory.key = key
                                            onOpenClip(clip.slug, clip.title)
                                        },
                                        modifier = Modifier.restoreFocusHere(memory, key == target),
                                    )
                                }
                            }
                        }
                    }
                }
                if (clips?.onTop == true) clipRow()
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
                if (clips?.onTop == false) clipRow()
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

/** Langues proposées : les plus courantes sur Twitch, « Toutes » en premier. */
private val LANGUAGES = listOf("", "fr", "en", "es", "de", "pt", "it")

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LanguageChips(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = LumenInsets.horizontal, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LANGUAGES.forEach { code ->
            FilterChip(selected = code == selected, onClick = { onSelect(code) }) {
                Text(if (code.isEmpty()) stringResource(R.string.language_all) else languageName(code))
            }
        }
    }
}

/** Nom de la langue dans cette langue (« Deutsch ») : lisible quelle que soit celle de l'appareil. */
private fun languageName(code: String): String {
    val locale = Locale(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}
