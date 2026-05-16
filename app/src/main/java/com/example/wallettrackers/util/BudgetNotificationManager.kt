package com.example.wallettrackers.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.wallettrackers.worker.BudgetCheckWorker
import java.util.concurrent.TimeUnit

object BudgetNotificationManager {

    private fun workName(userId: String) = "budget_check_$userId"

    /** Schedule a daily background budget check. Safe to call multiple times — KEEP prevents duplicate workers. */
    fun scheduleDailyCheck(context: Context, userId: String) {
        val data = Data.Builder()
            .putString("userId", userId)
            .build()

        val request = PeriodicWorkRequestBuilder<BudgetCheckWorker>(24, TimeUnit.HOURS)
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            workName(userId),
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun cancel(context: Context, userId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(userId))
    }
}
