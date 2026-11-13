package dev.lumen.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSearchTest {
    @Test
    fun newestFirstWithoutDuplicates() {
        assertEquals(listOf("Minecraft", "zerator"), addRecentSearch(listOf("zerator", "minecraft"), " Minecraft "))
    }

    @Test
    fun ignoresTooShortQueriesAndStaysBounded() {
        assertEquals(listOf("a1"), addRecentSearch(listOf("a1"), "x"))
        val full = (1..8).map { "q$it" }
        assertEquals(listOf("new") + full.take(7), addRecentSearch(full, "new"))
    }
}
