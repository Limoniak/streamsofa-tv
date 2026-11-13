package dev.lumen.feature.player

import dev.lumen.core.ChatEvent
import dev.lumen.core.ChatFragment
import dev.lumen.core.ChatMessage
import dev.lumen.core.ChatState

/** Au-delà, les plus anciens disparaissent : la mémoire reste bornée même après des heures. */
const val MAX_CHAT_LINES = 150

data class ChatUiState(
    val lines: List<ChatMessage> = emptyList(),
    val state: ChatState = ChatState.CONNECTING,
)

/** Applique un lot d'événements du cœur ; pur, donc testable sans Rust. */
fun ChatUiState.applyEvents(events: List<ChatEvent>, max: Int = MAX_CHAT_LINES): ChatUiState {
    val lines = ArrayList(lines)
    var state = state
    for (event in events) {
        when (event) {
            is ChatEvent.Message -> lines.add(event.message)
            is ChatEvent.ClearUser -> lines.removeAll { it.userId == event.userId }
            is ChatEvent.ClearAll -> lines.clear()
            is ChatEvent.Delete -> lines.removeAll { it.id == event.messageId }
            is ChatEvent.Notice -> lines.add(systemLine(event.text))
            is ChatEvent.State -> state = event.state
        }
    }
    val kept = if (lines.size > max) lines.subList(lines.size - max, lines.size).toList() else lines
    return ChatUiState(kept, state)
}

private var systemCounter = 0L

/** Ligne locale (notice de Twitch, refus d'envoi) : affichée comme un message système. */
fun systemLine(text: String) = ChatMessage(
    id = "lumen:${systemCounter++}",
    userId = "",
    login = "",
    displayName = "",
    color = 0u,
    fragments = emptyList<ChatFragment>(),
    action = false,
    system = text,
    timestampMs = System.currentTimeMillis(),
    badges = emptyList(),
)
