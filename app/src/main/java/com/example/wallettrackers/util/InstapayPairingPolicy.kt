package com.example.wallettrackers.util

object InstapayPairingPolicy {
    fun canPair(
        pendingAccountId: String,
        pendingCurrency: String,
        currentAccountId: String,
        currentCurrency: String
    ): Boolean = pendingAccountId.isNotBlank() &&
        currentAccountId.isNotBlank() &&
        pendingAccountId != currentAccountId &&
        FinancialCalculator.normaliseCurrency(pendingCurrency) ==
            FinancialCalculator.normaliseCurrency(currentCurrency)
}
