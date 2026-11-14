package dev.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    @Test
    fun defaultShowsEveryRow() {
        assertEquals(DEFAULT_HOME_ROWS, parseHomeRows(null))
        assertEquals(DEFAULT_HOME_ROWS, parseHomeRows(""))
    }

    @Test
    fun orderAndHiddenRowsSurviveTheRoundTrip() {
        val layout = DEFAULT_HOME_ROWS.moved(HomeRow.Popular, -3).toggled(HomeRow.Recent)
        assertEquals(layout, parseHomeRows(layout.toHomeRowsValue()))
        assertEquals(false, layout.first { it.row == HomeRow.Recent }.visible)
    }

    @Test
    fun unknownKeysAreDroppedAndNewRowsAppearAtTheEnd() {
        val rows = parseHomeRows("popular,-followed,gone,popular")
        assertEquals(listOf(HomeRow.Popular, HomeRow.Followed), rows.take(2).map { it.row })
        assertEquals(false, rows[1].visible)
        assertEquals(HomeRow.entries.size, rows.size)
        assert(rows.drop(2).all { it.visible })
    }

    @Test
    fun movingStopsAtTheEdges() {
        assertEquals(DEFAULT_HOME_ROWS, DEFAULT_HOME_ROWS.moved(HomeRow.Followed, -1))
        assertEquals(DEFAULT_HOME_ROWS, DEFAULT_HOME_ROWS.moved(HomeRow.Categories, 1))
        assertEquals(HomeRow.ContinueWatching, DEFAULT_HOME_ROWS.moved(HomeRow.Followed, 1).first().row)
    }
}
