package com.example.wallettrackers.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class Record(
    @DocumentId val id: String = "",
    val accountId: String = "",
    val accountName: String = "",
    val category: String = "",
    val amount: String = "",
    val currency: String = "",
    val color: Long = 0L,
    @ServerTimestamp val timestamp: Date = Date(),
    val userId: String = ""
)
