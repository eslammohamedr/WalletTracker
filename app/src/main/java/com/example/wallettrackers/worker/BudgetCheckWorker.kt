package com.example.wallettrackers.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.util.BudgetCalculator
import com.example.wallettrackers.util.NotificationHelper
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.Calendar

class BudgetCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val userId = inputData.getString("userId") ?: return Result.success()

        val db = Firebase.firestore
        val userDoc = db.collection("users").document(userId)

        val budgets = try {
            userDoc.collection("budgets").get().await()
                .documents.mapNotNull { it.toObject(Budget::class.java) }
        } catch (e: Exception) {
            return Result.retry()
        }

        if (budgets.isEmpty()) return Result.success()

        val records = try {
            userDoc.collection("records").get().await()
                .documents.mapNotNull { it.toObject(Record::class.java) }
        } catch (e: Exception) {
            return Result.retry()
        }

        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year  = cal.get(Calendar.YEAR)
        val monthKey = "$year-$month"

        val subcategoryMap = Categories.list.associate { cat ->
            cat.name to cat.subCategories.map { it.name }
        }

        NotificationHelper.createChannels(applicationContext)

        // Check if budget alerts are enabled
        val notifPrefs = applicationContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        if (!notifPrefs.getBoolean("budget_alerts", true)) return Result.success()

        // SharedPreferences tracks which budgets were already notified this month
        // to avoid re-firing the same notification every day
        val prefs = applicationContext.getSharedPreferences("budget_notif_prefs", Context.MODE_PRIVATE)

        for (budget in budgets) {
            if (budget.monthlyLimit <= 0) continue

            val spent = BudgetCalculator.spentInMonth(records, budget.category, month, year, subcategoryMap)
            val pct   = spent / budget.monthlyLimit

            if (pct < 0.75) continue

            val lastPctKey = "pct_${budget.id}_$monthKey"
            val lastNotifiedPct = prefs.getFloat(lastPctKey, 0f)

            // Fire if: newly hit 75 %, or newly crossed 100 %
            val shouldNotify = (pct >= 1.0 && lastNotifiedPct < 1.0) || lastNotifiedPct < 0.75f

            if (shouldNotify) {
                NotificationHelper.sendBudgetAlert(
                    applicationContext,
                    budget.category,
                    spent,
                    budget.monthlyLimit,
                    budget.currency
                )
                prefs.edit().putFloat(lastPctKey, pct.toFloat()).apply()
            }
        }

        return Result.success()
    }
}
