package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId

data class Account(
    @DocumentId val id: String = "",
    val name: String = "",
    val accountType: String = "", // e.g., "Cash", "Bank", "Credit Card"
    val last4Digits: String = "",
    val amount: String = "0", // Current Balance or Available Credit
    val currency: String = "EGP",
    val color: Long = 0L,
    val userId: String = "",
    val creditLimit: Double? = null, // Total limit if it's a credit card
    val billingDay: Int? = null      // Day of month the statement is issued
)
