package dev.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test
    fun markdownBecomesPlainText() {
        val notes = "\n### Added\n- Rows on `Home`.\n- Favourites.\n\n### Fixed\n- OK.\n"
        assertEquals("Added\n• Rows on Home.\n• Favourites.\n\nFixed\n• OK.", plainNotes(notes))
    }
}
