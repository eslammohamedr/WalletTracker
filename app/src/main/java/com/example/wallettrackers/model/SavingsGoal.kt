package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId
import java.util.Date

data class SavingsGoal(
    @DocumentId val id: String = "",
    val name: String = "",
    val targetAmount: Double = 0.0,
    val savedAmount: Double = 0.0,
    val currency: String = "EGP",
    val targetDate: Date? = null,
    val emoji: String = "🎯",
    val userId: String = ""
)
