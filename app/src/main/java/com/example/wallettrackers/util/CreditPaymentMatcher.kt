package com.example.wallettrackers.util

import com.example.wallettrackers.model.Record

object CreditPaymentMatcher {
    private const val MAX_FEE_AMOUNT = 5.0
    private const val MAX_FEE_PERCENT = 0.02
    const val MAX_DEBIT_PAIR_WINDOW_MILLIS = 10 * 60_000L

    fun amountsMatch(first: Double, second: Double): Boolean {
        if (!first.isFinite() || !second.isFinite() || first <= 0.0 || second <= 0.0) return false
        val difference = kotlin.math.abs(first - second)
        return difference <= MAX_FEE_AMOUNT || difference / maxOf(first, second) <= MAX_FEE_PERCENT
    }

    fun amountsMatch(first: String, second: String): Boolean {
        val firstAmount = first.toDoubleOrNull() ?: return false
        val secondAmount = second.toDoubleOrNull() ?: return false
        return amountsMatch(firstAmount, secondAmount)
    }

    data class PendingPayment(
        val key: String,
        val smsId: String,
        val creditDigits: String,
        val amount: String,
        val timestampMillis: Long
    )

    fun selectPendingPayment(
        pending: List<PendingPayment>,
        amount: String,
        destinationCardDigits: String
    ): PendingPayment? {
        val amountMatches = pending.filter { amountsMatch(it.amount, amount) }
        val destinationDigits = destinationCardDigits.filter(Char::isDigit)
        val matchingCard = if (destinationDigits.isEmpty()) {
            amountMatches
        } else {
            amountMatches.filter { cardDigitsMatch(it.creditDigits, destinationDigits) }
        }
        val unambiguousCandidates = if (destinationDigits.isEmpty() && matchingCard.size > 1) {
            emptyList()
        } else {
            matchingCard
        }
        return unambiguousCandidates.minByOrNull { it.timestampMillis }
    }

    fun cardDigitsMatch(first: String, second: String): Boolean {
        val firstDigits = first.filter(Char::isDigit)
        val secondDigits = second.filter(Char::isDigit)
        return firstDigits.isNotEmpty() && secondDigits.isNotEmpty() &&
            (firstDigits == secondDigits || firstDigits.endsWith(secondDigits) || secondDigits.endsWith(firstDigits))
    }

    fun findMatchingDebitExpense(
        records: List<Record>,
        amount: String,
        eventTimestampMillis: Long
    ): Record? {
        val candidates = records.filter { record ->
            record.category == "Instapay outcome" &&
                record.type == "Expense" &&
                !record.accountName.contains("->") &&
                kotlin.math.abs(record.timestamp.time - eventTimestampMillis) <= MAX_DEBIT_PAIR_WINDOW_MILLIS &&
                amountsMatch(record.amount, amount)
        }
        return candidates.singleOrNull()
    }

    fun findCompletedPayment(
        records: List<Record>,
        amount: String,
        creditCardName: String,
        eventTimestampMillis: Long = System.currentTimeMillis()
    ): Record? {
        val candidates = records.filter { record ->
            record.category == "Credit Payment" &&
                record.accountName.contains("->") &&
                record.accountName.substringAfter("->", "").trim().equals(creditCardName, ignoreCase = true) &&
                kotlin.math.abs(record.timestamp.time - eventTimestampMillis) <= MAX_DEBIT_PAIR_WINDOW_MILLIS &&
                amountsMatch(record.amount, amount)
        }
        val exactMatches = candidates.filter { it.amount.toDoubleOrNull() == amount.toDoubleOrNull() }
        return when {
            exactMatches.size == 1 -> exactMatches.single()
            exactMatches.size > 1 -> null
            candidates.size == 1 -> candidates.single()
            else -> null
        }
    }
}
