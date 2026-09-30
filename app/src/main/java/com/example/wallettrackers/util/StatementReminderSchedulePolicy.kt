package com.example.wallettrackers.util

import java.util.Calendar

object StatementReminderSchedulePolicy {
    fun delayMillis(dueDateMillis: Long, daysBefore: Int, nowMillis: Long = System.currentTimeMillis()): Long? {
        if (daysBefore < 0) return null
        val reminderCalendar = Calendar.getInstance().apply {
            timeInMillis = dueDateMillis
            add(Calendar.DAY_OF_YEAR, -daysBefore)
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nowCalendar = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val isReminderDay =
            nowCalendar.get(Calendar.ERA) == reminderCalendar.get(Calendar.ERA) &&
            nowCalendar.get(Calendar.YEAR) == reminderCalendar.get(Calendar.YEAR) &&
            nowCalendar.get(Calendar.DAY_OF_YEAR) == reminderCalendar.get(Calendar.DAY_OF_YEAR)
        val delay = reminderCalendar.timeInMillis - nowMillis
        return when {
            delay > 0L -> delay
            isReminderDay -> 0L
            else -> null
        }
    }
}
