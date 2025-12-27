package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId

data class Account(
    @DocumentId val id: String = "",
    val name: String = "",
    val accountType: String = "",
    val last4Digits: String = "",
    val amount: String = "",
    val currency: String = "",
    val color: Long = 0L,
    val userId: String = ""
)
