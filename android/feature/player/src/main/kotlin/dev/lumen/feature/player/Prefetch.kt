package dev.lumen.feature.player

import dev.lumen.core.LiveStream
import dev.lumen.core.Playback

/** Jeton de lecture et infos d'une chaîne voisine, obtenus avant que l'utilisateur ne zappe. */
internal class Prefetched(val playback: Playback, val stream: LiveStream?, val atMs: Long)

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
        entries[login] = Prefetched(playback, stream, now())
    }

    /** Entrée encore valable pour [login], retirée du cache. */
    @Synchronized
    fun take(login: String): Prefetched? = entries.remove(login)?.takeIf { now() - it.atMs < ttlMs }

    /** Vraie si [login] a déjà une entrée récente : inutile de la redemander. */
    @Synchronized
    fun isFresh(login: String): Boolean = entries[login]?.let { now() - it.atMs < ttlMs / 2 } == true

    /** Ne garde que les chaînes encore voisines. */
    @Synchronized
    fun retain(logins: Set<String>) {
        entries.keys.retainAll(logins)
    }
}

/** Chaînes voisines de [current] dans la liste de zapping, celle de ▲ d'abord ; sans doublon ni [current]. */
internal fun zapNeighbours(logins: List<String>, current: String): List<String> =
    listOfNotNull(zapTarget(logins, current, 1), zapTarget(logins, current, -1)).distinct().filter { it != current }

/** Les jetons Twitch valent plusieurs minutes ; on reste bien en deçà. */
internal const val PREFETCH_TTL_MS = 5 * 60_000L
/** Rafraîchis tant qu'on regarde la même chaîne : un zap au bout d'une heure reste rapide. */
internal const val PREFETCH_REFRESH_MS = 4 * 60_000L
