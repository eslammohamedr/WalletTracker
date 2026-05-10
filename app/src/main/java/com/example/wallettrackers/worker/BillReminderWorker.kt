package com.example.wallettrackers.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.example.wallettrackers.util.NotificationHelper

class BillReminderWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val name = inputData.getString("billName") ?: return Result.success()
        val amount = inputData.getDouble("amount", 0.0)
        val currency = inputData.getString("currency") ?: "EGP"
        val dayOfMonth = inputData.getInt("dayOfMonth", 1)
        val isDayBefore = inputData.getBoolean("isDayBefore", false)
        NotificationHelper.createChannels(applicationContext)
        NotificationHelper.sendBillReminder(applicationContext, name, amount, currency, dayOfMonth, isDayBefore)
        return Result.success()
    }
}
