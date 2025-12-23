package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId

data class Account(
    @DocumentId val id: String = "",
    val name: String = "",
    val amount: String = "",
    val color: Long = 0L, // Store color as a Long (ARGB)
    val userId: String = ""
)
