package com.example.wallettrackers.repository

import android.util.Log
import com.example.wallettrackers.model.Account
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseRepository(private val userId: String) {

    private val db = Firebase.firestore
    private val accountsCollection = db.collection("users").document(userId).collection("accounts")

    suspend fun addAccount(account: Account) {
        try {
            accountsCollection.add(account).await()
            Log.d("FirebaseRepository", "Account added successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding account", e)
        }
    }

    fun getAccounts(): Flow<List<Account>> = callbackFlow {
        val subscription = accountsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("FirebaseRepository", "Error fetching accounts", error)
                close(error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val accounts = snapshot.toObjects(Account::class.java)
                trySend(accounts).isSuccess
            }
        }

        awaitClose { subscription.remove() }
    }
}
