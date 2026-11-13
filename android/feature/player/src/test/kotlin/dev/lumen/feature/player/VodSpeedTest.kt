package dev.lumen.feature.player

import dev.lumen.core.Chapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VodSpeedTest {
    @Test
    fun speedCyclesBackToNormal() {
        var speed = 1f
        val seen = (1..VOD_SPEEDS.size).map { speed = nextSpeed(speed); speed }
        assertEquals(listOf(1.25f, 1.5f, 2f, 0.75f, 1f), seen)
    }

    @Test
    fun currentChapterIsTheLastStarted() {
        val chapters = listOf(Chapter(0u, "Just Chatting"), Chapter(880_000u, "World of Warcraft"))
        assertEquals("Just Chatting", chapters.at(879_999)?.title)
        assertEquals("World of Warcraft", chapters.at(880_000)?.title)
        assertNull(emptyList<Chapter>().at(10))
    }
}
