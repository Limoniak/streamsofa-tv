package dev.lumen.feature.player

import dev.lumen.data.SettingsRepository
import dev.lumen.data.screenTimeLeftS
import dev.lumen.data.withWatched
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Temps d'écran d'un profil enfants : tant que [isPlaying], chaque tranche de [TICK_S] secondes est
 * décomptée ; à zéro, [onTimeUp] arrête la lecture. Sans limite (adulte, ou rien de réglé), ne fait rien.
 */
internal class ScreenTimeGuard(
    private val settings: SettingsRepository,
    private val isPlaying: () -> Boolean,
    private val onTimeUp: () -> Unit,
) {
    /** Temps écoulé : la lecture ne doit pas démarrer. */
    val isUp: Boolean get() = (settings.state.value.screenTimeLeftS() ?: 1) <= 0

    fun start(scope: CoroutineScope) = scope.launch {
        while (true) {
            delay(TICK_S * 1000)
            if (settings.state.value.screenTimeLeftS() == null) continue
            if (isPlaying()) settings.update { withWatched(TICK_S) }
            if (isUp) onTimeUp()
        }
    }

    private companion object {
        const val TICK_S = 30L
    }
}
