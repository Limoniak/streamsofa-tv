package dev.lumen.designsystem

import java.text.NumberFormat
import java.util.Locale

/** « 842 », « 12,3 k », « 1,2 M » : lisible d'un coup d'œil à 3 m. */
fun formatViewers(count: Int, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return when {
        count >= 1_000_000 -> "${format.format(count / 1_000_000.0)} M"
        count >= 1_000 -> "${format.format(count / 1_000.0)} k"
        else -> count.toString()
    }
}

/** Durée d'un live : « 45 min », « 2 h 05 ». Vide si le début est inconnu. */
fun formatUptime(startedAtSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (startedAtSeconds <= 0) return ""
    val minutes = ((nowMillis / 1000 - startedAtSeconds) / 60).coerceAtLeast(0)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(minutes % 60)}"
}

/** Durée de vidéo : « 12:05 », « 1:02:03 ». */
fun formatDuration(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val (h, m, sec) = Triple(s / 3600, s / 60 % 60, s % 60)
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/**
 * Remplit un modèle d'URL Twitch (`{width}x{height}`) à la taille réellement affichée.
 * Les aperçus de live changent toutes les 5 minutes sous la même URL : [freshnessBucketMs]
 * ajoute un paramètre qui évite de ressortir une image périmée du cache disque.
 */
fun sizedImageUrl(template: String, widthPx: Int, heightPx: Int, freshnessBucketMs: Long = 0): String {
    val url = template.replace("{width}", widthPx.toString()).replace("{height}", heightPx.toString())
    if (freshnessBucketMs <= 0) return url
    val bucket = System.currentTimeMillis() / freshnessBucketMs
    return url + (if ('?' in url) "&" else "?") + "t=$bucket"
}
