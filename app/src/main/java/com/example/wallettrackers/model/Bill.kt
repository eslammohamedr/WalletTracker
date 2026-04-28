package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId

data class Bill(
    @DocumentId val id: String = "",
    val name: String = "",
    val amount: Double = 0.0,
    val currency: String = "EGP",
    val dayOfMonth: Int = 1,
    val category: String = "Housing",
    val isActive: Boolean = true,
    val userId: String = ""
)
