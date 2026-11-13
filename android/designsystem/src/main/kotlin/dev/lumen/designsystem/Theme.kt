package dev.lumen.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Marges de sécurité overscan (≈ 5 % d'un écran 960×540 dp). */
object LumenInsets {
    val horizontal = 48.dp
    val vertical = 27.dp
}

/** Couleurs d'accent proposées dans les paramètres ; [key] est la valeur enregistrée. */
enum class AccentColor(val key: String, val color: Color, val onColor: Color) {
    Violet("violet", Color(0xFFB79CFF), Color(0xFF1E1147)),
    Blue("blue", Color(0xFF8CC4FF), Color(0xFF0B2547)),
    Green("green", Color(0xFF7FE0A8), Color(0xFF0B3320)),
    // Clé « orange » conservée : c'est la valeur déjà enregistrée chez les utilisateurs.
    Peach("orange", Color(0xFFFFB38A), Color(0xFF3D1E0C)),
    Pink("pink", Color(0xFFFF9CCB), Color(0xFF450F2B)),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Violet
    }
}

object LumenColors {
    /** État Compose : changer d'accent recompose tout ce qui le lit, sans redémarrer l'Activity. */
    var accent by mutableStateOf(AccentColor.Violet)
    val Accent get() = accent.color
    val OnAccent get() = accent.onColor
    val Background = Color(0xFF130F19)
    val Surface = Color(0xFF1B1622)
    val SurfaceRaised = Color(0xFF261F30)
    val OnSurface = Color(0xFFF4EEE8)
    val OnSurfaceMuted = Color(0xFFA99FB6)
    val Success = Color(0xFF6FD9A0)
    val Error = Color(0xFFFF8A80)
    val Live = Color(0xFFE5484D)
    val Skeleton = Color(0xFF2E2639)
}

private fun darkScheme(): ColorScheme = darkColorScheme(
    primary = LumenColors.Accent,
    onPrimary = LumenColors.OnAccent,
    background = LumenColors.Background,
    onBackground = LumenColors.OnSurface,
    surface = LumenColors.Surface,
    onSurface = LumenColors.OnSurface,
    surfaceVariant = LumenColors.SurfaceRaised,
    onSurfaceVariant = LumenColors.OnSurfaceMuted,
    error = LumenColors.Error,
    border = LumenColors.Accent,
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    val accent = LumenColors.accent
    val scheme = remember(accent) { darkScheme() }
    MaterialTheme(colorScheme = scheme) {
        // Texte clair par défaut hors Surface (titres de rangées, écrans plein cadre).
        CompositionLocalProvider(LocalContentColor provides LumenColors.OnSurface, content = content)
    }
}
