package dev.lumen.feature.player

import android.media.audiofx.DynamicsProcessing
import android.os.Build
import androidx.annotation.OptIn
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener

/**
 * « Volume constant » : un compresseur sur la sortie du lecteur rapproche les streamers très
 * calmes et très forts, pour ne plus sursauter en zappant. Effet audio du système (Android 9+),
 * appliqué à la session audio du lecteur seulement ; sans effet si l'appareil le refuse.
 */
@OptIn(UnstableApi::class)
internal class AudioLeveler(private val player: ExoPlayer) {
    private var effect: Any? = null
    private val listener = object : AnalyticsListener {
        override fun onAudioSessionIdChanged(eventTime: AnalyticsListener.EventTime, audioSessionId: Int) {
            if (effect != null) attach(audioSessionId)
        }
    }

    fun setEnabled(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (enabled == (effect != null)) return
        if (enabled) {
            player.addAnalyticsListener(listener)
            attach(player.audioSessionId)
        } else {
            player.removeAnalyticsListener(listener)
            release()
        }
    }

    fun release() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (effect as? DynamicsProcessing)?.let { runCatching { it.release() } }
        effect = null
    }

    private fun attach(sessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        release()
        effect = runCatching { compressor(sessionId) }.getOrNull() ?: UNSUPPORTED
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun compressor(sessionId: Int): DynamicsProcessing {
        val config = DynamicsProcessing.Config.Builder(
            DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
            CHANNELS,
            false, 0, // pas d'égaliseur avant
            true, 1, // une bande de compression, sur tout le spectre
            false, 0, // pas d'égaliseur après
            true, // limiteur : aucun pic ne dépasse
        ).build()
        val band = DynamicsProcessing.MbcBand(true, 20_000f, ATTACK_MS, RELEASE_MS, RATIO, THRESHOLD_DB, 0f, -90f, 1f, 0f, MAKEUP_DB)
        val limiter = DynamicsProcessing.Limiter(true, true, 0, 1f, 60f, 10f, -1f, 0f)
        for (channel in 0 until CHANNELS) {
            config.setMbcBandByChannelIndex(channel, 0, band)
            config.setLimiterByChannelIndex(channel, limiter)
        }
        return DynamicsProcessing(0, sessionId, config).apply { enabled = true }
    }

    private companion object {
        /** Effet refusé par l'appareil : on ne réessaie pas à chaque changement de session. */
        val UNSUPPORTED = Any()
        const val CHANNELS = 2
        const val ATTACK_MS = 20f
        const val RELEASE_MS = 300f
        const val RATIO = 4f
        const val THRESHOLD_DB = -28f
        /** Remonte les voix calmes une fois la dynamique réduite. */
        const val MAKEUP_DB = 9f
    }
}
