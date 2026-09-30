package com.example.wallettrackers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.util.StatementReminderSchedulePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class StatementReminderSchedulePolicyComponentTest {
    @Test
    fun reminderTargetsNineLocalTimeDaysBeforeDueDate() {
        val dueDate = localTime(2026, Calendar.OCTOBER, 10, 0)
        val now = localTime(2026, Calendar.OCTOBER, 5, 8)
        val expected = localTime(2026, Calendar.OCTOBER, 5, 9).timeInMillis - now.timeInMillis

        assertEquals(expected, StatementReminderSchedulePolicy.delayMillis(dueDate.timeInMillis, 5, now.timeInMillis))
    }

    @Test
    fun dueTodayAfterReminderTimeSchedulesImmediately() {
        val dueToday = localTime(2026, Calendar.OCTOBER, 10, 0)
        val afterNine = localTime(2026, Calendar.OCTOBER, 10, 15)

        assertEquals(0L, StatementReminderSchedulePolicy.delayMillis(dueToday.timeInMillis, 0, afterNine.timeInMillis))
    }

    @Test
    fun missedReminderTimeOnFiveOrOneDayLeadDateSchedulesImmediately() {
        val dueDate = localTime(2026, Calendar.OCTOBER, 10, 0)
        val fiveDaysBeforeAfterNine = localTime(2026, Calendar.OCTOBER, 5, 16)
        val oneDayBeforeAfterNine = localTime(2026, Calendar.OCTOBER, 9, 16)

        assertEquals(0L, StatementReminderSchedulePolicy.delayMillis(dueDate.timeInMillis, 5, fiveDaysBeforeAfterNine.timeInMillis))
        assertEquals(0L, StatementReminderSchedulePolicy.delayMillis(dueDate.timeInMillis, 1, oneDayBeforeAfterNine.timeInMillis))
    }

    @Test
    fun dueTodayBeforeReminderTimeWaitsUntilNineAndOverdueStatementsAreSkipped() {
        val dueToday = localTime(2026, Calendar.OCTOBER, 10, 0)
        val beforeNine = localTime(2026, Calendar.OCTOBER, 10, 8)
        val expectedDelay = TimeUnit.HOURS.toMillis(1)
        val yesterday = localTime(2026, Calendar.OCTOBER, 9, 0)

        assertEquals(expectedDelay, StatementReminderSchedulePolicy.delayMillis(dueToday.timeInMillis, 0, beforeNine.timeInMillis))
        assertNull(StatementReminderSchedulePolicy.delayMillis(yesterday.timeInMillis, 0, beforeNine.timeInMillis))
        assertTrue(StatementReminderSchedulePolicy.delayMillis(dueToday.timeInMillis, -1, beforeNine.timeInMillis) == null)
    }

    private fun localTime(year: Int, month: Int, day: Int, hour: Int): Calendar =
        Calendar.getInstance().apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }
}
