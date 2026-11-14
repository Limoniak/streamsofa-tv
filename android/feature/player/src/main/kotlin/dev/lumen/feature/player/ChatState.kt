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
    val room: RoomModes = RoomModes(),
)

/** Modes du salon : qui peut écrire, et à quel rythme. `followersMin` -1 = désactivé. */
data class RoomModes(val emoteOnly: Boolean = false, val slowS: Int = 0, val followersMin: Int = -1, val subsOnly: Boolean = false) {
    val any: Boolean get() = emoteOnly || slowS > 0 || followersMin >= 0 || subsOnly
}

/** Applique un lot d'événements du cœur ; pur, donc testable sans Rust. */
fun ChatUiState.applyEvents(events: List<ChatEvent>, max: Int = MAX_CHAT_LINES): ChatUiState {
    val lines = ArrayList(lines)
    var state = state
    var room = room
    for (event in events) {
        when (event) {
            is ChatEvent.Message -> lines.add(event.message)
            is ChatEvent.ClearUser -> lines.removeAll { it.userId == event.userId }
            is ChatEvent.ClearAll -> lines.clear()
            is ChatEvent.Delete -> lines.removeAll { it.id == event.messageId }
            is ChatEvent.Notice -> lines.add(systemLine(event.text))
            is ChatEvent.State -> state = event.state
            is ChatEvent.Room -> room = room.copy(
                emoteOnly = event.emoteOnly ?: room.emoteOnly,
                slowS = event.slowS ?: room.slowS,
                followersMin = event.followersMin ?: room.followersMin,
                subsOnly = event.subsOnly ?: room.subsOnly,
            )
        }
    }
    val kept = if (lines.size > max) lines.subList(lines.size - max, lines.size).toList() else lines
    return ChatUiState(kept, state, room)
}

/** Le message cite [login], avec ou sans @, en mot entier ; jamais ses propres messages. */
fun ChatMessage.mentions(login: String): Boolean {
    if (login.isEmpty() || system != null || this.login.equals(login, ignoreCase = true)) return false
    val text = fragments.filterIsInstance<ChatFragment.Text>().joinToString(" ") { it.text }
    return Regex("""(?<!\w)@?${Regex.escape(login)}(?!\w)""", RegexOption.IGNORE_CASE).containsMatchIn(text)
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
    replyTo = null,
)

/** Texte du message, émotes comprises par leur nom. */
fun ChatMessage.plainText(): String = fragments.joinToString("") {
    when (it) {
        is ChatFragment.Text -> it.text
        is ChatFragment.Emote -> it.name
    }
}

/** Messages auxquels on peut répondre : ceux des spectateurs, le plus récent en tête. */
fun ChatUiState.replyCandidates(max: Int = 30): List<ChatMessage> =
    lines.filter { it.system == null && it.userId.isNotEmpty() }.takeLast(max).asReversed()
