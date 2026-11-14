package dev.lumen.data

import dev.lumen.core.LiveStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

private const val QUALITY_PREFIX = "quality:"
const val MAX_RECENT_CHANNELS = 20

private fun String?.lines(): List<String> = this?.split('\n')?.filter { it.isNotBlank() }.orEmpty()

/** Lignes `id\tnom` (un nom de catégorie ne contient ni tabulation ni saut de ligne), ordre conservé. */
private fun String?.idNames(): Map<String, String> = lines()
    .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
    .toMap(LinkedHashMap())

private fun Map<String, String>.lines(): String = entries.joinToString("\n") { "${it.key}\t${it.value}" }

const val MAX_RECENT_CATEGORIES = 5

/** Épinglées d'abord, dans l'ordre d'épinglage, puis les autres dans leur ordre d'origine. */
fun List<LiveStream>.pinnedFirst(pinned: List<String>): List<LiveStream> {
    if (pinned.isEmpty()) return this
    val rank = pinned.withIndex().associate { it.value to it.index }
    return sortedBy { rank[it.login] ?: Int.MAX_VALUE }
}
const val QUALITY_AUTO = "auto"

/** Préférences du profil actif, et celles de l'appareil (mises à jour, journal de crash). */
data class AppSettings(
    /** Chat ouvert dès le lancement d'un live ou d'une VOD. */
    val chatByDefault: Boolean = false,
    /** Hauteur maximale de la qualité choisie au lancement d'un live ; 0 = Auto. */
    val maxQuality: Int = 0,
    /** Lecture des segments en cours d'encodage (expérimental). */
    val lowLatency: Boolean = false,
    /** Clé d'`AccentColor` (design system). */
    val accent: String = "violet",
    /** Recherche d'une nouvelle version au démarrage (builds de release uniquement). */
    val checkUpdates: Boolean = true,
    /** Propose aussi les pré-versions (bêta). */
    val betaUpdates: Boolean = false,
    /** Journal de crash local ([CrashLog]), désactivé par défaut. */
    val crashLog: Boolean = false,
    /** Émotes 7TV, BTTV et FFZ dans le chat. */
    val thirdPartyEmotes: Boolean = true,
    /** Heure affichée devant chaque message du chat. */
    val chatTimestamps: Boolean = false,
    /** Taille du texte du chat : 0 petit, 1 moyen, 2 grand. */
    val chatTextSize: Int = 1,
    /** Largeur du chat : 0 étroit, 1 normal, 2 large. */
    val chatWidth: Int = 1,
    /** Chat transparent par-dessus la vidéo plutôt qu'à côté. */
    val chatOverlay: Boolean = false,
    /** Listes séparées par des virgules, appliquées par le cœur à l'ouverture du chat. */
    val hiddenWords: String = "",
    val hiddenUsers: String = "",
    /** Langue des lives dans une catégorie (code ISO 639-1) ; vide = toutes. */
    val streamLanguage: String = "",
    /** Recherches qui ont mené à un résultat, la plus récente en tête. */
    val recentSearches: List<String> = emptyList(),
    /** Dernière qualité choisie à la main, par login de chaîne ([QUALITY_AUTO] pour Auto). */
    val channelQuality: Map<String, String> = emptyMap(),
    /** Profil enfants : lu seulement, il se change depuis l'écran des profils (avec PIN parent). */
    val kids: Boolean = false,
    /** Chaînes épinglées (logins), dans l'ordre d'épinglage : en tête des suivis en live et du zapping. */
    val pinnedChannels: List<String> = emptyList(),
    /** Chaînes regardées en live, la plus récente en tête ([MAX_RECENT_CHANNELS] au plus). */
    val recentChannels: List<String> = emptyList(),
    /** Catégories masquées de l'accueil : id → nom (pour les lister dans les paramètres). */
    val hiddenCategories: Map<String, String> = emptyMap(),
    /** Catégories favorites : une rangée « En live dans … » chacune sur l'accueil, id → nom. */
    val favoriteCategories: Map<String, String> = emptyMap(),
    /** Catégories des lives regardés, la plus récente en tête : base de « Pourrait vous plaire ». */
    val recentCategories: Map<String, String> = emptyMap(),
    /** Rangées de l'accueil, dans l'ordre choisi, avec leur visibilité. */
    val homeRows: List<HomeRowSetting> = DEFAULT_HOME_ROWS,
) {
    /** [id] en tête des catégories récentes, [MAX_RECENT_CATEGORIES] au plus. */
    fun withRecentCategory(id: String, name: String): AppSettings {
        if (id.isEmpty() || recentCategories.keys.firstOrNull() == id) return this
        val merged = linkedMapOf(id to name).apply { putAll(recentCategories - id) }
        return copy(recentCategories = merged.entries.take(MAX_RECENT_CATEGORIES).associate { it.key to it.value })
    }

    /** [login] en tête des chaînes récentes, sans doublon. */
    fun withRecentChannel(login: String) =
        copy(recentChannels = (listOf(login) + recentChannels.filter { it != login }).take(MAX_RECENT_CHANNELS))

    fun withPinned(login: String, pinned: Boolean) =
        copy(pinnedChannels = if (pinned) (pinnedChannels.filter { it != login } + login) else pinnedChannels.filter { it != login })

    internal fun toMap() = mapOf(
        "chat_by_default" to chatByDefault.toString(),
        "max_quality" to maxQuality.toString(),
        "low_latency" to lowLatency.toString(),
        "accent" to accent,
        "check_updates" to checkUpdates.toString(),
        "beta_updates" to betaUpdates.toString(),
        "crash_log" to crashLog.toString(),
        "third_party_emotes" to thirdPartyEmotes.toString(),
        "chat_timestamps" to chatTimestamps.toString(),
        "chat_text_size" to chatTextSize.toString(),
        "chat_width" to chatWidth.toString(),
        "chat_overlay" to chatOverlay.toString(),
        "chat_hidden_words" to hiddenWords,
        "chat_hidden_users" to hiddenUsers,
        "stream_language" to streamLanguage,
        "recent_searches" to recentSearches.joinToString("\n"),
        "pinned_channels" to pinnedChannels.joinToString("\n"),
        "recent_channels" to recentChannels.joinToString("\n"),
        // Un nom de catégorie ne contient ni tabulation ni saut de ligne.
        "hidden_categories" to hiddenCategories.lines(),
        "favorite_categories" to favoriteCategories.lines(),
        "recent_categories" to recentCategories.lines(),
        "home_rows" to homeRows.toHomeRowsValue(),
    ) + channelQuality.mapKeys { QUALITY_PREFIX + it.key }

    internal companion object {
        fun fromMap(map: Map<String, String>) = AppSettings().let { d ->
            AppSettings(
                chatByDefault = map["chat_by_default"]?.toBooleanStrictOrNull() ?: d.chatByDefault,
                maxQuality = map["max_quality"]?.toIntOrNull() ?: d.maxQuality,
                lowLatency = map["low_latency"]?.toBooleanStrictOrNull() ?: d.lowLatency,
                accent = map["accent"] ?: d.accent,
                checkUpdates = map["check_updates"]?.toBooleanStrictOrNull() ?: d.checkUpdates,
                betaUpdates = map["beta_updates"]?.toBooleanStrictOrNull() ?: d.betaUpdates,
                crashLog = map["crash_log"]?.toBooleanStrictOrNull() ?: d.crashLog,
                thirdPartyEmotes = map["third_party_emotes"]?.toBooleanStrictOrNull() ?: d.thirdPartyEmotes,
                chatTimestamps = map["chat_timestamps"]?.toBooleanStrictOrNull() ?: d.chatTimestamps,
                chatTextSize = map["chat_text_size"]?.toIntOrNull() ?: d.chatTextSize,
                chatWidth = map["chat_width"]?.toIntOrNull() ?: d.chatWidth,
                chatOverlay = map["chat_overlay"]?.toBooleanStrictOrNull() ?: d.chatOverlay,
                hiddenWords = map["chat_hidden_words"] ?: d.hiddenWords,
                hiddenUsers = map["chat_hidden_users"] ?: d.hiddenUsers,
                streamLanguage = map["stream_language"] ?: d.streamLanguage,
                recentSearches = map["recent_searches"]?.split('\n')?.filter { it.isNotBlank() } ?: d.recentSearches,
                channelQuality = map.filterKeys { it.startsWith(QUALITY_PREFIX) }.mapKeys { it.key.removePrefix(QUALITY_PREFIX) },
                kids = map["kids_profile"] == "true",
                pinnedChannels = map["pinned_channels"].lines(),
                recentChannels = map["recent_channels"].lines(),
                hiddenCategories = map["hidden_categories"].idNames(),
                favoriteCategories = map["favorite_categories"].idNames(),
                recentCategories = map["recent_categories"].idNames(),
                homeRows = parseHomeRows(map["home_rows"]),
            )
        }
    }
}

/** Préférences relues à chaque changement de profil, puis écrites au fil des changements (SQLite côté Rust). */
@Singleton
class SettingsRepository @Inject constructor(
    private val coreHolder: CoreHolder,
    accounts: AccountsRepository,
) {
    // Un seul thread : les écritures arrivent en base dans l'ordre des changements.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            // Chaque profil a ses préférences : relues à la bascule, et quand son mode enfants change.
            accounts.snapshot.filterNotNull()
                .map { s -> s.activeId to s.locks.any { it.profileId == s.activeId && it.kids } }
                .distinctUntilChanged()
                .collect {
                    runCatching { coreHolder.get().settings() }.onSuccess { _state.value = AppSettings.fromMap(it) }
                    loaded.complete(Unit)
                }
        }
    }

    /** Préférences une fois lues en base (les valeurs par défaut si la lecture échoue). */
    suspend fun awaitLoaded(): AppSettings {
        loaded.await()
        return _state.value
    }

    fun update(change: AppSettings.() -> AppSettings) {
        val before = _state.getAndUpdate(change)
        val changed = _state.value.toMap().filter { (key, value) -> before.toMap()[key] != value }
        if (changed.isEmpty()) return
        scope.launch {
            // Une préférence non enregistrée reviendra simplement à sa valeur au prochain lancement.
            runCatching { changed.forEach { (key, value) -> coreHolder.get().setSetting(key, value) } }
        }
    }
}
