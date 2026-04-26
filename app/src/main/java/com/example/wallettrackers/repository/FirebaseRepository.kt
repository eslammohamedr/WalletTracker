package com.example.wallettrackers.repository

import android.util.Log
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.CreditStatement
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
    private val creditStatementsCollection = userDocument.collection("creditStatements")

    suspend fun addAccount(account: Account) {
        try {
            accountsCollection.add(account).await()
            Log.d("FirebaseRepository", "Account added successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding account", e)
        }
    }

    /** Adds a new account and returns its Firestore document ID, or null on failure. */
    suspend fun addAccountAndGetId(account: Account): String? {
        return try {
            accountsCollection.add(account).await().id
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding account", e)
            null
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

    /** Atomically creates a new record and updates an account balance. */
    suspend fun batchAddRecordAndUpdateAccount(account: Account, record: Record) {
        val batch = db.batch()
        val newRecordRef = recordsCollection.document()
        batch.set(accountsCollection.document(account.id), account)
        batch.set(newRecordRef, record)
        batch.commit().await()
    }

    /** Atomically updates an existing record and its linked account balance. */
    suspend fun batchUpdateAccountAndRecord(account: Account, record: Record) {
        val batch = db.batch()
        batch.set(accountsCollection.document(account.id), account)
        batch.set(recordsCollection.document(record.id), record)
        batch.commit().await()
    }

    /** Atomically updates two accounts and an existing record (e.g. account-change on edit). */
    suspend fun batchUpdateTwoAccountsAndRecord(account1: Account, account2: Account, record: Record) {
        val batch = db.batch()
        batch.set(accountsCollection.document(account1.id), account1)
        batch.set(accountsCollection.document(account2.id), account2)
        batch.set(recordsCollection.document(record.id), record)
        batch.commit().await()
    }

    /** Atomically creates a new record and updates two accounts (e.g. credit card payments). */
    suspend fun batchUpdateTwoAccountsAndAddRecord(account1: Account, account2: Account, record: Record) {
        val batch = db.batch()
        val newRecordRef = recordsCollection.document()
        batch.set(accountsCollection.document(account1.id), account1)
        batch.set(accountsCollection.document(account2.id), account2)
        batch.set(newRecordRef, record)
        batch.commit().await()
    }

    /** Atomically restores an account balance and deletes the linked record. */
    suspend fun batchUpdateAccountAndDeleteRecord(account: Account, recordId: String) {
        val batch = db.batch()
        batch.set(accountsCollection.document(account.id), account)
        batch.delete(recordsCollection.document(recordId))
        batch.commit().await()
    }

    /** Returns true if a record with the given smsId already exists (deduplication). */
    suspend fun recordWithSmsIdExists(smsId: String): Boolean {
        return try {
            !recordsCollection.whereEqualTo("smsId", smsId).limit(1).get().await().isEmpty
        } catch (e: Exception) {
            false
        }
    }

    /** Returns true if a credit statement with the given smsId already exists (deduplication). */
    suspend fun statementWithSmsIdExists(smsId: String): Boolean {
        return try {
            !creditStatementsCollection.whereEqualTo("smsId", smsId).limit(1).get().await().isEmpty
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Finds a recent "Credit Payment" record with the given amount saved within the last 24 hours.
     * Used to detect when the other side of a dual-SMS credit card payment has already been processed.
     */
    suspend fun findRecentCardPaymentRecord(amount: String): Record? {
        val oneDayAgo = java.util.Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        return try {
            val snapshot = recordsCollection
                .whereEqualTo("category", "Credit Payment")
                .get()
                .await()
            snapshot.documents
                .mapNotNull { it.toObject(Record::class.java)?.copy(id = it.id) }
                .filter { it.timestamp.after(oneDayAgo) && it.amount == amount }
                .firstOrNull()
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "findRecentCardPaymentRecord error", e)
            null
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

    suspend fun addCreditStatement(statement: CreditStatement) {
        try {
            creditStatementsCollection.add(statement).await()
            Log.d("FirebaseRepository", "Credit statement added successfully")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding credit statement", e)
        }
    }

    fun getCreditStatements(): Flow<List<CreditStatement>> = callbackFlow {
        val subscription = creditStatementsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e("FirebaseRepository", "Error fetching credit statements", error)
                close(error)
                return@addSnapshotListener
            }

            if (snapshot != null) {
                val statements = snapshot.documents.mapNotNull { doc ->
                    val statement = doc.toObject(CreditStatement::class.java)
                    statement?.copy(id = doc.id)
                }
                trySend(statements).isSuccess
            }
        }

        awaitClose { subscription.remove() }
    }

    suspend fun deleteCreditStatement(statementId: String) {
        try {
            creditStatementsCollection.document(statementId).delete().await()
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting credit statement", e)
        }
    }

    suspend fun updateCreditStatement(statement: CreditStatement) {
        try {
            creditStatementsCollection.document(statement.id).set(statement).await()
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating credit statement", e)
        }
    }
}
