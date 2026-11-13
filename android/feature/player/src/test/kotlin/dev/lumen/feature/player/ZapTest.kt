package dev.lumen.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ZapTest {
    private val logins = listOf("a", "b", "c")

    @Test
    fun wrapsAround() {
        assertEquals("b", zapTarget(logins, "a", 1))
        assertEquals("a", zapTarget(logins, "c", 1))
        assertEquals("c", zapTarget(logins, "a", -1))
    }

    @Test
    fun outsideListStartsAtAnEnd() {
        assertEquals("a", zapTarget(logins, "z", 1))
        assertEquals("c", zapTarget(logins, "z", -1))
    }

    @Test
    fun noTargetWhenAlone() {
        assertNull(zapTarget(listOf("a"), "a", 1))
        assertNull(zapTarget(emptyList(), "a", 1))
    }
}
