package com.example.wallettrackers.util

import com.example.wallettrackers.model.Record
import java.util.Locale

object RecordBalanceRollback {
    fun restoredBalance(currentBalance: String, record: Record, accountCurrency: String? = null): String {
        val current = currentBalance.toDoubleOrNull() ?: 0.0
        val amount = record.amount.toDoubleOrNull() ?: 0.0
        val balanceAfter = record.balanceAfter.toDoubleOrNull()
        val balanceBefore = record.balanceBefore.toDoubleOrNull()
        val restored = if (balanceBefore != null && balanceAfter != null &&
            String.format(Locale.US, "%.2f", current) == String.format(Locale.US, "%.2f", balanceAfter)
        ) {
            balanceBefore
        } else if (balanceBefore == null && balanceAfter == null &&
            !record.currency.isNullOrBlank() && !accountCurrency.isNullOrBlank() &&
            !record.currency.equals(accountCurrency, ignoreCase = true)
        ) {
            current
        } else if (record.category == "Credit Payment" && !record.accountName.contains("->")) {
            current - amount
        } else if (record.type == "Income") {
            current - amount
        } else {
            current + amount
        }
        return String.format(Locale.US, "%.2f", restored)
    }
}
