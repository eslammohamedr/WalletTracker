package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId
import java.util.Date

data class Record(
    @DocumentId val id: String = "",
    val accountId: String = "",
    val accountName: String = "",
    val category: String = "",
    val amount: String = "",
    val currency: String = "",
    val type: String = "Expense", // "Income" or "Expense"
    val timestamp: Date = Date(), // Removed @ServerTimestamp to preserve SMS date
    val userId: String = "",
    val balanceAfter: String = "",
    val smsId: String? = null,
    val comment: String = ""
)
