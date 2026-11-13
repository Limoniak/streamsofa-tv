package dev.lumen.feature.player

import dev.lumen.core.ReplayComment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayBufferTest {
    private fun comment(offset: UInt, id: String) = ReplayComment(offset, systemLine(id).copy(id = id))

    @Test
    fun releasesMessagesWhenPlaybackReachesThem() {
        val buffer = ReplayBuffer()
        buffer.reset(100u)
        assertTrue(buffer.needsMore(100u))
        buffer.add(listOf(comment(101u, "a"), comment(105u, "b"), comment(140u, "c")))
        assertEquals(141u, buffer.nextOffset)
        assertFalse("40 s d'avance suffisent", buffer.needsMore(100u))

        assertEquals(emptyList<String>(), buffer.due(100u).map { it.id })
        assertEquals(listOf("a", "b"), buffer.due(110u).map { it.id })
        assertTrue(buffer.needsMore(125u))
        assertEquals(listOf("c"), buffer.due(200u).map { it.id })
    }

    @Test
    fun anEmptyWindowEndsTheChatUntilTheNextSeek() {
        val buffer = ReplayBuffer()
        buffer.reset(0u)
        buffer.add(emptyList())
        assertTrue(buffer.ended)
        assertFalse(buffer.needsMore(0u))

        buffer.reset(500u)
        assertFalse(buffer.ended)
        assertTrue(buffer.needsMore(500u))
    }

    @Test
    fun aSeekDropsWhatWasPending() {
        val buffer = ReplayBuffer()
        buffer.reset(0u)
        buffer.add(listOf(comment(10u, "old")))
        buffer.reset(300u)
        buffer.add(listOf(comment(5u, "stale"), comment(301u, "new")))
        assertEquals(listOf("new"), buffer.due(400u).map { it.id })
    }
}
