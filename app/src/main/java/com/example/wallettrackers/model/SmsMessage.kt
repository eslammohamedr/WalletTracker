package com.example.wallettrackers.model

import java.util.Date

data class SmsMessage(
    val id: String = "",
    val body: String = "",
    val sender: String = "",
    val timestamp: Date = Date(),
    val isBankRelated: Boolean = false,
    val hasRecordAdded: Boolean = false,
    val linkedRecord: Record? = null,
    val linkedStatement: CreditStatement? = null,
    val missingInfoReason: String? = null,
    val extractedAmount: String? = null,
    val last4Digits: String? = null,
    val extractedCategory: String? = null,
    val extractedType: String? = null, // "Income", "Expense", "Statement", "CardPayment", "AtmWithdrawal"
    val extractedComment: String? = null,
    val extractedDueDate: String? = null
)
