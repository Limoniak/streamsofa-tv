package dev.lumen.feature.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import dev.lumen.designsystem.LumenColors
import dev.lumen.designsystem.LumenInsets
import java.util.Locale
import kotlinx.coroutines.delay

/** Mesures du flux lues sur le lecteur, une fois par seconde. */
internal data class StreamStats(
    val resolution: String = "",
    val bitrate: String = "",
    val latency: String = "",
    val dropped: String = "",
)

@OptIn(UnstableApi::class)
internal fun ExoPlayer.stats(): StreamStats {
    val format = videoFormat
    val resolution = format?.let {
        val fps = if (it.frameRate != Format.NO_VALUE.toFloat()) " @ ${it.frameRate.toInt()} fps" else ""
        "${it.width}×${it.height}$fps"
    }.orEmpty()
    // Les variantes HLS annoncent leur débit (BANDWIDTH) ; en Auto, c'est celui de la variante jouée.
    val bits = format?.let { if (it.averageBitrate > 0) it.averageBitrate else it.bitrate }?.takeIf { it > 0 }
    val offset = currentLiveOffset
    return StreamStats(
        resolution = resolution,
        bitrate = bits?.let { String.format(Locale.getDefault(), "%.1f Mb/s", it / 1_000_000.0) }.orEmpty(),
        latency = if (offset != C.TIME_UNSET) String.format(Locale.getDefault(), "%.1f s", offset / 1000.0) else "",
        dropped = videoDecoderCounters?.droppedBufferCount?.toString().orEmpty(),
    )
}

/** Encadré « statistiques » en haut à droite de la vidéo. */
@Composable
internal fun StatsOverlay(player: ExoPlayer, modifier: Modifier = Modifier) {
    var stats by remember { mutableStateOf(StreamStats()) }
    LaunchedEffect(player) {
        while (true) {
            stats = player.stats()
            delay(1_000)
        }
    }
    Column(
        modifier = modifier
            .padding(horizontal = LumenInsets.horizontal, vertical = LumenInsets.vertical)
            .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatRow(stringResource(R.string.stats_resolution), stats.resolution)
        StatRow(stringResource(R.string.stats_bitrate), stats.bitrate)
        StatRow(stringResource(R.string.stats_latency), stats.latency)
        StatRow(stringResource(R.string.stats_dropped), stats.dropped)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = LumenColors.OnSurfaceMuted, modifier = Modifier.width(150.dp))
        Text(value.ifEmpty { "–" }, style = MaterialTheme.typography.bodyMedium)
    }
}
