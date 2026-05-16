package com.example.wallettrackers.model

import java.util.Date

data class AppNotification(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val timestamp: Date = Date(),
    val type: String = "" // "budget_alert", "bill_reminder", "unusual_transaction"
)
