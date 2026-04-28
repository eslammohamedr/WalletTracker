package com.example.wallettrackers.util

import android.content.Context
import androidx.work.*
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.worker.BillReminderWorker
import java.util.*
import java.util.concurrent.TimeUnit

object BillReminderManager {

    fun scheduleBillReminder(context: Context, bill: Bill) {
        if (!bill.isActive) return
        val delay = msUntilNextBillDay(bill.dayOfMonth)
        if (delay <= 0) return

        val data = Data.Builder()
            .putString("billId", bill.id)
            .putString("billName", bill.name)
            .putDouble("amount", bill.amount)
            .putString("currency", bill.currency)
            .putInt("dayOfMonth", bill.dayOfMonth)
            .build()

        val request = OneTimeWorkRequestBuilder<BillReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("bill_${bill.id}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "bill_${bill.id}",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelBillReminder(context: Context, billId: String) {
        WorkManager.getInstance(context).cancelUniqueWork("bill_$billId")
    }

    private fun msUntilNextBillDay(dayOfMonth: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceIn(1, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.MONTH, 1)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
