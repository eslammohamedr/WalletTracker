package com.example.wallettrackers.util

import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import java.util.Locale

data class CompletedPaymentReconciliation(
    val record: Record,
    val cardBalanceAdjustment: Double
)

object CreditPaymentLinker {
    fun findCreditAccount(
        accounts: List<Account>,
        pendingCreditDigits: String,
        smsCreditDigits: String,
        partialRecordAccountId: String
    ): Account? {
        val partialAccount = accounts.find { it.id == partialRecordAccountId }
            ?.takeIf { it.accountType.contains("Credit", ignoreCase = true) }
        val pendingDigits = pendingCreditDigits.filter(Char::isDigit)
        if (partialAccount != null && digitsMatch(partialAccount.last4Digits, pendingDigits)) {
            return partialAccount
        }
        findByDigits(accounts, pendingDigits)?.let { return it }
        return findByDigits(accounts, smsCreditDigits)
    }

    fun completePartialRecord(partialRecord: Record, source: Account, credit: Account): Record =
        partialRecord.copy(
            accountId = source.id,
            accountName = "${source.name} -> ${credit.name}",
            currency = source.currency,
            transferDestinationAmount = partialRecord.transferDestinationAmount.ifBlank {
                partialRecord.amount.toDoubleOrNull()?.let { String.format(Locale.US, "%.2f", it) }.orEmpty()
            }
        )

    fun reconcileCompletedPayment(
        record: Record,
        creditedAmount: Double,
        cardBalanceAlreadyPrinted: Boolean = false
    ): CompletedPaymentReconciliation {
        val previousDestinationAmount = record.transferDestinationAmount.toDoubleOrNull()
        val sourceDebitAmount = record.amount.toDoubleOrNull() ?: creditedAmount
        val cardBalanceAdjustment = if (previousDestinationAmount == null && !cardBalanceAlreadyPrinted) {
            creditedAmount - sourceDebitAmount
        } else {
            0.0
        }
        val destinationAmount = if (cardBalanceAlreadyPrinted) {
            creditedAmount
        } else {
            previousDestinationAmount ?: creditedAmount
        }
        return CompletedPaymentReconciliation(
            record = record.copy(transferDestinationAmount = String.format(Locale.US, "%.2f", destinationAmount)),
            cardBalanceAdjustment = cardBalanceAdjustment
        )
    }

    private fun findByDigits(accounts: List<Account>, digits: String): Account? {
        val normalizedDigits = digits.filter(Char::isDigit)
        if (normalizedDigits.isEmpty()) return null
        return accounts.filter { account ->
            account.accountType.contains("Credit", ignoreCase = true) &&
                digitsMatch(account.last4Digits, normalizedDigits)
        }.singleOrNull()
    }

    private fun digitsMatch(accountDigits: String, requestedDigits: String): Boolean {
        val normalizedAccountDigits = accountDigits.filter(Char::isDigit)
        val normalizedRequestedDigits = requestedDigits.filter(Char::isDigit)
        return normalizedAccountDigits.isNotEmpty() && normalizedRequestedDigits.isNotEmpty() &&
            (normalizedAccountDigits == normalizedRequestedDigits ||
                normalizedRequestedDigits.endsWith(normalizedAccountDigits) ||
                normalizedAccountDigits.endsWith(normalizedRequestedDigits))
    }
}
