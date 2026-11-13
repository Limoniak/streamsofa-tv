package dev.lumen.feature.player

import dev.lumen.core.ChatEvent
import dev.lumen.core.ChatFragment
import dev.lumen.core.ChatMessage
import dev.lumen.core.ChatState
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatStateTest {
    private fun msg(id: String, user: String = "u$id") = ChatMessage(
        id = id, userId = user, login = user, displayName = user, color = 0u,
        fragments = listOf(ChatFragment.Text("salut")), action = false, system = null, timestampMs = 0, badges = emptyList(),
    )

    private fun ChatUiState.ids() = lines.map { it.id }

    @Test
    fun moderationRemovesLines() {
        val start = ChatUiState().applyEvents(listOf(msg("1", "a"), msg("2", "b"), msg("3", "a")).map { ChatEvent.Message(it) })
        assertEquals(listOf("2"), start.applyEvents(listOf(ChatEvent.ClearUser("a"))).ids())
        assertEquals(listOf("1", "3"), start.applyEvents(listOf(ChatEvent.Delete("2"))).ids())
        assertEquals(emptyList<String>(), start.applyEvents(listOf(ChatEvent.ClearAll)).ids())
    }

    @Test
    fun keepsOnlyTheMostRecentLines() {
        val state = ChatUiState().applyEvents((1..10).map { ChatEvent.Message(msg("$it")) }, max = 3)
        assertEquals(listOf("8", "9", "10"), state.ids())
    }

    @Test
    fun tracksConnectionState() {
        val state = ChatUiState().applyEvents(listOf(ChatEvent.State(ChatState.JOINED)))
        assertEquals(ChatState.JOINED, state.state)
    }
}
