package com.example.wallettrackers.db

import androidx.room.Entity

@Entity(tableName = "accounts", primaryKeys = ["id", "userId"])
data class AccountEntity(
    val id: String,
    val name: String = "",
    val accountType: String = "",
    val last4Digits: String = "",
    val amount: String = "0",
    val currency: String = "EGP",
    val color: Long = 0L,
    val userId: String = "",
    val creditLimit: Double? = null,
    val billingDay: Int? = null,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0
)
