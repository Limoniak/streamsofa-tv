package dev.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun roundTripsThroughTheStore() {
        val settings = AppSettings(chatByDefault = true, maxQuality = 720, lowLatency = true, accent = "green", checkUpdates = false,
            thirdPartyEmotes = false, hiddenWords = "spoiler, gg", hiddenUsers = "nightbot",
            channelQuality = mapOf("zerator" to "720p60", "gotaga" to QUALITY_AUTO))
        assertEquals(settings, AppSettings.fromMap(settings.toMap()))
    }

    @Test
    fun missingOrInvalidValuesFallBackToDefaults() {
        assertEquals(AppSettings(), AppSettings.fromMap(emptyMap()))
        assertEquals(AppSettings(), AppSettings.fromMap(mapOf("chat_by_default" to "oui", "max_quality" to "hd")))
    }
}
