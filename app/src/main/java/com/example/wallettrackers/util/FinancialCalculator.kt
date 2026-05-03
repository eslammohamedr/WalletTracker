package com.example.wallettrackers.util

import com.example.wallettrackers.model.Record
import java.text.SimpleDateFormat
import java.util.Locale

object FinancialCalculator {

    fun exportToCsvString(recordList: List<Record>): String {
        val sb = StringBuilder()
        sb.appendLine("Date,Account,Category,Type,Amount,Currency,Comment,Balance After")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH)
        recordList.forEach { r ->
            sb.appendLine(
                "${fmt.format(r.timestamp)},\"${r.accountName}\",\"${r.category}\"," +
                "${r.type},${r.amount},${r.currency},\"${r.comment}\",${r.balanceAfter}"
            )
        }
        return sb.toString()
    }

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

    fun normaliseCurrency(currency: String): String = when {
        currency.contains("Dollar", ignoreCase = true) || currency.equals("USD", ignoreCase = true) -> "USD"
        currency.contains("Euro",   ignoreCase = true) || currency.equals("EUR", ignoreCase = true) -> "EUR"
        currency.contains("Pound",  ignoreCase = true) || currency.equals("GBP", ignoreCase = true) -> "GBP"
        currency.equals("SAR", ignoreCase = true) -> "SAR"
        currency.equals("AED", ignoreCase = true) -> "AED"
        else -> "EGP"
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
