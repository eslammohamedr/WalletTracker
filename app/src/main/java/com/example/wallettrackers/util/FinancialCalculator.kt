package com.example.wallettrackers.util

import com.example.wallettrackers.model.Record

object FinancialCalculator {

    fun parseAmount(amountStr: String): Double {
        val cleanStr = amountStr.replace(Regex("[^0-9.\\-]"), "")
        return cleanStr.toDoubleOrNull() ?: 0.0
    }

    fun getCurrencyType(currency: String, accountName: String): String {
        val c = currency.uppercase()
        val n = accountName.uppercase()
        return when {
            c.contains("USD") || c.contains("DOLLAR") || c.contains("$") ||
            n.contains("USD") || n.contains("DOLLAR") -> "USD"
            c.contains("EUR") || c.contains("EURO") || c.contains("€") ||
            n.contains("EUR") || n.contains("EURO") -> "EUR"
            else -> "EGP"
        }
    }

    fun convertToEGP(
        amount: Double,
        currency: String,
        accountName: String,
        usdRate: Double,
        eurRate: Double
    ): Double = when (getCurrencyType(currency, accountName)) {
        "USD" -> amount * usdRate
        "EUR" -> amount * eurRate
        else  -> amount
    }

    fun isExcludedFromSpending(record: Record): Boolean {
        val category = record.category.lowercase()
        val comment  = record.comment.lowercase()
        val account  = record.accountName.lowercase()
        return record.type == "Income" ||
               category == "credit" ||
               category == "credit payment" ||
               comment.contains("atm withdrawal") ||
               account.contains("->") ||
               (category == "instapay outcome" && comment.contains("credit"))
    }
}
