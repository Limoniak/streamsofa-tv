package dev.lumen.feature.player

import dev.lumen.core.ClipQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClipQualityTest {
    private val qualities = listOf(1080u, 720u, 360u).map { ClipQuality(it, "u$it") }

    @Test
    fun picksBestUnderLimit() {
        assertEquals(1080u, pickClipQuality(qualities, 0)?.height)
        assertEquals(720u, pickClipQuality(qualities, 720)?.height)
        assertEquals(720u, pickClipQuality(qualities, 900)?.height)
        // Rien sous la limite : la plus basse plutôt que rien.
        assertEquals(360u, pickClipQuality(qualities, 160)?.height)
        assertNull(pickClipQuality(emptyList(), 720))
    }
}
