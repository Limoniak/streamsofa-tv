package dev.lumen.designsystem

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun viewers() {
        val fr = Locale.FRANCE
        assertEquals("842", formatViewers(842, fr))
        assertEquals("12,3 k", formatViewers(12_345, fr))
        assertEquals("1 k", formatViewers(1_000, fr))
        assertEquals("1,2 M", formatViewers(1_234_567, fr))
    }

    @Test
    fun uptime() {
        val start = 1_000_000L
        assertEquals("", formatUptime(0))
        assertEquals("45 min", formatUptime(start, (start + 45 * 60) * 1000))
        assertEquals("2 h 05", formatUptime(start, (start + 125 * 60) * 1000))
    }

    @Test
    fun duration() {
        assertEquals("0:09", formatDuration(9))
        assertEquals("12:05", formatDuration(725))
        assertEquals("1:02:03", formatDuration(3723))
    }

    @Test
    fun sizedUrl() {
        val template = "https://x/live_user_a-{width}x{height}.jpg"
        assertEquals("https://x/live_user_a-544x306.jpg", sizedImageUrl(template, 544, 306))
        assert(sizedImageUrl(template, 1, 1, 300_000).contains(".jpg?t="))
    }
}
