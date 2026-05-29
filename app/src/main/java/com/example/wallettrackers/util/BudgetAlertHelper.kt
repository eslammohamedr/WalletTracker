package com.example.wallettrackers.util

import android.content.Context
import android.util.Log
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.repository.FirebaseRepository
import kotlinx.coroutines.flow.first
import java.util.Calendar

object BudgetAlertHelper {

    private const val TAG = "BudgetAlertHelper"

    /**
     * Checks if a newly tracked expense pushes any budget category past
     * the 75% or 100% threshold and sends a push notification if so.
     * Safe to call from SmsReceiver (background context).
     */
    suspend fun checkBudgetAfterTransaction(
        context: Context,
        repository: FirebaseRepository,
        category: String,
        amount: Double
    ) {
        if (amount <= 0) return
        val budgets = repository.getBudgets().first()
        if (budgets.isEmpty()) return

        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)

        // Build subcategory map so BudgetCalculator can include child categories
        val subcategoryMap = Categories.list.associate { cat ->
            cat.name to cat.subCategories.map { it.name }
        }

        val records = repository.getRecords().first()

        for (budget in budgets) {
            // Only check the budget that matches this transaction's category (or parent)
            val isMatch = budget.category == category ||
                subcategoryMap[budget.category]?.contains(category) == true
            if (!isMatch) continue

            val spent = BudgetCalculator.spentInMonth(records, budget.category, month, year, subcategoryMap)
            val pct = if (budget.monthlyLimit > 0) spent / budget.monthlyLimit else 0.0

            // Compute what spending was BEFORE this transaction
            val spentBefore = spent - amount
            val pctBefore = if (budget.monthlyLimit > 0) spentBefore / budget.monthlyLimit else 0.0

            // Only alert if this transaction crossed a threshold
            if (pct >= 1.0 && pctBefore < 1.0) {
                Log.d(TAG, "Budget EXCEEDED: ${budget.category} spent=$spent limit=${budget.monthlyLimit}")
                NotificationHelper.sendBudgetAlert(context, budget.category, spent, budget.monthlyLimit, budget.currency)
            } else if (pct >= 0.75 && pctBefore < 0.75) {
                Log.d(TAG, "Budget WARNING: ${budget.category} spent=$spent limit=${budget.monthlyLimit} (${(pct * 100).toInt()}%)")
                NotificationHelper.sendBudgetAlert(context, budget.category, spent, budget.monthlyLimit, budget.currency)
            }
        }
    }
}
