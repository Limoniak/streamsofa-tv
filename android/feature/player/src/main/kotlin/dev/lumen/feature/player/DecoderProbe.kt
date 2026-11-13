package dev.lumen.feature.player

import android.app.ActivityManager
import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import androidx.media3.common.MimeTypes
import dev.lumen.core.Variant

/** Au-delà, les tuiles seraient trop petites pour un écran vu à 3 m. */
const val MAX_TILES = 4

/**
 * Nombre de flux H.264 que l'appareil annonce pouvoir décoder à la fois (1 à [MAX_TILES]).
 * Les fabricants surestiment souvent : le multi-view rattrape à l'exécution (qualité réduite,
 * puis tuile abandonnée) si un décodeur refuse de démarrer.
 */
object DecoderProbe {
    @Volatile
    private var cached: Int? = null

    fun maxStreams(context: Context): Int = cached ?: probe(context).also { cached = it }

    private fun probe(context: Context): Int {
        if (context.getSystemService(ActivityManager::class.java).isLowRamDevice) return 1
        val decoders = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H264, ignoreCase = true) }
            }
        }.getOrDefault(emptyList())
        // Décodeurs matériels d'abord ; sans eux (émulateur), le logiciel décide, prudemment.
        val hardware = decoders.filter { it.isHardware() }
        val instances = (hardware.ifEmpty { decoders }).maxOfOrNull { info ->
            runCatching { info.getCapabilitiesForType(MimeTypes.VIDEO_H264).maxSupportedInstances }.getOrDefault(1)
        } ?: 1
        return if (hardware.isEmpty()) instances.coerceIn(1, 2) else instances.coerceIn(1, MAX_TILES)
    }

    private fun MediaCodecInfo.isHardware(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            !name.startsWith("OMX.google.") && !name.startsWith("c2.android.")
        }
}

/** Hauteur visée par tuile : assez nette à sa taille, sans saturer décodeur ni réseau. */
fun tileHeight(tiles: Int): Int = if (tiles <= 2) 720 else 480

/**
 * Meilleure qualité vidéo dont la hauteur ne dépasse pas [maxHeight] ; la plus faible sinon.
 * [variants] arrivent de la meilleure à la plus faible, comme dans la playlist de Twitch.
 */
fun pickVariant(variants: List<Variant>, maxHeight: Int): Variant? {
    val video = variants.filter { !it.audioOnly }
    return video.firstOrNull { it.height.toInt() in 1..maxHeight } ?: video.lastOrNull()
}

/** Qualité juste en dessous de [current] : repli quand le décodeur refuse. */
fun lowerVariant(variants: List<Variant>, current: Variant): Variant? {
    val video = variants.filter { !it.audioOnly }
    return video.firstOrNull { it.height < current.height && it.height > 0u }
}
