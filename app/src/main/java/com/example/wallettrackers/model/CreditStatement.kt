package com.example.wallettrackers.model

import java.util.Date

data class CreditStatement(
    val id: String = "",
    val cardLast4Digits: String = "",
    val totalAmount: Double = 0.0,
    val dueDate: Date = Date(),
    val isPaid: Boolean = false,
    val userId: String = "",
    val smsId: String = ""
)
