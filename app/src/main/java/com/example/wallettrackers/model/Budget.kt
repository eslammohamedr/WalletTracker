package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId

data class Budget(
    @DocumentId val id: String = "",
    val category: String = "",
    val monthlyLimit: Double = 0.0,
    val currency: String = "EGP",
    val userId: String = ""
)
