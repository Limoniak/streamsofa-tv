package dev.lumen.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * QR code de [count] × [count] modules (`true` = sombre, ligne par ligne).
 * Fond blanc avec marge : la « zone calme » dont les lecteurs de QR ont besoin.
 */
@Composable
fun QrCode(count: Int, modules: List<Boolean>, modifier: Modifier = Modifier, size: Dp = 300.dp) {
    Canvas(
        modifier = modifier
            .size(size)
            .background(Color.White, RoundedCornerShape(size / 15))
            .padding(size / 12),
    ) {
        val cell = this.size.minDimension / count
        // Arrondi au pixel pour éviter les liserés clairs entre modules voisins.
        for (y in 0 until count) {
            val top = floor(y * cell)
            val bottom = floor((y + 1) * cell)
            for (x in 0 until count) {
                if (modules[y * count + x]) {
                    val left = floor(x * cell)
                    val right = floor((x + 1) * cell)
                    drawRect(Color.Black, Offset(left, top), Size(right - left, bottom - top))
                }
            }
        }
    }
}
