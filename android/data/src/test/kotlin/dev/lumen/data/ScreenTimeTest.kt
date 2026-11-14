package dev.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenTimeTest {
    private val kids = AppSettings(kids = true, kidsDailyMinutes = 60)

    @Test
    fun noLimitForAdultsOrWithoutALimit() {
        assertNull(AppSettings(kidsDailyMinutes = 60).screenTimeLeftS("2026-11-14"))
        assertNull(AppSettings(kids = true).screenTimeLeftS("2026-11-14"))
    }

    @Test
    fun watchingCountsDownAndANewDayStartsOver() {
        val watched = kids.withWatched(1_800, "2026-11-14").withWatched(600, "2026-11-14")
        assertEquals(1_200L, watched.screenTimeLeftS("2026-11-14"))
        assertEquals(3_600L, watched.screenTimeLeftS("2026-11-15"))
        assertEquals(30L, watched.withWatched(30, "2026-11-15").screenTime?.second)
    }

    @Test
    fun extraTimeOnlyCountsOnItsDay() {
        val settings = kids.copy(kidsExtra = "2026-11-14" to 30).withWatched(3_600, "2026-11-14")
        assertEquals(1_800L, settings.screenTimeLeftS("2026-11-14"))
        assertEquals(3_600L, settings.screenTimeLeftS("2026-11-15"))
    }

    @Test
    fun usageSurvivesTheDatabaseRoundTrip() {
        val settings = kids.withWatched(90, "2026-11-14")
        val map = settings.toMap() + ("kids_daily_minutes" to "60") + ("kids_extra" to "2026-11-14\t15") + ("kids_profile" to "true")
        val back = AppSettings.fromMap(map)
        assertEquals("2026-11-14" to 90L, back.screenTime)
        assertEquals("2026-11-14" to 15, back.kidsExtra)
        assertEquals(60 * 60L + 15 * 60L - 90L, back.screenTimeLeftS("2026-11-14"))
    }
}
