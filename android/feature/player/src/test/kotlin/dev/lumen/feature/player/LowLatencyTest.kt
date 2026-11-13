package dev.lumen.feature.player

import org.junit.Assert.assertEquals
import org.junit.Test

class LowLatencyTest {
    @Test
    fun prefetchBecomesRegularSegments() {
        val playlist = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:2.002,live
            https://cdn/a.ts
            #EXT-X-TWITCH-PREFETCH:https://cdn/b.ts
            #EXT-X-TWITCH-PREFETCH:https://cdn/c.ts
        """.trimIndent()
        val expected = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXTINF:2.002,live
            https://cdn/a.ts
            #EXTINF:2.002,live
            https://cdn/b.ts
            #EXTINF:2.002,live
            https://cdn/c.ts
        """.trimIndent()
        assertEquals(expected, rewritePrefetch(playlist))
    }

    @Test
    fun playlistWithoutPrefetchIsUntouched() {
        val playlist = "#EXTM3U\r\n#EXTINF:2.000,live\r\nhttps://cdn/a.ts\r\n"
        assertEquals(playlist, rewritePrefetch(playlist))
    }
}
