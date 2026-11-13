package dev.lumen.feature.home

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import dev.lumen.core.ChannelAbout
import dev.lumen.core.Clip
import dev.lumen.core.Panel
import dev.lumen.core.ScheduleSegment
import dev.lumen.core.LiveStream
import dev.lumen.core.Video
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.loadCatching
import dev.lumen.data.messageRes
import dev.lumen.data.toLoadError
import dev.lumen.data.withProgress
import dev.lumen.designsystem.Avatar
import coil3.compose.AsyncImage
import dev.lumen.designsystem.LumenCard
import dev.lumen.designsystem.LumenCardSize
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.MessageState
import dev.lumen.designsystem.StreamCard
import dev.lumen.designsystem.StreamCardSkeleton
import dev.lumen.designsystem.VideoCard
import dev.lumen.designsystem.rememberFocusMemory
import dev.lumen.designsystem.requestWhenAttached
import dev.lumen.designsystem.restoreFocusHere
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import dev.lumen.data.R as DataR
import dev.lumen.designsystem.QrCode
import dev.lumen.core.qrCode
import androidx.compose.runtime.remember

data class ChannelUiState(
    val channel: Channel? = null,
    val live: LiveStream? = null,
    /** La vérification du live est terminée (en ligne ou non). */
    val liveChecked: Boolean = false,
    val videos: List<Video> = emptyList(),
    val cursor: String? = null,
    val loading: Boolean = true,
    val error: LoadError? = null,
    val notFound: Boolean = false,
    val clips: List<Clip> = emptyList(),
    /** Planning et panneaux ; `null` tant qu'ils ne sont pas arrivés (ou en cas d'échec). */
    val about: ChannelAbout? = null,
)

/** Page d'une chaîne : son live s'il y en a un, puis ses VOD page par page. */
@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val login: String = checkNotNull(savedState["login"])
    private val _state = MutableStateFlow(ChannelUiState())

    /** Les reprises enregistrées par le lecteur s'appliquent sans recharger la page. */
    val state: StateFlow<ChannelUiState> = combine(_state, catalog.vodProgress) { state, progress ->
        if (progress.isEmpty()) state else state.copy(videos = state.videos.map { it.withProgress(progress) })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChannelUiState())

    private var job: Job? = null

    init {
        reload()
    }

    fun reload() {
        job?.cancel()
        _state.value = ChannelUiState()
        job = viewModelScope.launch {
            launch {
                val live = loadCatching { catalog.liveStream(login) }.getOrNull()
                _state.update { it.copy(live = live, liveChecked = true) }
            }
            // Secondaires : la page reste utilisable sans eux.
            launch { loadCatching { catalog.channelClips(login) }.onSuccess { c -> _state.update { it.copy(clips = c) } } }
            launch { loadCatching { catalog.channelAbout(login) }.onSuccess { a -> _state.update { it.copy(about = a) } } }
            loadCatching { catalog.channel(login) }
                .onSuccess { channel ->
                    if (channel == null) {
                        _state.update { it.copy(loading = false, notFound = true) }
                    } else {
                        _state.update { it.copy(channel = channel) }
                        loadVideos(channel.id, null)
                    }
                }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.toLoadError()) } }
        }
    }

    fun loadMore() {
        val current = _state.value
        val channel = current.channel ?: return
        val cursor = current.cursor ?: return
        if (current.loading || job?.isActive == true) return
        _state.update { it.copy(loading = true) }
        job = viewModelScope.launch { loadVideos(channel.id, cursor) }
    }

    private suspend fun loadVideos(channelId: String, cursor: String?) {
        loadCatching { catalog.channelVideos(channelId, cursor) }
            .onSuccess { page ->
                _state.update { old ->
                    old.copy(videos = (old.videos + page.items).distinctBy { it.id }, cursor = page.cursor, loading = false)
                }
            }
            .onFailure { e -> _state.update { it.copy(loading = false, error = e.toLoadError()) } }
    }
}

@Composable
fun ChannelRoute(
    onOpenStream: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenClip: (slug: String, title: String) -> Unit,
    viewModel: ChannelViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ChannelScreen(state, onOpenStream, onOpenVideo, onOpenClip, onRetry = viewModel::reload, onNearEnd = viewModel::loadMore)
}

@Composable
fun ChannelScreen(
    state: ChannelUiState,
    onOpenStream: (String) -> Unit,
    onOpenVideo: (String) -> Unit,
    onOpenClip: (slug: String, title: String) -> Unit,
    onRetry: () -> Unit,
    onNearEnd: () -> Unit,
) {
    val memory = rememberFocusMemory()
    val live = state.live
    val keys = listOfNotNull(live?.let { "live" }) + state.videos.map { "v:${it.id}" }
    // On attend le live et la première page : sinon le focus sauterait de l'un à l'autre.
    val settled = state.liveChecked && (!state.loading || state.videos.isNotEmpty())
    val target = if (settled) memory.target(keys, keys.firstOrNull()) else null
    LaunchedEffect(target != null) { if (target != null) memory.requester.requestWhenAttached() }

    when {
        state.notFound -> CenteredMessage(stringResource(R.string.channel_not_found), onAction = null)
        state.channel == null && state.error != null -> CenteredMessage(stringResource(state.error.messageRes), onRetry)
        // En-tête hors de la grille : il reste visible quand la grille défile.
        else -> Column(Modifier.fillMaxSize().padding(top = LumenInsets.vertical)) {
            ChannelHeader(state.channel, Modifier.padding(horizontal = LumenInsets.horizontal))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(LumenCardSize.streamWidth),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = LumenInsets.horizontal, vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (live != null) {
                    fullWidth { SectionTitle(stringResource(R.string.channel_live)) }
                    item(key = "live") {
                        StreamCard(
                            stream = live.toCard("live"),
                            onClick = {
                                memory.key = "live"
                                onOpenStream(live.login)
                            },
                            modifier = Modifier.restoreFocusHere(memory, target == "live"),
                        )
                    }
                }
                val schedule = state.about?.schedule.orEmpty()
                if (schedule.isNotEmpty()) {
                    fullWidth { SectionTitle(stringResource(R.string.channel_schedule)) }
                    fullWidth { ScheduleList(schedule.take(SCHEDULE_SHOWN)) }
                }
                if (state.clips.isNotEmpty()) {
                    fullWidth { SectionTitle(stringResource(R.string.channel_clips)) }
                    fullWidth {
                        val viewsLabel = stringResource(R.string.video_views)
                        CardRow(state.clips, key = { "clip:${it.slug}" }, padded = false) { clip ->
                            val key = "clip:${clip.slug}"
                            VideoCard(
                                video = clip.toCard(viewsLabel),
                                onClick = {
                                    memory.key = key
                                    onOpenClip(clip.slug, clip.title)
                                },
                                modifier = Modifier.restoreFocusHere(memory, key == target),
                            )
                        }
                    }
                }
                val panels = state.about?.panels.orEmpty()
                if (panels.isNotEmpty()) {
                    fullWidth { SectionTitle(stringResource(R.string.channel_about)) }
                    fullWidth {
                        CardRow(panels.withIndex().toList(), key = { "panel:${it.index}" }, padded = false) { PanelCard(it.value) }
                    }
                }
                fullWidth { SectionTitle(stringResource(R.string.channel_videos)) }
                when {
                    state.videos.isEmpty() && state.loading -> items(4) { StreamCardSkeleton() }
                    state.videos.isEmpty() && state.error != null -> fullWidth {
                        MessageState(
                            text = stringResource(state.error.messageRes),
                            actionLabel = stringResource(DataR.string.retry),
                            onAction = onRetry,
                            takeFocus = live == null,
                        )
                    }
                    state.videos.isEmpty() -> fullWidth {
                        Text(
                            text = stringResource(R.string.channel_no_videos),
                            style = MaterialTheme.typography.bodyLarge,
                            color = LumenColors.OnSurfaceMuted,
                        )
                    }
                }
                itemsIndexed(state.videos, key = { _, it -> "v:${it.id}" }) { index, video ->
                    if (index >= state.videos.size - 8) LaunchedEffect(Unit) { onNearEnd() }
                    val key = "v:${video.id}"
                    VideoCard(
                        video = video.toCard(stringResource(R.string.video_views)),
                        onClick = {
                            memory.key = key
                            onOpenVideo(video.id)
                        },
                        modifier = Modifier.restoreFocusHere(memory, key == target),
                    )
                }
            }
        }
    }
}

private const val SCHEDULE_SHOWN = 5
private val PANEL_WIDTH = 320.dp

/** « jeu. 14 nov., 20:00   Titre · Catégorie » */
@Composable
private fun ScheduleList(segments: List<ScheduleSegment>) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        segments.forEach { segment ->
            val start = DateUtils.formatDateTime(
                context,
                segment.startAt * 1000,
                DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                    DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_TIME,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(start, style = MaterialTheme.typography.titleSmall, color = LumenColors.Accent)
                Text(
                    text = listOf(segment.title, segment.categoryName).filter { it.isNotBlank() }.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Panneau « À propos » : focalisable pour que la rangée défile à la télécommande. */
@Composable
private fun PanelCard(panel: Panel) {
    LumenCard(onClick = {}, modifier = Modifier.width(PANEL_WIDTH)) {
        Column {
            if (panel.imageUrl.isNotEmpty()) {
                AsyncImage(
                    model = panel.imageUrl,
                    contentDescription = panel.title.ifEmpty { null },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                )
            }
            if (panel.title.isNotEmpty() || panel.description.isNotEmpty()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (panel.title.isNotEmpty()) Text(panel.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (panel.description.isNotEmpty()) {
                        Text(
                            text = panel.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = LumenColors.OnSurfaceMuted,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun LazyGridScope.fullWidth(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun ChannelHeader(channel: Channel?, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Avatar(channel?.avatarUrl.orEmpty(), channel?.displayName.orEmpty(), size = 80.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(channel?.displayName.orEmpty(), style = MaterialTheme.typography.headlineMedium)
            if (!channel?.description.isNullOrBlank()) {
                Text(
                    text = channel?.description.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = LumenColors.OnSurfaceMuted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // Suivre depuis l'app est impossible (API Twitch fermée) : le téléphone ouvre la chaîne.
        if (channel != null) FollowQr(channel.login)
    }
}

@Composable
private fun FollowQr(login: String) {
    val qr = remember(login) { runCatching { qrCode("https://www.twitch.tv/$login") }.getOrNull() } ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            text = stringResource(R.string.channel_follow_qr),
            style = MaterialTheme.typography.bodyMedium,
            color = LumenColors.OnSurfaceMuted,
            modifier = Modifier.width(180.dp),
        )
        QrCode(qr.size.toInt(), qr.modules, size = 120.dp)
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun CenteredMessage(text: String, onAction: (() -> Unit)?) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MessageState(
            text = text,
            actionLabel = if (onAction != null) stringResource(DataR.string.retry) else null,
            onAction = { onAction?.invoke() },
            takeFocus = true,
        )
    }
}
