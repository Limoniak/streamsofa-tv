package dev.lumen.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.core.Category
import dev.lumen.core.LiveStream
import dev.lumen.data.messageRes
import dev.lumen.designsystem.CategoryCard
import dev.lumen.designsystem.CategoryCardSkeleton
import dev.lumen.designsystem.FocusMemory
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.MessageState
import dev.lumen.designsystem.StreamCard
import dev.lumen.designsystem.StreamCardSkeleton
import dev.lumen.designsystem.rememberFocusMemory
import dev.lumen.designsystem.requestWhenAttached
import dev.lumen.designsystem.restoreFocusHere
import dev.lumen.data.R as DataR

@Composable
fun HomeRoute(
    onOpenStream: (login: String) -> Unit,
    onOpenChannel: (login: String) -> Unit,
    onOpenCategory: (id: String, name: String) -> Unit,
    onReconnect: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshIfStale() }
    HomeScreen(state, onOpenStream, onOpenChannel, onOpenCategory, onReconnect, onRetry = viewModel::reload)
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenStream: (login: String) -> Unit,
    onOpenChannel: (login: String) -> Unit,
    onOpenCategory: (id: String, name: String) -> Unit,
    onReconnect: () -> Unit,
    onRetry: () -> Unit,
    memory: FocusMemory = rememberFocusMemory(),
) {
    // Focus initial : la dernière carte ouverte, sinon la première carte de la première rangée.
    val followed = (state.followed as? Section.Ready)?.items.orEmpty().takeIf { state.showFollowed }.orEmpty()
    val popular = (state.popular as? Section.Ready)?.items.orEmpty()
    val categories = (state.categories as? Section.Ready)?.items.orEmpty()
    val keys = followed.map { "f:${it.login}" } + popular.map { "p:${it.login}" } + categories.map { "c:${it.id}" }
    // On attend toutes les rangées : sinon le focus irait à la première arrivée, pas à la première affichée.
    val settled = listOfNotNull(state.followed.takeIf { state.showFollowed }, state.popular, state.categories)
        .none { it is Section.Loading }
    val target = if (settled) memory.target(keys, keys.firstOrNull()) else null
    // Une seule fois par entrée sur l'écran : une rangée chargée plus tard ne vole pas le focus.
    LaunchedEffect(target != null) { if (target != null) memory.requester.requestWhenAttached() }

    val openStream = { prefix: String, stream: LiveStream ->
        memory.key = "$prefix:${stream.login}"
        onOpenStream(stream.login)
    }
    val openChannel = { prefix: String, stream: LiveStream ->
        memory.key = "$prefix:${stream.login}"
        onOpenChannel(stream.login)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = LumenInsets.vertical),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (state.needsReauth) {
            item(key = "reauth") { ReauthBanner(state.account?.displayName.orEmpty(), onReconnect) }
        }
        if (state.showFollowed) {
            item(key = "followed") {
                StreamRow(
                    title = stringResource(R.string.home_followed),
                    section = state.followed,
                    prefix = "f",
                    emptyText = stringResource(R.string.home_no_followed),
                    memory = memory,
                    target = target,
                    onOpen = openStream,
                    onOpenChannel = openChannel,
                    onRetry = onRetry,
                )
            }
        }
        item(key = "popular") {
            StreamRow(
                title = stringResource(R.string.home_popular),
                section = state.popular,
                prefix = "p",
                emptyText = null,
                memory = memory,
                target = target,
                onOpen = openStream,
                onOpenChannel = openChannel,
                onRetry = onRetry,
            )
        }
        item(key = "categories") {
            RowScaffold(stringResource(R.string.home_categories)) {
                when (val section = state.categories) {
                    Section.Loading -> SkeletonRow { CategoryCardSkeleton() }
                    is Section.Failed -> RowMessage(stringResource(section.error.messageRes), onRetry)
                    is Section.Ready -> CardRow(section.items, key = { it.id }) { category ->
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
private fun StreamRow(
    title: String,
    section: Section<LiveStream>,
    prefix: String,
    emptyText: String?,
    memory: FocusMemory,
    target: String?,
    onOpen: (String, LiveStream) -> Unit,
    onOpenChannel: (String, LiveStream) -> Unit,
    onRetry: () -> Unit,
) {
    RowScaffold(title) {
        when (section) {
            Section.Loading -> SkeletonRow { StreamCardSkeleton() }
            is Section.Failed -> RowMessage(stringResource(section.error.messageRes), onRetry)
            is Section.Ready -> if (section.items.isEmpty() && emptyText != null) {
                RowMessage(emptyText, onRetry = null)
            } else {
                CardRow(section.items, key = { it.login }) { stream ->
                    StreamCard(
                        stream = stream.toCard(prefix),
                        onClick = { onOpen(prefix, stream) },
                        onLongClick = { onOpenChannel(prefix, stream) },
                        modifier = Modifier.restoreFocusHere(memory, "$prefix:${stream.login}" == target),
                    )
                }
            }
        }
    }
}

@Composable
private fun RowScaffold(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = LumenInsets.horizontal),
        )
        content()
    }
}

/**
 * `focusRestorer` : revenir dans une rangée (haut/bas) reprend la carte quittée,
 * et non la première.
 */
@Composable
internal fun <T> CardRow(items: List<T>, key: (T) -> Any, card: @Composable (T) -> Unit) {
    LazyRow(
        modifier = Modifier.focusRestorer(),
        contentPadding = PaddingValues(horizontal = LumenInsets.horizontal, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(items, key = key) { card(it) }
    }
}

@Composable
private fun SkeletonRow(skeleton: @Composable () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = LumenInsets.horizontal, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        repeat(6) { skeleton() }
    }
}

@Composable
private fun RowMessage(text: String, onRetry: (() -> Unit)?) {
    Box(
        modifier = Modifier.fillMaxWidth().height(120.dp).padding(horizontal = LumenInsets.horizontal),
        contentAlignment = Alignment.CenterStart,
    ) {
        MessageState(
            text = text,
            actionLabel = if (onRetry != null) stringResource(DataR.string.retry) else null,
            onAction = { onRetry?.invoke() },
        )
    }
}

@Composable
private fun ReauthBanner(name: String, onReconnect: () -> Unit) {
    Row(
        modifier = Modifier.padding(horizontal = LumenInsets.horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = stringResource(R.string.home_needs_reauth, name),
            style = MaterialTheme.typography.titleMedium,
            color = LumenColors.Error,
        )
        Button(onClick = onReconnect) { Text(stringResource(R.string.home_reconnect)) }
    }
}
