package com.example.wallettrackers.util

import android.content.Context
import androidx.work.*
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.worker.ReminderWorker
import java.util.concurrent.TimeUnit

object ReminderManager {

    fun scheduleStatementReminders(context: Context, statement: CreditStatement) {
        val workManager = WorkManager.getInstance(context)
        
        // 5 Days Before
        scheduleReminder(workManager, statement, 5, "Bill Due in 5 Days")
        
        // 1 Day Before
        scheduleReminder(workManager, statement, 1, "Bill Due Tomorrow")
        
        // Same Day
        scheduleReminder(workManager, statement, 0, "Bill Due Today")
    }

    private fun scheduleReminder(
        workManager: WorkManager,
        statement: CreditStatement,
        daysBefore: Int,
        title: String
    ) {
        val reminderTime = statement.dueDate.time - TimeUnit.DAYS.toMillis(daysBefore.toLong())
        val delay = reminderTime - System.currentTimeMillis()

        if (delay > 0) {
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
        }
    }

    fun cancelReminders(context: Context, smsId: String) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork("reminder_${smsId}_5")
        workManager.cancelUniqueWork("reminder_${smsId}_1")
        workManager.cancelUniqueWork("reminder_${smsId}_0")
    }
}
