package dev.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun roundTripsThroughTheStore() {
        val settings = AppSettings(chatByDefault = true, maxQuality = 720, accent = "green", checkUpdates = false,
            thirdPartyEmotes = false, hiddenWords = "spoiler, gg", hiddenUsers = "nightbot")
        assertEquals(settings, AppSettings.fromMap(settings.toMap()))
    }

    @Test
    fun missingOrInvalidValuesFallBackToDefaults() {
        assertEquals(AppSettings(), AppSettings.fromMap(emptyMap()))
        assertEquals(AppSettings(), AppSettings.fromMap(mapOf("chat_by_default" to "oui", "max_quality" to "hd")))
    }
}
