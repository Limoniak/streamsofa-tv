package dev.lumen.feature.home

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.core.SearchChannel
import dev.lumen.core.SearchResults
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.loadCatching
import dev.lumen.data.messageRes
import dev.lumen.data.toLoadError
import dev.lumen.designsystem.CategoryCard
import dev.lumen.designsystem.ChannelCard
import dev.lumen.designsystem.ChannelCardModel
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.rememberFocusMemory
import dev.lumen.designsystem.requestWhenAttached
import dev.lumen.designsystem.restoreFocusHere
import javax.inject.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val results: SearchResults? = null,
    val loading: Boolean = false,
    val error: LoadError? = null,
)

/** Pause de frappe avant de lancer la requête : une par mot, pas une par lettre. */
private const val SEARCH_DEBOUNCE_MS = 400L

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(query = savedState["q"] ?: ""))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            _state.map { it.query.trim() }.distinctUntilChanged().debounce(SEARCH_DEBOUNCE_MS).collectLatest { query ->
                if (query.length < 2) {
                    _state.update { it.copy(results = null, loading = false, error = null) }
                    return@collectLatest
                }
                _state.update { it.copy(loading = true, error = null) }
                loadCatching { catalog.search(query) }
                    .onSuccess { r -> _state.update { it.copy(results = r, loading = false) } }
                    .onFailure { e -> _state.update { it.copy(loading = false, error = e.toLoadError()) } }
            }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query.take(100)) }
}

@Composable
fun SearchRoute(
    onOpenStream: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (id: String, name: String) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SearchScreen(state, viewModel::setQuery, onOpenStream, onOpenChannel, onOpenCategory)
}

/** Chaînes en direct : OK ouvre le live ; hors ligne : la page de la chaîne. */
@Composable
fun SearchScreen(
    state: SearchUiState,
    onQuery: (String) -> Unit,
    onOpenStream: (String) -> Unit,
    onOpenChannel: (String) -> Unit,
    onOpenCategory: (id: String, name: String) -> Unit,
) {
    val memory = rememberFocusMemory()
    val fieldFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var fieldFocused by remember { mutableStateOf(false) }
    val channels = state.results?.channels.orEmpty()
    val categories = state.results?.categories.orEmpty()
    val keys = channels.map { "s:${it.login}" } + categories.map { "c:${it.id}" }
    // Requête venue du launcher : le focus va au premier résultat, sans rouvrir le clavier.
    val prefilled = remember { state.query.isNotEmpty() }
    val target = memory.target(keys, if (prefilled) keys.firstOrNull() else null)
    // Au retour d'une chaîne, le focus revient sur sa carte ; sinon dans le champ (clavier ouvert).
    LaunchedEffect(target != null) { if (target != null) memory.requester.requestWhenAttached() }
    LaunchedEffect(Unit) { if (!prefilled && target == null) fieldFocus.requestWhenAttached() }

    val message = when {
        state.error != null -> stringResource(state.error.messageRes)
        state.loading && state.results == null -> stringResource(R.string.search_loading)
        state.results != null && channels.isEmpty() && categories.isEmpty() -> stringResource(R.string.search_empty)
        else -> null
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = LumenInsets.vertical),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(key = "field") {
            Box(
                modifier = Modifier
                    .padding(horizontal = LumenInsets.horizontal)
                    .width(640.dp)
                    .border(2.dp, if (fieldFocused) LumenColors.Accent else LumenColors.Skeleton, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                if (state.query.isEmpty()) {
                    Text(stringResource(R.string.search_hint), color = LumenColors.OnSurfaceMuted, fontSize = 20.sp)
                }
                BasicTextField(
                    value = state.query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = TextStyle(color = LumenColors.OnSurface, fontSize = 20.sp),
                    cursorBrush = SolidColor(LumenColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    // « Rechercher » du clavier : on le ferme et on descend vers les résultats.
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        focusManager.moveFocus(FocusDirection.Down)
                    }),
                    modifier = Modifier
                        .focusRequester(fieldFocus)
                        .onFocusChanged { fieldFocused = it.isFocused }
                        // Le champ garde les flèches pour le curseur : Bas mène aux résultats.
                        .onPreviewKeyEvent {
                            val down = it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown
                            if (down) focusManager.moveFocus(FocusDirection.Down)
                            down
                        },
                )
            }
        }
        if (message != null) {
            item(key = "message") {
                Text(
                    text = message,
                    style = MaterialTheme.typography.titleMedium,
                    color = LumenColors.OnSurfaceMuted,
                    modifier = Modifier.padding(horizontal = LumenInsets.horizontal),
                )
            }
        }
        if (channels.isNotEmpty()) {
            item(key = "channels") {
                ResultRow(stringResource(R.string.search_channels)) {
                    val liveLabel = stringResource(R.string.search_live)
                    CardRow(channels, key = { it.login }) { channel ->
                        val key = "s:${channel.login}"
                        ChannelCard(
                            channel = channel.toCard(liveLabel),
                            onClick = {
                                memory.key = key
                                if (channel.live) onOpenStream(channel.login) else onOpenChannel(channel.login)
                            },
                            modifier = Modifier.restoreFocusHere(memory, key == target),
                        )
                    }
                }
            }
        }
        if (categories.isNotEmpty()) {
            item(key = "categories") {
                ResultRow(stringResource(R.string.search_categories)) {
                    CardRow(categories, key = { it.id }) { category ->
                        val key = "c:${category.id}"
                        CategoryCard(
                            category = category.toCard(),
                            onClick = {
                                memory.key = key
                                onOpenCategory(category.id, category.name)
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
private fun ResultRow(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = LumenInsets.horizontal))
        content()
    }
}

private fun SearchChannel.toCard(liveLabel: String) = ChannelCardModel(
    key = "s:$login",
    name = displayName,
    avatarUrl = avatarUrl,
    subtitle = if (live) listOf(liveLabel, categoryName).filter { it.isNotEmpty() }.joinToString(" · ") else "",
)
