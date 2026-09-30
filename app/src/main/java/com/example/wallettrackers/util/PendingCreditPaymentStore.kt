package com.example.wallettrackers.util

import android.content.Context
import android.content.SharedPreferences

data class PendingCreditPayment(
    val key: String,
    val smsId: String,
    val creditDigits: String,
    val amount: String,
    val timestampMillis: Long
)

class PendingCreditPaymentStore(
    context: Context,
    private val nowMillis: () -> Long = System::currentTimeMillis
) {
    private val preferences: SharedPreferences =
        context.getSharedPreferences("pending_cc", Context.MODE_PRIVATE)

    fun store(amount: String, creditDigits: String, smsId: String) {
        preferences.edit()
            .putString("cc_pending_$smsId", "$amount|$creditDigits|${nowMillis()}")
            .apply()
    }

    fun peek(amount: String, destinationCardDigits: String): PendingCreditPayment? =
        find(amount, destinationCardDigits, consume = false)

    fun consume(amount: String, destinationCardDigits: String): PendingCreditPayment? =
        find(amount, destinationCardDigits, consume = true)

    fun remove(payment: PendingCreditPayment) {
        preferences.edit().remove(payment.key).apply()
    }

    private fun find(
        amount: String,
        destinationCardDigits: String,
        consume: Boolean
    ): PendingCreditPayment? {
        val now = nowMillis()
        val pending = preferences.all.entries.mapNotNull { entry ->
            if (!entry.key.startsWith("cc_pending_")) return@mapNotNull null
            val keyValue = entry.key.removePrefix("cc_pending_")
            val parts = (entry.value as? String)?.split("|") ?: return@mapNotNull null
            if (parts.size < 3) return@mapNotNull null

            val legacyAmount = keyValue.toDoubleOrNull()
            val payment = if (legacyAmount != null) {
                PendingCreditPayment(
                    key = entry.key,
                    smsId = parts[0],
                    creditDigits = parts[1],
                    amount = legacyAmount.toString(),
                    timestampMillis = parts[2].toLongOrNull() ?: 0L
                )
            } else {
                PendingCreditPayment(
                    key = entry.key,
                    smsId = keyValue,
                    creditDigits = parts[1],
                    amount = parts[0],
                    timestampMillis = parts[2].toLongOrNull() ?: 0L
                )
            }
            if (now - payment.timestampMillis > MAX_AGE_MILLIS) {
                preferences.edit().remove(entry.key).apply()
                return@mapNotNull null
            }
            payment
        }
        val selected = CreditPaymentMatcher.selectPendingPayment(
            pending.map { payment ->
                CreditPaymentMatcher.PendingPayment(
                    key = payment.key,
                    smsId = payment.smsId,
                    creditDigits = payment.creditDigits,
                    amount = payment.amount,
                    timestampMillis = payment.timestampMillis
                )
            },
            amount,
            destinationCardDigits
        ) ?: return null
        if (consume) preferences.edit().remove(selected.key).apply()
        return pending.firstOrNull { it.key == selected.key }
    }

    private companion object {
        const val MAX_AGE_MILLIS = 48 * 60 * 60_000L
    }
}
