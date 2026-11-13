package dev.lumen.feature.home

import android.os.SystemClock
import android.text.format.DateUtils
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lumen.core.Account
import dev.lumen.core.AccountState
import dev.lumen.core.Category
import dev.lumen.core.Channel
import dev.lumen.core.LiveStream
import dev.lumen.core.Video
import dev.lumen.data.AccountsRepository
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.loadCatching
import dev.lumen.data.toLoadError
import dev.lumen.designsystem.CategoryCardModel
import dev.lumen.designsystem.ChannelCardModel
import dev.lumen.designsystem.StreamCardModel
import dev.lumen.designsystem.VideoCardModel
import dev.lumen.designsystem.formatViewers
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Section<out T> {
    data object Loading : Section<Nothing>
    data class Ready<T>(val items: List<T>) : Section<T>
    data class Failed(val error: LoadError) : Section<Nothing>
}

fun <T> Result<List<T>>.toSection(): Section<T> =
    fold({ Section.Ready(it) }, { Section.Failed(it.toLoadError()) })

data class HomeUiState(
    /** Compte actif ; `null` en invité. */
    val account: Account? = null,
    val followed: Section<LiveStream> = Section.Loading,
    val popular: Section<LiveStream> = Section.Loading,
    val categories: Section<Category> = Section.Loading,
) {
    val needsReauth get() = account?.state == AccountState.NEEDS_REAUTH
    val showFollowed get() = account != null && !needsReauth
}

/** Au-delà, revenir sur l'accueil recharge : les compteurs et les lives ont bougé. */
private const val STALE_AFTER_MS = 60_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    accounts: AccountsRepository,
    private val catalog: CatalogRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    private var loadedAt = 0L
    private var job: Job? = null

    init {
        viewModelScope.launch {
            // Changer de compte (ou le voir révoqué) change tout le contenu : on recharge.
            accounts.snapshot.filterNotNull()
                .map { snap -> snap.accounts.firstOrNull { it.id == snap.activeId } }
                .distinctUntilChanged()
                .collect { account ->
                    _state.update { it.copy(account = account) }
                    reload()
                }
        }
    }

    fun refreshIfStale() {
        if (loadedAt != 0L && SystemClock.elapsedRealtime() - loadedAt > STALE_AFTER_MS) reload()
    }

    fun reload() {
        job?.cancel()
        loadedAt = SystemClock.elapsedRealtime()
        val withFollowed = _state.value.showFollowed
        // Les sections déjà affichées restent visibles pendant le rechargement (pas de clignotement).
        job = viewModelScope.launch {
            if (withFollowed) {
                launch {
                    val result = loadCatching { catalog.followedStreams() }.toSection()
                    _state.update { it.copy(followed = result) }
                }
            }
            launch {
                val result = loadCatching { catalog.topStreams(null).items }.toSection()
                _state.update { it.copy(popular = result) }
            }
            launch {
                val result = loadCatching { catalog.topCategories(null).items }.toSection()
                _state.update { it.copy(categories = result) }
            }
        }
    }
}

/** [prefix] distingue une même chaîne présente dans deux rangées (clé de focus unique). */
fun LiveStream.toCard(prefix: String) = StreamCardModel(
    key = "$prefix:$login",
    title = title,
    channelName = displayName,
    categoryName = categoryName,
    viewers = viewers.toInt(),
    thumbnailTemplate = thumbnailUrl,
    avatarUrl = avatarUrl,
)

fun Channel.toCard() = ChannelCardModel(key = "ch:$login", name = displayName, avatarUrl = avatarUrl)

/** « il y a 3 jours · 12,3 k vues » */
fun Video.toCard(viewsLabel: String) = VideoCardModel(
    key = "v:$id",
    title = title,
    subtitle = listOfNotNull(
        publishedAt.takeIf { it > 0 }?.let { DateUtils.getRelativeTimeSpanString(it * 1000).toString() },
        "${formatViewers(views.toInt())} $viewsLabel",
    ).joinToString("  ·  "),
    thumbnailTemplate = thumbnailUrl,
    durationS = durationS.toLong(),
    positionS = positionS.toLong(),
    finished = finished,
)

fun Category.toCard() = CategoryCardModel(
    key = "c:$id",
    name = name,
    viewers = viewers.toInt(),
    boxArtTemplate = boxArtUrl,
)
