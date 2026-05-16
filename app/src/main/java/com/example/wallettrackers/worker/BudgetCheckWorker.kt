package com.example.wallettrackers.worker

import android.content.Context
import android.util.Log
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

    private val TAG = "BudgetWorker"

    override suspend fun doWork(): Result {
        Log.d(TAG, "doWork START: background budget check triggered")
        val userId = inputData.getString("userId") ?: run {
            Log.d(TAG, "doWork: no userId in input, returning success")
            return Result.success()
        }
        Log.d(TAG, "doWork: userId=$userId")

        val db = Firebase.firestore
        val userDoc = db.collection("users").document(userId)

        val budgets = try {
            val result = userDoc.collection("budgets").get().await()
                .documents.mapNotNull { it.toObject(Budget::class.java) }
            Log.d(TAG, "doWork: fetched ${result.size} budgets from Firestore")
            result
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to fetch budgets, will retry", e)
            return Result.retry()
        }

        if (budgets.isEmpty()) {
            Log.d(TAG, "doWork: no budgets configured, returning success")
            return Result.success()
        }

        val records = try {
            val result = userDoc.collection("records").get().await()
                .documents.mapNotNull { it.toObject(Record::class.java) }
            Log.d(TAG, "doWork: fetched ${result.size} records from Firestore")
            result
        } catch (e: Exception) {
            Log.e(TAG, "doWork: failed to fetch records, will retry", e)
            return Result.retry()
        }

        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year  = cal.get(Calendar.YEAR)
        val monthKey = "$year-$month"
        Log.d(TAG, "doWork: checking month=$month year=$year")

        val subcategoryMap = Categories.list.associate { cat ->
            cat.name to cat.subCategories.map { it.name }
        }

        NotificationHelper.createChannels(applicationContext)

        // Check if budget alerts are enabled
        val notifPrefs = applicationContext.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
        if (!notifPrefs.getBoolean("budget_alerts", true)) {
            Log.d(TAG, "doWork: budget alerts DISABLED in settings, returning success")
            return Result.success()
        }

        // SharedPreferences tracks which budgets were already notified this month
        // to avoid re-firing the same notification every day
        val prefs = applicationContext.getSharedPreferences("budget_notif_prefs", Context.MODE_PRIVATE)

        var alertsSent = 0
        for (budget in budgets) {
            if (budget.monthlyLimit <= 0) {
                Log.d(TAG, "doWork: skipping budget '${budget.category}' (limit=${budget.monthlyLimit})")
                continue
            }

            val spent = BudgetCalculator.spentInMonth(records, budget.category, month, year, subcategoryMap)
            val pct   = spent / budget.monthlyLimit
            Log.d(TAG, "doWork: budget='${budget.category}' spent=${"%.2f".format(spent)} limit=${budget.monthlyLimit} pct=${"%.0f".format(pct * 100)}%")

            if (pct < 0.75) {
                Log.d(TAG, "doWork: pct < 75%, no alert needed for '${budget.category}'")
                continue
            }

            val lastPctKey = "pct_${budget.id}_$monthKey"
            val lastNotifiedPct = prefs.getFloat(lastPctKey, 0f)

            // Fire if: newly hit 75 %, or newly crossed 100 %
            val shouldNotify = (pct >= 1.0 && lastNotifiedPct < 1.0) || lastNotifiedPct < 0.75f
            Log.d(TAG, "doWork: lastNotifiedPct=$lastNotifiedPct shouldNotify=$shouldNotify")

            if (shouldNotify) {
                Log.d(TAG, "doWork: SENDING budget alert for '${budget.category}'")
                NotificationHelper.sendBudgetAlert(
                    applicationContext,
                    budget.category,
                    spent,
                    budget.monthlyLimit,
                    budget.currency
                )
                prefs.edit().putFloat(lastPctKey, pct.toFloat()).apply()
                alertsSent++
            }
        }

        Log.d(TAG, "doWork END: checked ${budgets.size} budgets, sent $alertsSent alerts")
        return Result.success()
    }
}
