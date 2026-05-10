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

    data class InstallmentSeries(
        val label: String,
        val monthlyAmount: Double,
        val currency: String,
        val paid: Int,
        val total: Int
    ) {
        val remaining get() = total - paid
        val progressFraction get() = if (total > 0) paid.toFloat() / total else 0f
    }

    fun detectInstallments(records: List<Record>): List<InstallmentSeries> {
        val pattern = Regex(
            """(?:قسط|installment|instalment)\s*(?:رقم\s*)?(\d+)\s*(?:من|of|/)\s*(\d+)""",
            RegexOption.IGNORE_CASE
        )
        data class Key(val accountId: String, val bucket: Long, val currency: String)
        return records
            .mapNotNull { r ->
                val match = pattern.find("${r.comment} ${r.category}") ?: return@mapNotNull null
                val paid  = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val total = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (total <= 0 || paid > total) return@mapNotNull null
                val amt = r.amount.toDoubleOrNull() ?: 0.0
                Key(r.accountId, (amt / 50).toLong(), r.currency) to Triple(r, paid, total)
            }
            .groupBy { it.first }
            .mapNotNull { (_, entries) ->
                val (_, triple) = entries.maxByOrNull { it.second.second } ?: return@mapNotNull null
                val (r, paid, total) = triple
                InstallmentSeries(
                    label = r.comment.take(35).ifBlank { r.category },
                    monthlyAmount = r.amount.toDoubleOrNull() ?: 0.0,
                    currency = r.currency,
                    paid = paid, total = total
                )
            }
            .filter { it.remaining > 0 }
            .sortedByDescending { it.monthlyAmount }
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
