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
    private val playback = Playback(masterUrl = "https://example.invalid/master.m3u8", variants = emptyList(), expiresAt = 0)

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

    @Test
    fun twitchExpiryShortensTheCache() {
        // Jeton qui expire 90 s après sa préparation : servi pendant 30 s seulement (marge d'une minute).
        val long = PlaybackCache(ttlMs = PREFETCH_TTL_MS, now = { now })
        long.put("bob", playback.copy(expiresAt = 90), null)
        // Plus de la moitié de sa courte vie écoulée : la boucle de préparation le renouvellera.
        now = 29_000
        assertFalse(long.isFresh("bob"))
        assertNotNull(long.take("bob"))
        long.put("bob", playback.copy(expiresAt = 90), null)
        now = 31_000
        assertNull(long.take("bob"))
    }

    @Test
    fun aLongTokenStillRespectsTheCap() {
        val long = PlaybackCache(ttlMs = PREFETCH_TTL_MS, now = { now })
        long.put("bob", playback.copy(expiresAt = 24 * 3600), null)
        now = PREFETCH_TTL_MS
        assertNull(long.take("bob"))
    }

    @Test
    fun refreshFollowsTheShortestToken() {
        val long = PlaybackCache(ttlMs = PREFETCH_TTL_MS, now = { now })
        assertEquals(PREFETCH_REFRESH_MS, long.nextRefreshInMs())
        // Valable 2 min (3 min annoncées moins la marge) : renouvelé à mi-vie, au bout d'une minute.
        long.put("bob", playback.copy(expiresAt = 180), null)
        assertEquals(60_000L, long.nextRefreshInMs())
        // Jamais plus souvent que toutes les 30 s.
        long.put("carol", playback.copy(expiresAt = 70), null)
        assertEquals(30_000L, long.nextRefreshInMs())
    }
}
