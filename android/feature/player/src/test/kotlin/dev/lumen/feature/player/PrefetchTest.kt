package dev.lumen.feature.player

import dev.lumen.core.Playback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefetchTest {
    private var now = 0L
    private val cache = PlaybackCache(ttlMs = 1_000, now = { now })
    private val playback = Playback(masterUrl = "https://example.invalid/master.m3u8", variants = emptyList())

    @Test
    fun aTokenIsServedOnlyOnce() {
        cache.put("bob", playback, null)
        assertNotNull(cache.take("bob"))
        // Relance après un jeton refusé : il faut en demander un neuf.
        assertNull(cache.take("bob"))
    }

    @Test
    fun anOldTokenIsNeverServed() {
        cache.put("bob", playback, null)
        now = 1_000
        assertNull(cache.take("bob"))
    }

    @Test
    fun freshnessDecidesWhenToFetchAgain() {
        cache.put("bob", playback, null)
        assertTrue(cache.isFresh("bob"))
        now = 600
        assertFalse(cache.isFresh("bob"))
        assertFalse(cache.isFresh("carol"))
    }

    @Test
    fun retainDropsChannelsThatAreNoLongerNeighbours() {
        cache.put("bob", playback, null)
        cache.put("carol", playback, null)
        cache.retain(setOf("carol"))
        assertNull(cache.take("bob"))
        assertNotNull(cache.take("carol"))
    }

    @Test
    fun neighboursAreUpThenDown() {
        assertEquals(listOf("c", "a"), zapNeighbours(listOf("a", "b", "c"), "b"))
        // Deux chaînes : la même voisine des deux côtés, préparée une seule fois.
        assertEquals(listOf("b"), zapNeighbours(listOf("a", "b"), "a"))
        assertEquals(emptyList<String>(), zapNeighbours(listOf("a"), "a"))
    }
}
