package com.example.wallettrackers.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "records")
data class RecordEntity(
    @PrimaryKey val id: String,
    val accountId: String = "",
    val accountName: String = "",
    val category: String = "",
    val amount: String = "",
    val currency: String = "",
    val type: String = "Expense",
    val timestamp: Long = System.currentTimeMillis(),
    val userId: String = "",
    val balanceAfter: String = "",
    val smsId: String? = null,
    val comment: String = "",
    val receiptUrl: String = ""
)
