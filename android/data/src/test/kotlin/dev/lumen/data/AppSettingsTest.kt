package dev.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSettingsTest {
    @Test
    fun roundTripsThroughTheStore() {
        val settings = AppSettings(chatByDefault = true, maxQuality = 720, lowLatency = true, accent = "green", checkUpdates = false,
            thirdPartyEmotes = false, chatTimestamps = true, crashLog = true, betaUpdates = true, hiddenWords = "spoiler, gg", hiddenUsers = "nightbot",
            recentSearches = listOf("minecraft", "just chatting"), channelQuality = mapOf("zerator" to "720p60", "gotaga" to QUALITY_AUTO))
        assertEquals(settings, AppSettings.fromMap(settings.toMap()))
    }

    @Test
    fun missingOrInvalidValuesFallBackToDefaults() {
        assertEquals(AppSettings(), AppSettings.fromMap(emptyMap()))
        assertEquals(AppSettings(), AppSettings.fromMap(mapOf("chat_by_default" to "oui", "max_quality" to "hd")))
    }
}

class ChannelListsTest {
    private fun stream(login: String) = dev.lumen.core.LiveStream(
        channelId = login, login = login, displayName = login, avatarUrl = "", title = "", categoryId = "",
        categoryName = "", viewers = 1u, startedAt = 0, thumbnailUrl = "",
    )

    @org.junit.Test
    fun pinnedComeFirstInPinningOrder() {
        val streams = listOf("a", "b", "c", "d").map(::stream)
        org.junit.Assert.assertEquals(listOf("c", "a", "b", "d"), streams.pinnedFirst(listOf("c", "a", "zz")).map { it.login })
        org.junit.Assert.assertEquals(streams, streams.pinnedFirst(emptyList()))
    }

    @org.junit.Test
    fun recentChannelsAreUniqueAndBounded() {
        var settings = AppSettings()
        repeat(30) { settings = settings.withRecentChannel("c$it") }
        settings = settings.withRecentChannel("c25")
        org.junit.Assert.assertEquals(MAX_RECENT_CHANNELS, settings.recentChannels.size)
        org.junit.Assert.assertEquals(listOf("c25", "c29", "c28"), settings.recentChannels.take(3))
    }

    @org.junit.Test
    fun listsSurviveTheDatabaseRoundTrip() {
        val settings = AppSettings()
            .withPinned("a", true).withPinned("b", true).withPinned("a", false)
            .withRecentChannel("x")
            .copy(hiddenCategories = mapOf("509658" to "Just Chatting", "1" to "Jeu : édition spéciale"))
        val back = AppSettings.fromMap(settings.toMap())
        org.junit.Assert.assertEquals(listOf("b"), back.pinnedChannels)
        org.junit.Assert.assertEquals(listOf("x"), back.recentChannels)
        org.junit.Assert.assertEquals(settings.hiddenCategories, back.hiddenCategories)
    }
}
