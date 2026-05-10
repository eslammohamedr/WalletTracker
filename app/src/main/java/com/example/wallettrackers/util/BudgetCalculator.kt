package com.example.wallettrackers.util

import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.FinancialCalculator
import java.util.Calendar

object BudgetCalculator {

    fun spentInMonth(
        records: List<Record>,
        category: String,
        month: Int,
        year: Int,
        subcategoryMap: Map<String, List<String>>
    ): Double {
        val subcategories = subcategoryMap[category] ?: emptyList()
        return records.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == month &&
            rc.get(Calendar.YEAR) == year &&
            r.type == "Expense" &&
            !FinancialCalculator.isExcludedFromSpending(r) &&
            (r.category == category || r.category in subcategories)
        }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    }
}
