package dev.lumen.data

import java.time.LocalDate

/** Jour local de l'appareil, `AAAA-MM-JJ` : le temps d'écran repart de zéro chaque jour. */
fun today(): String = LocalDate.now().toString()

/**
 * Temps d'écran restant aujourd'hui pour un profil enfants, en secondes ; `null` sans limite
 * (profil adulte, ou aucune limite réglée). Peut être négatif ou nul : le temps est écoulé.
 */
fun AppSettings.screenTimeLeftS(day: String = today()): Long? {
    if (!kids || kidsDailyMinutes <= 0) return null
    val extra = kidsExtra?.takeIf { it.first == day }?.second ?: 0
    val used = screenTime?.takeIf { it.first == day }?.second ?: 0L
    return (kidsDailyMinutes + extra) * 60L - used
}

/** Ajoute [seconds] de lecture au jour [day] (un nouveau jour repart de zéro). */
fun AppSettings.withWatched(seconds: Long, day: String = today()): AppSettings {
    val used = screenTime?.takeIf { it.first == day }?.second ?: 0L
    return copy(screenTime = day to used + seconds)
}
