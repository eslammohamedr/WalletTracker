package com.example.wallettrackers.util

import android.content.Context
import android.util.Log
import androidx.work.*
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.worker.BillReminderWorker
import java.util.*
import java.util.concurrent.TimeUnit

object BillReminderManager {

    private const val TAG = "BillReminderMgr"

    fun scheduleBillReminder(context: Context, bill: Bill) {
        Log.d(TAG, "scheduleBillReminder START: bill='${bill.name}' id=${bill.id} day=${bill.dayOfMonth} isActive=${bill.isActive}")
        if (!bill.isActive) {
            Log.d(TAG, "scheduleBillReminder: bill is inactive, skipping")
            return
        }
        val wm = WorkManager.getInstance(context)

        // 1 day before at 9:00 AM
        val delayBefore = msUntilBillDay(bill.dayOfMonth, daysBefore = 1)
        Log.d(TAG, "scheduleBillReminder: delayBefore=${delayBefore}ms (${delayBefore / 3600000}h)")
        if (delayBefore > 0) {
            val data = Data.Builder()
                .putString("billId", bill.id)
                .putString("billName", bill.name)
                .putDouble("amount", bill.amount)
                .putString("currency", bill.currency)
                .putInt("dayOfMonth", bill.dayOfMonth)
                .putBoolean("isDayBefore", true)
                .build()
            wm.enqueueUniqueWork(
                "bill_${bill.id}_before",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BillReminderWorker>()
                    .setInitialDelay(delayBefore, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .build()
            )
            Log.d(TAG, "scheduleBillReminder: scheduled 'day-before' reminder")
        }

        // On the due day at 9:00 AM
        val delayToday = msUntilBillDay(bill.dayOfMonth, daysBefore = 0)
        Log.d(TAG, "scheduleBillReminder: delayToday=${delayToday}ms (${delayToday / 3600000}h)")
        if (delayToday > 0) {
            val data = Data.Builder()
                .putString("billId", bill.id)
                .putString("billName", bill.name)
                .putDouble("amount", bill.amount)
                .putString("currency", bill.currency)
                .putInt("dayOfMonth", bill.dayOfMonth)
                .putBoolean("isDayBefore", false)
                .build()
            wm.enqueueUniqueWork(
                "bill_${bill.id}_today",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<BillReminderWorker>()
                    .setInitialDelay(delayToday, TimeUnit.MILLISECONDS)
                    .setInputData(data)
                    .build()
            )
            Log.d(TAG, "scheduleBillReminder: scheduled 'due-day' reminder")
        }
        Log.d(TAG, "scheduleBillReminder END")
    }

    fun cancelBillReminder(context: Context, billId: String) {
        Log.d(TAG, "cancelBillReminder: cancelling all reminders for billId=$billId")
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork("bill_${billId}_before")
        wm.cancelUniqueWork("bill_${billId}_today")
        wm.cancelUniqueWork("bill_$billId") // cancel legacy format too
        Log.d(TAG, "cancelBillReminder: done")
    }

    private fun msUntilBillDay(dayOfMonth: Int, daysBefore: Int): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, dayOfMonth.coerceIn(1, getActualMaximum(Calendar.DAY_OF_MONTH)))
            set(Calendar.HOUR_OF_DAY, 9)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(now)) add(Calendar.MONTH, 1)
            if (daysBefore > 0) add(Calendar.DAY_OF_MONTH, -daysBefore)
        }
        return target.timeInMillis - now.timeInMillis
    }
}
