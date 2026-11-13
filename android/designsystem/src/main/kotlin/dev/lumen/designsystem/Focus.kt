package dev.lumen.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * Retient la dernière carte ouverte d'un écran pour lui rendre le focus au retour
 * (sinon la télécommande repartirait du haut de la page).
 */
class FocusMemory(var key: String?) {
    val requester = FocusRequester()

    /** La carte à focaliser : la dernière ouverte si elle est encore là, sinon [default]. */
    fun target(keys: Collection<String>, default: String?): String? = key?.takeIf { it in keys } ?: default
}

@Composable
fun rememberFocusMemory(): FocusMemory =
    rememberSaveable(saver = Saver({ it.key }, { FocusMemory(it) })) { FocusMemory(null) }

fun Modifier.restoreFocusHere(memory: FocusMemory, isTarget: Boolean): Modifier =
    if (isTarget) focusRequester(memory.requester) else this

/**
 * Les listes paresseuses ne composent leurs éléments qu'à la mesure : on retente pendant
 * quelques images au lieu d'échouer si la carte n'est pas encore attachée.
 */
suspend fun FocusRequester.requestWhenAttached(frames: Int = 10) {
    repeat(frames) {
        // `false` (ou une exception selon la version de Compose) : pas encore attachée.
        if (runCatching { requestFocus() }.getOrDefault(false)) return
        withFrameNanos { }
    }
}
