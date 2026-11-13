package dev.lumen.data

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch

/** Préférences de l'app, communes à tous les profils. */
data class AppSettings(
    /** Chat ouvert dès le lancement d'un live ou d'une VOD. */
    val chatByDefault: Boolean = false,
    /** Hauteur maximale de la qualité choisie au lancement d'un live ; 0 = Auto. */
    val maxQuality: Int = 0,
    /** Clé d'`AccentColor` (design system). */
    val accent: String = "violet",
    /** Recherche d'une nouvelle version au démarrage (builds de release uniquement). */
    val checkUpdates: Boolean = true,
    /** Émotes 7TV, BTTV et FFZ dans le chat. */
    val thirdPartyEmotes: Boolean = true,
    /** Listes séparées par des virgules, appliquées par le cœur à l'ouverture du chat. */
    val hiddenWords: String = "",
    val hiddenUsers: String = "",
) {
    internal fun toMap() = mapOf(
        "chat_by_default" to chatByDefault.toString(),
        "max_quality" to maxQuality.toString(),
        "accent" to accent,
        "check_updates" to checkUpdates.toString(),
        "third_party_emotes" to thirdPartyEmotes.toString(),
        "chat_hidden_words" to hiddenWords,
        "chat_hidden_users" to hiddenUsers,
    )

    internal companion object {
        fun fromMap(map: Map<String, String>) = AppSettings().let { d ->
            AppSettings(
                chatByDefault = map["chat_by_default"]?.toBooleanStrictOrNull() ?: d.chatByDefault,
                maxQuality = map["max_quality"]?.toIntOrNull() ?: d.maxQuality,
                accent = map["accent"] ?: d.accent,
                checkUpdates = map["check_updates"]?.toBooleanStrictOrNull() ?: d.checkUpdates,
                thirdPartyEmotes = map["third_party_emotes"]?.toBooleanStrictOrNull() ?: d.thirdPartyEmotes,
                hiddenWords = map["chat_hidden_words"] ?: d.hiddenWords,
                hiddenUsers = map["chat_hidden_users"] ?: d.hiddenUsers,
            )
        }
    }
}

/** Préférences lues une fois au démarrage, puis écrites au fil des changements (SQLite côté Rust). */
@Singleton
class SettingsRepository @Inject constructor(private val coreHolder: CoreHolder) {
    // Un seul thread : les écritures arrivent en base dans l'ordre des changements.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch {
            runCatching { coreHolder.get().settings() }.onSuccess { _state.value = AppSettings.fromMap(it) }
            loaded.complete(Unit)
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
