package dev.lumen.data

/** Un rappel se déclenche 5 min avant le créneau annoncé, et reste valable 12 h après. */
private const val EARLY_S = 5 * 60L
private const val VALID_S = 12 * 3600L
const val MAX_REMINDERS = 20

/** Chaînes dont le créneau rappelé a commencé (ou va commencer) : à vérifier en live. */
fun AppSettings.dueReminders(nowS: Long): List<String> =
    reminders.filter { (_, start) -> nowS >= start - EARLY_S && nowS < start + VALID_S }.keys.toList()

/** Rappel pour [login] au créneau [startS] ; les rappels passés sont oubliés au passage. */
fun AppSettings.withReminder(login: String, startS: Long, nowS: Long): AppSettings =
    copy(reminders = (reminders.filterValues { it + VALID_S > nowS } + (login to startS)).entries.toList().takeLast(MAX_REMINDERS).associate { it.key to it.value })

fun AppSettings.withoutReminder(login: String): AppSettings = copy(reminders = reminders - login)
