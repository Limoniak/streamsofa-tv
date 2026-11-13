package dev.lumen.feature.player

import dev.lumen.core.ChatMessage
import dev.lumen.core.ReplayComment

/** On recharge le chat de la VOD quand il en reste moins que ça d'avance sur la lecture. */
private const val LOOKAHEAD_S = 20u

/**
 * Messages de replay en attente de leur instant ; pur pour être testé sans réseau ni lecteur.
 * Le cœur sert le chat par fenêtres à partir d'un offset : on demande la suite avant d'en manquer.
 */
class ReplayBuffer {
    private val pending = ArrayDeque<ReplayComment>()

    /** Offset de la prochaine demande au cœur. */
    var nextOffset: UInt = 0u
        private set

    /** Plus rien après [nextOffset] : fin du chat, ou VOD sans chat. */
    var ended = false
        private set

    /** Saut dans la vidéo : ce qui était en attente ne correspond plus. */
    fun reset(positionS: UInt) {
        pending.clear()
        nextOffset = positionS
        ended = false
    }

    fun needsMore(positionS: UInt): Boolean = !ended && nextOffset <= positionS + LOOKAHEAD_S

    fun add(batch: List<ReplayComment>) {
        val fresh = batch.filter { it.offsetS >= nextOffset }
        if (fresh.isEmpty()) {
            ended = true
            return
        }
        pending.addAll(fresh)
        // Les messages de la même seconde coupés par la fin de la fenêtre sont perdus : rare et sans gravité.
        nextOffset = fresh.last().offsetS + 1u
    }

    /** Messages dont l'instant est atteint, dans l'ordre. */
    fun due(positionS: UInt): List<ChatMessage> {
        val out = ArrayList<ChatMessage>()
        while (pending.isNotEmpty() && pending.first().offsetS <= positionS) out.add(pending.removeFirst().message)
        return out
    }
}
