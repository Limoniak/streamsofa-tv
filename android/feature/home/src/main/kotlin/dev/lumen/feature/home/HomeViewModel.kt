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
import dev.lumen.core.Clip
import dev.lumen.core.LiveStream
import dev.lumen.core.Video
import dev.lumen.data.AccountsRepository
import dev.lumen.data.CatalogRepository
import dev.lumen.data.LoadError
import dev.lumen.data.DEFAULT_HOME_ROWS
import dev.lumen.data.HomeRowSetting
import dev.lumen.data.HomeRow
import dev.lumen.data.SettingsRepository
import dev.lumen.data.loadCatching
import dev.lumen.data.toLoadError
import dev.lumen.designsystem.CategoryCardModel
import dev.lumen.designsystem.ChannelCardModel
import dev.lumen.designsystem.StreamCardModel
import dev.lumen.designsystem.VideoCardModel
import dev.lumen.designsystem.formatViewers
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** Compte connecté uniquement ; rangée masquée si vide. */
    val recommended: Section<LiveStream> = Section.Loading,
    val popular: Section<LiveStream> = Section.Loading,
    val categories: Section<Category> = Section.Loading,
    /** Chaînes regardées récemment et en live, hors suivis en live ; rangée masquée si vide. */
    val recent: List<LiveStream> = emptyList(),
    /** Catégories masquées par le profil : ni dans la rangée, ni leurs lives dans Populaires et Recommandés. */
    val hiddenCategories: Set<String> = emptySet(),
    /** Rangées dans l'ordre choisi (Paramètres > Accueil) ; seules les visibles sont chargées et affichées. */
    val rows: List<HomeRowSetting> = DEFAULT_HOME_ROWS,
    /** Rediffusions commencées et pas finies. */
    val continueWatching: List<Video> = emptyList(),
    /** Lives des catégories regardées dernièrement, hors chaînes déjà connues. */
    val forYou: List<LiveStream> = emptyList(),
    /** Code ISO 639-1 de la langue de l'interface, et ses lives les plus regardés. */
    val language: String = "",
    val languageStreams: List<LiveStream> = emptyList(),
    /** Une rangée par catégorie favorite, dans l'ordre d'ajout. */
    val favorites: List<FavoriteRow> = emptyList(),
    val favoriteIds: Set<String> = emptySet(),
) {
    val needsReauth get() = account?.state == AccountState.NEEDS_REAUTH
    val showFollowed get() = account != null && !needsReauth
}

/** Rangée d'une catégorie favorite : ses lives, dans la langue choisie pour les catégories. */
data class FavoriteRow(val id: String, val name: String, val streams: List<LiveStream>)

/** Chaînes récentes interrogées à chaque chargement (une requête chacune, en parallèle). */
private const val RECENT_LOOKUPS = 10
/** Rediffusions de « Continuer à regarder » (une requête chacune). */
private const val CONTINUE_LOOKUPS = 10
/** Catégories récentes qui nourrissent « Pourrait vous plaire ». */
private const val FOR_YOU_SEEDS = 2
private const val ROW_SIZE = 20

/** Les listes l'une après l'autre, élément par élément : chaque catégorie a sa place en tête de rangée. */
internal fun <T> List<List<T>>.interleaved(): List<T> {
    val out = ArrayList<T>()
    var i = 0
    while (any { i < it.size }) {
        forEach { if (i < it.size) out += it[i] }
        i++
    }
    return out
}

/** Langue de l'interface (celle du système, ou celle choisie pour l'app sur Android 13+). */
private fun appLanguage(): String = java.util.Locale.getDefault().language

/** Au-delà, revenir sur l'accueil recharge : les compteurs et les lives ont bougé. */
private const val STALE_AFTER_MS = 60_000L

@HiltViewModel
class HomeViewModel @Inject constructor(
    accounts: AccountsRepository,
    private val catalog: CatalogRepository,
    private val settings: SettingsRepository,
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

    init {
        viewModelScope.launch {
            settings.state.map { it.hiddenCategories.keys }.distinctUntilChanged().collect { hidden ->
                _state.update { it.copy(hiddenCategories = hidden) }
            }
        }
        // Rangées réordonnées, affichées ou ajoutées (favorites) : on recharge ce qui est désormais visible.
        viewModelScope.launch {
            settings.state.map { it.homeRows to it.favoriteCategories }.distinctUntilChanged().collect { (rows, favorites) ->
                val changed = _state.value.rows != rows || _state.value.favoriteIds != favorites.keys
                _state.update { it.copy(rows = rows, favoriteIds = favorites.keys) }
                if (changed && loadedAt != 0L) reload()
            }
        }
    }

    /** Masque [id] de l'accueil ; Paramètres > Accueil les fait réapparaître. */
    fun hideCategory(id: String, name: String) = settings.update { copy(hiddenCategories = hiddenCategories + (id to name)) }

    /** Ajoute [id] aux favorites (sa rangée apparaît sur l'accueil) ou l'en retire. */
    fun toggleFavorite(id: String, name: String) = settings.update {
        copy(favoriteCategories = if (id in favoriteCategories) favoriteCategories - id else favoriteCategories + (id to name))
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
            val prefs = settings.awaitLoaded()
            val visible = prefs.homeRows.filter { it.visible }.map { it.row }.toSet()
            fun shown(row: HomeRow) = row in visible
            val language = appLanguage()
            _state.update { it.copy(rows = prefs.homeRows, language = language, favoriteIds = prefs.favoriteCategories.keys) }
            // Les suivis servent aussi à écarter des doublons dans d'autres rangées : chargés dès qu'il y a un compte.
            val followed = async {
                if (!withFollowed) return@async emptyList()
                val result = loadCatching { catalog.followedStreams() }.toSection()
                _state.update { it.copy(followed = result) }
                (result as? Section.Ready)?.items.orEmpty()
            }
            if (shown(HomeRow.Recent)) launch {
                // Après les suivis : une chaîne suivie en live est déjà dans sa rangée, inutile de la redemander.
                val skip = followed.await().map { it.login }.toSet()
                val logins = prefs.recentChannels.filter { it !in skip }.take(RECENT_LOOKUPS)
                val live = logins.map { login -> async { loadCatching { catalog.liveStream(login) }.getOrNull() } }.awaitAll().filterNotNull()
                _state.update { it.copy(recent = live) }
            }
            if (shown(HomeRow.ContinueWatching)) launch {
                val videos = loadCatching { catalog.continueWatching(CONTINUE_LOOKUPS) }.getOrDefault(emptyList())
                _state.update { it.copy(continueWatching = videos) }
            }
            if (shown(HomeRow.ForYou)) launch {
                val seeds = prefs.recentCategories.keys.filter { it !in prefs.hiddenCategories }.take(FOR_YOU_SEEDS)
                val lists = seeds.map { id ->
                    async { loadCatching { catalog.categoryStreams(id, null, language).items }.getOrDefault(emptyList()) }
                }.awaitAll()
                // Ce que l'on connaît déjà (suivis, regardés) a sa propre rangée : ici, des découvertes.
                val known = followed.await().map { it.login }.toSet() + prefs.recentChannels
                val picks = lists.interleaved().filter { it.login !in known }.distinctBy { it.login }.take(ROW_SIZE)
                _state.update { it.copy(forYou = picks) }
            }
            if (withFollowed && shown(HomeRow.Recommended)) launch {
                val result = loadCatching { catalog.recommendedStreams() }.toSection()
                _state.update { it.copy(recommended = result) }
            }
            if (shown(HomeRow.Popular)) launch {
                val result = loadCatching { catalog.topStreams(null).items }.toSection()
                _state.update { it.copy(popular = result) }
            } else {
                _state.update { it.copy(popular = Section.Ready(emptyList())) }
            }
            if (shown(HomeRow.Language)) launch {
                val streams = loadCatching { catalog.languageStreams(language) }.getOrDefault(emptyList())
                _state.update { it.copy(languageStreams = streams) }
            }
            if (shown(HomeRow.FavoriteCategories)) launch {
                val filter = prefs.streamLanguage.ifEmpty { null }
                val rows = prefs.favoriteCategories.map { (id, name) ->
                    async { FavoriteRow(id, name, loadCatching { catalog.categoryStreams(id, null, filter).items }.getOrDefault(emptyList())) }
                }.awaitAll()
                _state.update { it.copy(favorites = rows) }
            }
            if (shown(HomeRow.Categories)) launch {
                val result = loadCatching { catalog.topCategories(null).items }.toSection()
                _state.update { it.copy(categories = result) }
            } else {
                _state.update { it.copy(categories = Section.Ready(emptyList())) }
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

/** « Pseudo  ·  12,3 k vues » ; [showChannel] : la chaîne plutôt que l'auteur du clip (rangées multi-chaînes). */
fun Clip.toCard(viewsLabel: String, showChannel: Boolean = false) = VideoCardModel(
    key = "clip:$slug",
    title = title,
    subtitle = listOfNotNull(
        (if (showChannel) channelName else curator).takeIf { it.isNotEmpty() },
        "${formatViewers(views.toInt())} $viewsLabel",
    ).joinToString("  ·  "),
    thumbnailTemplate = thumbnailUrl,
    durationS = durationS.toLong(),
    positionS = 0,
    finished = false,
)

fun Category.toCard() = CategoryCardModel(
    key = "c:$id",
    name = name,
    viewers = viewers.toInt(),
    boxArtTemplate = boxArtUrl,
)
