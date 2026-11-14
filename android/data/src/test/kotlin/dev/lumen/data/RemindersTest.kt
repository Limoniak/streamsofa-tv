package dev.lumen.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RemindersTest {
    @Test
    fun aReminderIsDueFromFiveMinutesBeforeUntilTwelveHoursAfter() {
        val settings = AppSettings().withReminder("zerator", startS = 10_000, nowS = 0)
        assertEquals(emptyList<String>(), settings.dueReminders(10_000 - 301))
        assertEquals(listOf("zerator"), settings.dueReminders(10_000 - 300))
        assertEquals(listOf("zerator"), settings.dueReminders(10_000 + 3600))
        assertEquals(emptyList<String>(), settings.dueReminders(10_000 + 12 * 3600))
    }

    @Test
    fun oldRemindersAreForgottenAndTheListStaysBounded() {
        var settings = AppSettings().withReminder("old", startS = 0, nowS = 0)
        settings = settings.withReminder("new", startS = 100_000, nowS = 50_000)
        assertEquals(setOf("new"), settings.reminders.keys)
        repeat(30) { settings = settings.withReminder("c$it", 100_000, 50_000) }
        assertEquals(MAX_REMINDERS, settings.reminders.size)
        assertEquals(emptyMap<String, Long>(), settings.withoutReminder("c29").reminders.filterKeys { it == "c29" })
    }

    @Test
    fun remindersSurviveTheDatabaseRoundTrip() {
        val settings = AppSettings().withReminder("zerator", 1_800_000_000, 1_700_000_000)
        assertEquals(settings.reminders, AppSettings.fromMap(settings.toMap()).reminders)
    }
}
