package com.example.wallettrackers.util

import android.util.Log
import com.example.wallettrackers.model.Record
import java.util.Calendar

object BudgetCalculator {

    private const val TAG = "BudgetCalc"

    fun spentInMonth(
        records: List<Record>,
        category: String,
        month: Int,
        year: Int,
        subcategoryMap: Map<String, List<String>>,
        currency: String = "EGP"
    ): Double {
        val subcategories = subcategoryMap[category] ?: emptyList()
        val normalizedCurrency = FinancialCalculator.normaliseCurrency(currency)
        Log.d(TAG, "spentInMonth START: category='$category' month=$month year=$year currency=$normalizedCurrency subcategories=${subcategories.size} totalRecords=${records.size}")
        val matchingRecords = records.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == month &&
            rc.get(Calendar.YEAR) == year &&
            r.type == "Expense" &&
            currencyMatches(r.currency, normalizedCurrency) &&
            !FinancialCalculator.isExcludedFromSpending(r) &&
            (r.category == category || r.category in subcategories)
        }
        val total = matchingRecords.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        Log.d(TAG, "spentInMonth END: category='$category' currency=$normalizedCurrency matchingRecords=${matchingRecords.size} total=$total")
        return total
    }

    fun currencyMatches(recordCurrency: String, budgetCurrency: String): Boolean =
        FinancialCalculator.normaliseCurrency(recordCurrency) ==
            FinancialCalculator.normaliseCurrency(budgetCurrency)
}
