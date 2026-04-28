package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId
import java.util.Date

data class Debt(
    @DocumentId val id: String = "",
    val personName: String = "",
    val amount: Double = 0.0,
    val currency: String = "EGP",
    val isOwedToMe: Boolean = true,
    val description: String = "",
    val dueDate: Date? = null,
    val isSettled: Boolean = false,
    val userId: String = ""
)
