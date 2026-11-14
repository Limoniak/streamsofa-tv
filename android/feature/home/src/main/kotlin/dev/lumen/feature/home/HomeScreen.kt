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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.tv.material3.OutlinedButton
import androidx.compose.ui.window.Dialog
import dev.lumen.designsystem.VideoCard
import dev.lumen.data.HomeRow
import dev.lumen.designsystem.CategoryCard
import dev.lumen.designsystem.CategoryCardSkeleton
import dev.lumen.designsystem.FocusMemory
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import dev.lumen.designsystem.MessageState
import dev.lumen.designsystem.StreamCard
import dev.lumen.designsystem.StreamCardSkeleton
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import dev.lumen.designsystem.ConfirmDialog
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
    onOpenVideo: (id: String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshIfStale() }
    HomeScreen(
        state, onOpenStream, onOpenChannel, onOpenCategory, onReconnect, onRetry = viewModel::reload,
        onHideCategory = viewModel::hideCategory, onToggleFavorite = viewModel::toggleFavorite, onOpenVideo = onOpenVideo,
    )
}

/** Une rangée prête à afficher : ses clés de focus (dans l'ordre) et son contenu. */
private class HomeItem(val id: String, val keys: List<String>, val content: @Composable () -> Unit)

/**
 * Accueil : les rangées dans l'ordre choisi (Paramètres > Accueil). Les rangées principales (suivis,
 * populaires, catégories) montrent leur chargement et leurs erreurs ; les autres n'apparaissent que si
 * elles ont du contenu.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenStream: (login: String) -> Unit,
    onOpenChannel: (login: String) -> Unit,
    onOpenCategory: (id: String, name: String) -> Unit,
    onReconnect: () -> Unit,
    onRetry: () -> Unit,
    onHideCategory: (id: String, name: String) -> Unit = { _, _ -> },
    onToggleFavorite: (id: String, name: String) -> Unit = { _, _ -> },
    onOpenVideo: (id: String) -> Unit = {},
    memory: FocusMemory = rememberFocusMemory(),
) {
    val hidden = state.hiddenCategories
    // Les lives d'une catégorie masquée disparaissent aussi des rangées de découverte (pas des suivis).
    fun Section<LiveStream>.visible(): Section<LiveStream> =
        if (hidden.isEmpty() || this !is Section.Ready) this else Section.Ready(items.filter { it.categoryId !in hidden })
    fun List<LiveStream>.visible() = if (hidden.isEmpty()) this else filter { it.categoryId !in hidden }
    val visibleRows = state.rows.filter { it.visible }.map { it.row }
    val followed = (state.followed as? Section.Ready)?.items.orEmpty().takeIf { state.showFollowed }.orEmpty()
    val followedLogins = followed.map { it.login }.toSet()
    /** Catégorie dont on montre les options (appui long), puis dont on confirme le masquage. */
    var options by remember { mutableStateOf<Category?>(null) }
    var hiding by remember { mutableStateOf<Category?>(null) }

    val openStream = { prefix: String, stream: LiveStream ->
        memory.key = "$prefix:${stream.login}"
        onOpenStream(stream.login)
    }
    val openChannel = { prefix: String, stream: LiveStream ->
        memory.key = "$prefix:${stream.login}"
        onOpenChannel(stream.login)
    }

    val items = ArrayList<HomeItem>()
    // `target` n'est connu qu'une fois toutes les clés calculées : les rangées le lisent à l'affichage.
    var target: String? = null
    fun streamItem(id: String, title: String, section: Section<LiveStream>, prefix: String, emptyText: String? = null) {
        val keys = (section as? Section.Ready)?.items.orEmpty().map { "$prefix:${it.login}" }
        items += HomeItem(id, keys) {
            StreamRow(title, section, prefix, emptyText, memory, target, openStream, openChannel, onRetry)
        }
    }
    for (row in visibleRows) when (row) {
        HomeRow.Followed -> if (state.showFollowed) {
            streamItem("followed", stringResource(R.string.home_followed), state.followed, "f", stringResource(R.string.home_no_followed))
        }
        HomeRow.ContinueWatching -> if (state.continueWatching.isNotEmpty()) {
            val videos = state.continueWatching
            items += HomeItem("continue", videos.map { "w:${it.id}" }) {
                RowScaffold(stringResource(R.string.home_continue)) {
                    val viewsLabel = stringResource(R.string.video_views)
                    CardRow(videos, key = { it.id }) { video ->
                        val key = "w:${video.id}"
                        VideoCard(
                            video = video.toCard(viewsLabel),
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
        HomeRow.Recent -> state.recent.filter { it.login !in followedLogins }.takeIf { it.isNotEmpty() }?.let {
            streamItem("recent", stringResource(R.string.home_recent), Section.Ready(it), "h")
        }
        HomeRow.FavoriteCategories -> state.favorites.filter { it.streams.isNotEmpty() }.forEach { favorite ->
            streamItem("fav:${favorite.id}", stringResource(R.string.home_favorite, favorite.name), Section.Ready(favorite.streams), "k${favorite.id}")
        }
        HomeRow.ForYou -> state.forYou.visible().takeIf { it.isNotEmpty() }?.let {
            streamItem("for_you", stringResource(R.string.home_for_you), Section.Ready(it), "y")
        }
        HomeRow.Recommended -> if (state.showFollowed) {
            state.recommended.visible().takeIf { (it as? Section.Ready)?.items?.isNotEmpty() == true }?.let {
                streamItem("recommended", stringResource(R.string.home_recommended), it, "r")
            }
        }
        HomeRow.Popular -> streamItem("popular", stringResource(R.string.home_popular), state.popular.visible(), "p")
        HomeRow.Language -> state.languageStreams.visible().takeIf { it.isNotEmpty() }?.let {
            streamItem("language", stringResource(R.string.home_language, languageName(state.language)), Section.Ready(it), "l")
        }
        HomeRow.Categories -> {
            val section = state.categories.let { s ->
                if (hidden.isEmpty() || s !is Section.Ready) s else Section.Ready(s.items.filter { it.id !in hidden })
            }
            val keys = (section as? Section.Ready)?.items.orEmpty().map { "c:${it.id}" }
            items += HomeItem("categories", keys) {
                RowScaffold(stringResource(R.string.home_categories)) {
                    when (section) {
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
                                onLongClick = { options = category },
                                modifier = Modifier.restoreFocusHere(memory, key == target),
                            )
                        }
                    }
                }
            }
        }
    }

    // Focus initial : la dernière carte ouverte, sinon la première carte de la première rangée.
    val keys = items.flatMap { it.keys }
    // On attend les rangées principales : sinon le focus irait à la première arrivée, pas à la première affichée.
    val settled = listOfNotNull(
        state.followed.takeIf { state.showFollowed && HomeRow.Followed in visibleRows },
        state.popular.takeIf { HomeRow.Popular in visibleRows },
        state.categories.takeIf { HomeRow.Categories in visibleRows },
    ).none { it is Section.Loading }
    target = if (settled) memory.target(keys, keys.firstOrNull()) else null
    // Une seule fois par entrée sur l'écran : une rangée chargée plus tard ne vole pas le focus.
    LaunchedEffect(target != null) { if (target != null) memory.requester.requestWhenAttached() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = LumenInsets.vertical),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (state.needsReauth) {
            item(key = "reauth") { ReauthBanner(state.account?.displayName.orEmpty(), onReconnect) }
        }
        items.forEach { row -> item(key = row.id) { row.content() } }
    }
    options?.let { category ->
        CategoryOptionsDialog(
            name = category.name,
            favorite = category.id in state.favoriteIds,
            onToggleFavorite = {
                options = null
                onToggleFavorite(category.id, category.name)
            },
            onHide = {
                options = null
                hiding = category
            },
            onDismiss = { options = null },
        )
    }
    hiding?.let { category ->
        ConfirmDialog(
            title = stringResource(R.string.category_hide_title, category.name),
            body = stringResource(R.string.category_hide_body),
            confirm = stringResource(R.string.category_hide_confirm),
            cancel = stringResource(R.string.category_hide_cancel),
            onConfirm = {
                hiding = null
                onHideCategory(category.id, category.name)
            },
            onDismiss = { hiding = null },
        )
    }
}

/** « Français », dans la langue elle-même (c'est aussi celle de l'interface). */
private fun languageName(code: String): String {
    val locale = java.util.Locale(code)
    return locale.getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }.ifEmpty { code }
}

/** Appui long sur une catégorie : l'ajouter à l'accueil (ou l'en retirer), ou la masquer. */
@Composable
private fun CategoryOptionsDialog(name: String, favorite: Boolean, onToggleFavorite: () -> Unit, onHide: () -> Unit, onDismiss: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestWhenAttached() }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(LumenColors.SurfaceRaised, RoundedCornerShape(24.dp))
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(name, style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(onClick = onToggleFavorite, modifier = Modifier.focusRequester(focus)) {
                    Text(stringResource(if (favorite) R.string.category_remove_home else R.string.category_add_home))
                }
                OutlinedButton(onClick = onHide) { Text(stringResource(R.string.category_hide_confirm)) }
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
internal fun <T> CardRow(items: List<T>, key: (T) -> Any, padded: Boolean = true, card: @Composable (T) -> Unit) {
    LazyRow(
        modifier = Modifier.focusRestorer(),
        // [padded] = false dans une grille qui applique déjà les marges latérales.
        contentPadding = PaddingValues(horizontal = if (padded) LumenInsets.horizontal else 0.dp, vertical = 12.dp),
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
