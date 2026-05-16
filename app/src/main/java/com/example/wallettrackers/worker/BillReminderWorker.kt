package com.example.wallettrackers.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.util.BillReminderManager
import com.example.wallettrackers.util.NotificationHelper

class BillReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        Log.d("BillWorker", "doWork START: processing bill reminder")
        val billId = inputData.getString("billId") ?: ""
        val name = inputData.getString("billName") ?: run {
            Log.d("BillWorker", "doWork: no billName in input, returning success")
            return Result.success()
        }
        val amount = inputData.getDouble("amount", 0.0)
        val currency = inputData.getString("currency") ?: "EGP"
        val dayOfMonth = inputData.getInt("dayOfMonth", 1)
        val isDayBefore = inputData.getBoolean("isDayBefore", false)
        Log.d("BillWorker", "doWork: bill='$name' amount=$amount currency=$currency day=$dayOfMonth isDayBefore=$isDayBefore")

        // Check if bill reminders are enabled
        val notifPrefs = applicationContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        if (!notifPrefs.getBoolean("bill_reminders", true)) {
            Log.d("BillWorker", "doWork: bill reminders DISABLED, skipping notification")
            return Result.success()
        }

        NotificationHelper.createChannels(applicationContext)
        NotificationHelper.sendBillReminder(applicationContext, name, amount, currency, dayOfMonth, isDayBefore)
        Log.d("BillWorker", "doWork: notification sent")

        // Reschedule for next month so reminders repeat automatically
        val bill = Bill(id = billId, name = name, amount = amount, currency = currency, dayOfMonth = dayOfMonth)
        BillReminderManager.scheduleBillReminder(applicationContext, bill)
        Log.d("BillWorker", "doWork END: rescheduled for next month")

        return Result.success()
    }
}
