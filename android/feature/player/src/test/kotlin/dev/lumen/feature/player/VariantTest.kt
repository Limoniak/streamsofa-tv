package dev.lumen.feature.player

import dev.lumen.core.Variant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VariantTest {
    private fun v(name: String, height: Int, audio: Boolean = false) =
        Variant(name, "u/$name", 0uL, 0u, height.toUInt(), 30f, source = false, audioOnly = audio)

    private val variants = listOf(v("1080p60", 1080), v("720p60", 720), v("480p", 480), v("160p", 160), v("audio_only", 0, true))

    @Test
    fun picksTheBestQualityThatFitsTheTile() {
        assertEquals("720p60", pickVariant(variants, tileHeight(2))?.name)
        assertEquals("480p", pickVariant(variants, tileHeight(4))?.name)
        assertEquals("160p", pickVariant(variants, 100)?.name)
        assertNull(pickVariant(listOf(v("audio_only", 0, true)), 720))
    }

    @Test
    fun stepsDownWhenTheDecoderRefuses() {
        assertEquals("480p", lowerVariant(variants, v("720p60", 720))?.name)
        assertNull(lowerVariant(variants, v("160p", 160)))
    }
}
