package dev.lumen.feature.player

import dev.lumen.core.LiveStream
import dev.lumen.core.Playback

/**
 * Jeton de lecture et infos d'une chaîne voisine, obtenus avant que l'utilisateur ne zappe.
 * [validUntilMs] : le plus tôt de « préparé + TTL » et de l'expiration annoncée par Twitch, moins une marge.
 */
internal class Prefetched(val playback: Playback, val stream: LiveStream?, val atMs: Long, val validUntilMs: Long)

/**
 * Zapping rapide : les chaînes voisines (▲ et ▼) sont préparées pendant qu'on regarde, pour que le
 * changement de chaîne saute les allers-retours GQL et usher. Seuls le jeton et la playlist maîtresse
 * sont gardés : aucun flux vidéo n'est décodé en plus.
 *
 * Un jeton n'est servi qu'une fois ([take]) et seulement s'il est récent : s'il est refusé, la relance
 * automatique du lecteur en demande un neuf.
 */
internal class PlaybackCache(private val ttlMs: Long = PREFETCH_TTL_MS, private val now: () -> Long = System::currentTimeMillis) {
    private val entries = HashMap<String, Prefetched>()

    @Synchronized
    fun put(login: String, playback: Playback, stream: LiveStream?) {
        val at = now()
        val announced = if (playback.expiresAt > 0) playback.expiresAt * 1000 - EXPIRY_MARGIN_MS else Long.MAX_VALUE
        entries[login] = Prefetched(playback, stream, at, minOf(at + ttlMs, announced))
    }

    /** Entrée encore valable pour [login], retirée du cache. */
    @Synchronized
    fun take(login: String): Prefetched? = entries.remove(login)?.takeIf { now() < it.validUntilMs }

    /** Vraie si [login] a une entrée valable encore au moins la moitié de sa durée : inutile de la redemander. */
    @Synchronized
    fun isFresh(login: String): Boolean = entries[login]?.let { now() < it.atMs + (it.validUntilMs - it.atMs) / 2 } == true

    /** Délai avant de renouveler : à mi-vie de l'entrée la plus courte, entre 30 s et [PREFETCH_REFRESH_MS]. */
    @Synchronized
    fun nextRefreshInMs(): Long {
        val soonest = entries.values.minOfOrNull { it.atMs + (it.validUntilMs - it.atMs) / 2 } ?: return PREFETCH_REFRESH_MS
        return (soonest - now()).coerceIn(MIN_REFRESH_MS, PREFETCH_REFRESH_MS)
    }

    /** Ne garde que les chaînes encore voisines. */
    @Synchronized
    fun retain(logins: Set<String>) {
        entries.keys.retainAll(logins)
    }
}

/** Chaînes voisines de [current] dans la liste de zapping, celle de ▲ d'abord ; sans doublon ni [current]. */
internal fun zapNeighbours(logins: List<String>, current: String): List<String> =
    listOfNotNull(zapTarget(logins, current, 1), zapTarget(logins, current, -1)).distinct().filter { it != current }

/** Plafond, même si Twitch annonce un jeton valable plus longtemps. */
internal const val PREFETCH_TTL_MS = 5 * 60_000L
private const val MIN_REFRESH_MS = 30_000L

/** Un jeton n'est jamais servi dans sa dernière minute : le temps de charger la playlist et les segments. */
internal const val EXPIRY_MARGIN_MS = 60_000L
/** Rafraîchis tant qu'on regarde la même chaîne : un zap au bout d'une heure reste rapide. */
internal const val PREFETCH_REFRESH_MS = 4 * 60_000L
