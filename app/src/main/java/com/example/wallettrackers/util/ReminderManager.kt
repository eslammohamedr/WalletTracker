package com.example.wallettrackers.util

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.worker.ReminderWorker
import java.util.concurrent.TimeUnit

object ReminderManager {

    private const val TAG = "ReminderMgr"

    internal fun statementReminderWorkNames(smsId: String): List<String> =
        listOf(5, 1, 0).map { daysBefore -> "reminder_${smsId}_$daysBefore" }

    fun scheduleStatementReminders(context: Context, statement: CreditStatement) {
        Log.d(TAG, "scheduleStatementReminders START: smsId=${statement.smsId} card=${statement.cardLast4Digits} amount=${statement.totalAmount} dueDate=${statement.dueDate}")
        val workManager = WorkManager.getInstance(context)

        // 5 Days Before
        scheduleReminder(workManager, statement, 5, "Bill Due in 5 Days")

        // 1 Day Before
        scheduleReminder(workManager, statement, 1, "Bill Due Tomorrow")

        // Same Day
        scheduleReminder(workManager, statement, 0, "Bill Due Today")
        Log.d(TAG, "scheduleStatementReminders END: 3 reminders scheduled")
    }

    private fun scheduleReminder(
        workManager: WorkManager,
        statement: CreditStatement,
        daysBefore: Int,
        title: String
    ) {
        val delay = StatementReminderSchedulePolicy.delayMillis(statement.dueDate.time, daysBefore)

        if (delay != null) {
            Log.d(TAG, "scheduleReminder: '$title' in ${delay / 3600000}h for card ****${statement.cardLast4Digits}")
            val data = Data.Builder()
                .putString("title", title)
                .putString("message", "Payment for your card ending ****${statement.cardLast4Digits} is due.")
                .putString("cardDigits", statement.cardLast4Digits)
                .putDouble("amount", statement.totalAmount)
                .build()

            val reminderRequest = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(data)
                .addTag("reminder_${statement.smsId}_$daysBefore") // Unique tag per statement and reminder type
                .build()

            workManager.enqueueUniqueWork(
                "reminder_${statement.smsId}_$daysBefore",
                ExistingWorkPolicy.REPLACE,
                reminderRequest
            )
        } else {
            Log.d(TAG, "scheduleReminder: skipping '$title' — reminder day already passed")
        }
    }

    fun cancelReminders(context: Context, smsId: String) {
        Log.d(TAG, "cancelReminders: cancelling all reminders for smsId=$smsId")
        val workManager = WorkManager.getInstance(context)
        statementReminderWorkNames(smsId).forEach(workManager::cancelUniqueWork)
    }
}
