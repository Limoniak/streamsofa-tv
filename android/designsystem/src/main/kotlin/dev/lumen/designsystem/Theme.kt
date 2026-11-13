package dev.lumen.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ColorScheme
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Marges de sécurité overscan (≈ 5 % d'un écran 960×540 dp). */
object LumenInsets {
    val horizontal = 48.dp
    val vertical = 27.dp
}

object LumenColors {
    val Accent = Color(0xFFB79CFF)
    val OnAccent = Color(0xFF1E1147)
    val Background = Color(0xFF0E0E12)
    val Surface = Color(0xFF17171D)
    val SurfaceRaised = Color(0xFF22222B)
    val OnSurface = Color(0xFFECEAF2)
    val OnSurfaceMuted = Color(0xFFA6A3B3)
    val Success = Color(0xFF6FD9A0)
    val Error = Color(0xFFFF8A80)
}

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = LumenColors.Accent,
    onPrimary = LumenColors.OnAccent,
    background = LumenColors.Background,
    onBackground = LumenColors.OnSurface,
    surface = LumenColors.Surface,
    onSurface = LumenColors.OnSurface,
    surfaceVariant = LumenColors.SurfaceRaised,
    onSurfaceVariant = LumenColors.OnSurfaceMuted,
    error = LumenColors.Error,
)

@Composable
fun LumenTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
