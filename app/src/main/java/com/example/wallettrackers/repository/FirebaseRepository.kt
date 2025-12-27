package com.example.wallettrackers.repository

import android.util.Log
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseRepository(private val userId: String) {

    private val db = Firebase.firestore
    private val userDocument = db.collection("users").document(userId)
    private val accountsCollection = userDocument.collection("accounts")
    private val recordsCollection = userDocument.collection("records")

    suspend fun addAccount(account: Account) {
        try {
            accountsCollection.add(account).await()
            Log.d("FirebaseRepository", "Account added successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding account", e)
        }
    }

    suspend fun updateAccount(account: Account) {
        try {
            accountsCollection.document(account.id).set(account).await()
            Log.d("FirebaseRepository", "Account updated successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating account", e)
        }
    }

    suspend fun deleteAccount(accountId: String) {
        try {
            accountsCollection.document(accountId).delete().await()
            Log.d("FirebaseRepository", "Account deleted successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting account", e)
        }
    }

    suspend fun deleteAllUserData() {
        try {
            userDocument.delete().await()
            Log.d("FirebaseRepository", "User data deleted successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting user data", e)
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
                val accounts = snapshot.documents.mapNotNull { doc ->
                    val account = doc.toObject(Account::class.java)
                    account?.copy(id = doc.id)
                }
                trySend(accounts).isSuccess
            }
        }

        awaitClose { subscription.remove() }
    }

    suspend fun addRecord(record: Record) {
        try {
            recordsCollection.add(record).await()
            Log.d("FirebaseRepository", "Record added successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding record", e)
        }
    }

    suspend fun updateRecord(record: Record) {
        try {
            recordsCollection.document(record.id).set(record).await()
            Log.d("FirebaseRepository", "Record updated successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating record", e)
        }
    }

    suspend fun deleteRecord(recordId: String) {
        try {
            recordsCollection.document(recordId).delete().await()
            Log.d("FirebaseRepository", "Record deleted successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting record", e)
        }
    }

    fun getRecords(): Flow<List<Record>> = callbackFlow {
        val subscription = recordsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("FirebaseRepository", "Error fetching records", error)
                close(error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val records = snapshot.documents.mapNotNull { doc ->
                    val record = doc.toObject(Record::class.java)
                    record?.copy(id = doc.id)
                }
                trySend(records).isSuccess
            }
        }

        awaitClose { subscription.remove() }
    }
}
