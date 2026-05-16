package com.example.wallettrackers.repository

import android.net.Uri
import android.util.Log
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.AppNotification
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.CategoryRule
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.CustomSubCategory
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SavingsGoal
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class FirebaseRepository(private val userId: String) : WalletRepository {

    private val db = Firebase.firestore
    private val userDocument = db.collection("users").document(userId)
    private val accountsCollection = userDocument.collection("accounts")
    private val recordsCollection = userDocument.collection("records")
    private val creditStatementsCollection = userDocument.collection("creditStatements")
    private val budgetsCollection = userDocument.collection("budgets")
    private val savingsGoalsCollection = userDocument.collection("savingsGoals")
    private val debtsCollection = userDocument.collection("debts")
    private val billsCollection = userDocument.collection("bills")
    private val categoryRulesCollection = userDocument.collection("categoryRules")
    private val customSubCategoriesCollection = userDocument.collection("customSubCategories")
    private val notificationsCollection = userDocument.collection("notifications")

    override suspend fun addAccount(account: Account) {
        Log.d("Repo", "addAccount START: name=${account.name}, type=${account.accountType}, userId=${account.userId}")
        try {
            accountsCollection.add(account).await()
            Log.d("Repo", "addAccount END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding account", e)
        }
    }

    /** Adds a new account and returns its Firestore document ID, or null on failure. */
    override suspend fun addAccountAndGetId(account: Account): String? {
        Log.d("Repo", "addAccountAndGetId START: name=${account.name}, type=${account.accountType}")
        return try {
            val id = accountsCollection.add(account).await().id
            Log.d("Repo", "addAccountAndGetId END: success, id=$id")
            id
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding account", e)
            null
        }
    }

    override suspend fun updateAccount(account: Account) {
        Log.d("Repo", "updateAccount START: id=${account.id}, name=${account.name}, amount=${account.amount}")
        try {
            accountsCollection.document(account.id).set(account).await()
            Log.d("Repo", "updateAccount END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating account", e)
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        Log.d("Repo", "deleteAccount START: accountId=$accountId")
        try {
            accountsCollection.document(accountId).delete().await()
            Log.d("Repo", "deleteAccount END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting account", e)
        }
    }

    override suspend fun deleteAllUserData() {
        Log.d("Repo", "deleteAllUserData START: userId=$userId")
        try {
            userDocument.delete().await()
            Log.d("Repo", "deleteAllUserData END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting user data", e)
        }
    }

    override fun getAccounts(): Flow<List<Account>> = callbackFlow {
        Log.d("Repo", "getAccounts: registering snapshot listener")
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
                Log.d("Repo", "getAccounts: snapshot received with ${accounts.size} accounts")
                trySend(accounts).isSuccess
            }
        }

        awaitClose { subscription.remove() }
    }

    override suspend fun addRecord(record: Record) {
        Log.d("Repo", "addRecord START: category=${record.category}, amount=${record.amount}, account=${record.accountName}")
        try {
            recordsCollection.add(record).await()
            Log.d("Repo", "addRecord END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding record", e)
        }
    }

    override suspend fun updateRecord(record: Record) {
        Log.d("Repo", "updateRecord START: id=${record.id}, category=${record.category}, amount=${record.amount}")
        try {
            recordsCollection.document(record.id).set(record).await()
            Log.d("Repo", "updateRecord END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating record", e)
        }
    }

    override suspend fun deleteRecord(recordId: String) {
        Log.d("Repo", "deleteRecord START: recordId=$recordId")
        try {
            recordsCollection.document(recordId).delete().await()
            Log.d("Repo", "deleteRecord END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting record", e)
        }
    }

    /** Atomically creates a new record and updates an account balance. */
    override suspend fun batchAddRecordAndUpdateAccount(account: Account, record: Record) {
        Log.d("Repo", "batchAddRecordAndUpdateAccount START: account=${account.name}, record.category=${record.category}, record.amount=${record.amount}")
        val batch = db.batch()
        val newRecordRef = recordsCollection.document()
        batch.set(accountsCollection.document(account.id), account)
        batch.set(newRecordRef, record)
        batch.commit().await()
        Log.d("Repo", "batchAddRecordAndUpdateAccount END: success")
    }

    /** Atomically updates an existing record and its linked account balance. */
    override suspend fun batchUpdateAccountAndRecord(account: Account, record: Record) {
        Log.d("Repo", "batchUpdateAccountAndRecord START: account=${account.name}, record.id=${record.id}, record.amount=${record.amount}")
        val batch = db.batch()
        batch.set(accountsCollection.document(account.id), account)
        batch.set(recordsCollection.document(record.id), record)
        batch.commit().await()
        Log.d("Repo", "batchUpdateAccountAndRecord END: success")
    }

    /** Atomically updates two accounts and an existing record (e.g. account-change on edit). */
    override suspend fun batchUpdateTwoAccountsAndRecord(account1: Account, account2: Account, record: Record) {
        Log.d("Repo", "batchUpdateTwoAccountsAndRecord START: account1=${account1.name}, account2=${account2.name}, record.id=${record.id}")
        val batch = db.batch()
        batch.set(accountsCollection.document(account1.id), account1)
        batch.set(accountsCollection.document(account2.id), account2)
        batch.set(recordsCollection.document(record.id), record)
        batch.commit().await()
        Log.d("Repo", "batchUpdateTwoAccountsAndRecord END: success")
    }

    /** Atomically creates a new record and updates two accounts (e.g. credit card payments). */
    override suspend fun batchUpdateTwoAccountsAndAddRecord(account1: Account, account2: Account, record: Record) {
        Log.d("Repo", "batchUpdateTwoAccountsAndAddRecord START: account1=${account1.name}, account2=${account2.name}, record.category=${record.category}, record.amount=${record.amount}")
        val batch = db.batch()
        val newRecordRef = recordsCollection.document()
        batch.set(accountsCollection.document(account1.id), account1)
        batch.set(accountsCollection.document(account2.id), account2)
        batch.set(newRecordRef, record)
        batch.commit().await()
        Log.d("Repo", "batchUpdateTwoAccountsAndAddRecord END: success")
    }

    /** Atomically restores an account balance and deletes the linked record. */
    override suspend fun batchUpdateAccountAndDeleteRecord(account: Account, recordId: String) {
        Log.d("Repo", "batchUpdateAccountAndDeleteRecord START: account=${account.name}, recordId=$recordId")
        val batch = db.batch()
        batch.set(accountsCollection.document(account.id), account)
        batch.delete(recordsCollection.document(recordId))
        batch.commit().await()
        Log.d("Repo", "batchUpdateAccountAndDeleteRecord END: success")
    }

    /** Atomically restores two account balances and deletes a transfer record. */
    override suspend fun batchUpdateTwoAccountsAndDeleteRecord(account1: Account, account2: Account, recordId: String) {
        Log.d("Repo", "batchUpdateTwoAccountsAndDeleteRecord START: account1=${account1.name}, account2=${account2.name}, recordId=$recordId")
        val batch = db.batch()
        batch.set(accountsCollection.document(account1.id), account1)
        batch.set(accountsCollection.document(account2.id), account2)
        batch.delete(recordsCollection.document(recordId))
        batch.commit().await()
        Log.d("Repo", "batchUpdateTwoAccountsAndDeleteRecord END: success")
    }

    /** Atomically updates any number of accounts and an existing record. */
    override suspend fun batchUpdateMultipleAccountsAndRecord(updatedAccounts: List<Account>, record: Record) {
        Log.d("Repo", "batchUpdateMultipleAccountsAndRecord START: ${updatedAccounts.size} accounts, record.id=${record.id}")
        val batch = db.batch()
        updatedAccounts.forEach { batch.set(accountsCollection.document(it.id), it) }
        batch.set(recordsCollection.document(record.id), record)
        batch.commit().await()
        Log.d("Repo", "batchUpdateMultipleAccountsAndRecord END: success")
    }

    /** Batch-writes updated records in chunks of 500 (Firestore batch limit). */
    override suspend fun batchUpdateRecords(records: List<Record>) {
        Log.d("Repo", "batchUpdateRecords START: processing ${records.size} records in ${records.chunked(500).size} chunks")
        records.chunked(500).forEachIndexed { index, chunk ->
            Log.d("Repo", "batchUpdateRecords: committing chunk ${index + 1} with ${chunk.size} records")
            val batch = db.batch()
            chunk.forEach { batch.set(recordsCollection.document(it.id), it) }
            batch.commit().await()
        }
        Log.d("Repo", "batchUpdateRecords END: success")
    }

    /** Returns true if a record with the given smsId already exists (deduplication). */
    override suspend fun recordWithSmsIdExists(smsId: String): Boolean {
        Log.d("Repo", "recordWithSmsIdExists START: smsId=$smsId")
        return try {
            val exists = !recordsCollection.whereEqualTo("smsId", smsId).limit(1).get().await().isEmpty
            Log.d("Repo", "recordWithSmsIdExists END: exists=$exists")
            exists
        } catch (e: Exception) {
            Log.d("Repo", "recordWithSmsIdExists: exception, returning false")
            false
        }
    }

    /** Returns the record linked to [smsId], or null if not found. No time window — works for historical SMS. */
    override suspend fun findRecordBySmsId(smsId: String): Record? {
        Log.d("Repo", "findRecordBySmsId START: smsId=$smsId")
        return try {
            val result = recordsCollection.whereEqualTo("smsId", smsId).limit(1).get().await()
                .documents.firstOrNull()?.let { it.toObject(Record::class.java)?.copy(id = it.id) }
            Log.d("Repo", "findRecordBySmsId END: found=${result != null}")
            result
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "findRecordBySmsId error", e)
            null
        }
    }

    /** Returns true if a credit statement with the given smsId already exists (deduplication). */
    override suspend fun statementWithSmsIdExists(smsId: String): Boolean {
        Log.d("Repo", "statementWithSmsIdExists START: smsId=$smsId")
        return try {
            val exists = !creditStatementsCollection.whereEqualTo("smsId", smsId).limit(1).get().await().isEmpty
            Log.d("Repo", "statementWithSmsIdExists END: exists=$exists")
            exists
        } catch (e: Exception) {
            Log.d("Repo", "statementWithSmsIdExists: exception, returning false")
            false
        }
    }

    /**
     * Finds a recent "Credit Payment" record with the given amount saved within the last 24 hours.
     * Uses fuzzy amount matching (within 100 EGP or 5%) to handle Instapay fees.
     */
    override suspend fun findRecentCardPaymentRecord(amount: String): Record? {
        Log.d("Repo", "findRecentCardPaymentRecord START: amount=$amount")
        val oneDayAgo = java.util.Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        val amountDouble = amount.toDoubleOrNull() ?: run {
            Log.d("Repo", "findRecentCardPaymentRecord: invalid amount, returning null")
            return null
        }
        return try {
            val snapshot = recordsCollection
                .whereEqualTo("category", "Credit Payment")
                .get()
                .await()
            Log.d("Repo", "findRecentCardPaymentRecord: checking ${snapshot.documents.size} records, looking for amount≈$amountDouble within 24h")
            val result = snapshot.documents
                .mapNotNull { it.toObject(Record::class.java)?.copy(id = it.id) }
                .filter { record ->
                    if (!record.timestamp.after(oneDayAgo)) return@filter false
                    val recAmt = record.amount.toDoubleOrNull() ?: return@filter false
                    val diff = kotlin.math.abs(recAmt - amountDouble)
                    diff <= 100.0 || diff / maxOf(recAmt, amountDouble) <= 0.05
                }
                .firstOrNull()
            Log.d("Repo", "findRecentCardPaymentRecord END: found=${result != null}")
            result
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "findRecentCardPaymentRecord error", e)
            null
        }
    }

    /**
     * Finds a recent Expense record (Instapay or any debit) from a non-credit-card account
     * with an amount close to [amount] (within 100 EGP or 5%). Used to detect the debit side
     * of a CC payment when the debit SMS arrived first and was saved as a regular expense.
     */
    override suspend fun findRecentDebitExpenseRecord(amount: String): Record? {
        Log.d("Repo", "findRecentDebitExpenseRecord START: amount=$amount")
        val oneDayAgo = java.util.Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000)
        val amountDouble = amount.toDoubleOrNull() ?: run {
            Log.d("Repo", "findRecentDebitExpenseRecord: invalid amount, returning null")
            return null
        }
        return try {
            val snapshot = recordsCollection
                .whereEqualTo("type", "Expense")
                .get()
                .await()
            Log.d("Repo", "findRecentDebitExpenseRecord: checking ${snapshot.documents.size} expense records, looking for amount≈$amountDouble within 24h")
            val result = snapshot.documents
                .mapNotNull { it.toObject(Record::class.java)?.copy(id = it.id) }
                .filter { record ->
                    if (!record.timestamp.after(oneDayAgo)) return@filter false
                    if (record.accountName.contains("->")) return@filter false  // already a transfer
                    if (record.category == "Credit Payment") return@filter false  // already handled
                    val recAmt = record.amount.toDoubleOrNull() ?: return@filter false
                    val diff = kotlin.math.abs(recAmt - amountDouble)
                    diff <= 100.0 || diff / maxOf(recAmt, amountDouble) <= 0.05
                }
                .firstOrNull()
            Log.d("Repo", "findRecentDebitExpenseRecord END: found=${result != null}")
            result
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "findRecentDebitExpenseRecord error", e)
            null
        }
    }

    override fun getRecords(): Flow<List<Record>> = callbackFlow {
        Log.d("Repo", "getRecords: registering snapshot listener")
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
                Log.d("Repo", "getRecords: snapshot received with ${records.size} records")
                trySend(records).isSuccess
            }
        }

        awaitClose { subscription.remove() }
    }

    override suspend fun addCreditStatement(statement: CreditStatement) {
        Log.d("Repo", "addCreditStatement START: card=${statement.cardLast4Digits}, totalAmount=${statement.totalAmount}, dueDate=${statement.dueDate}")
        try {
            creditStatementsCollection.add(statement).await()
            Log.d("Repo", "addCreditStatement END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding credit statement", e)
        }
    }

    override fun getCreditStatements(): Flow<List<CreditStatement>> = callbackFlow {
        Log.d("Repo", "getCreditStatements: registering snapshot listener")
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
                Log.d("Repo", "getCreditStatements: snapshot received with ${statements.size} statements")
                trySend(statements).isSuccess
            }
        }

        awaitClose { subscription.remove() }
    }

    override suspend fun deleteCreditStatement(statementId: String) {
        Log.d("Repo", "deleteCreditStatement START: statementId=$statementId")
        try {
            creditStatementsCollection.document(statementId).delete().await()
            Log.d("Repo", "deleteCreditStatement END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting credit statement", e)
        }
    }

    override suspend fun updateCreditStatement(statement: CreditStatement) {
        Log.d("Repo", "updateCreditStatement START: id=${statement.id}, isPaid=${statement.isPaid}, amount=${statement.totalAmount}")
        try {
            creditStatementsCollection.document(statement.id).set(statement).await()
            Log.d("Repo", "updateCreditStatement END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating credit statement", e)
        }
    }

    override suspend fun getUnpaidStatementsOnce(): List<CreditStatement> {
        Log.d("Repo", "getUnpaidStatementsOnce START")
        return try {
            // Fetch all and filter in code — Firestore's whereEqualTo("isPaid", false)
            // does NOT match documents where the field is absent (older documents).
            val result = creditStatementsCollection.get().await()
                .documents.mapNotNull { doc -> doc.toObject(CreditStatement::class.java)?.copy(id = doc.id) }
                .filter { !it.isPaid }
            Log.d("Repo", "getUnpaidStatementsOnce END: found ${result.size} unpaid statements")
            result
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error fetching unpaid statements", e)
            emptyList()
        }
    }

    override suspend fun markStatementAsPaidById(statementId: String) {
        Log.d("Repo", "markStatementAsPaidById START: statementId=$statementId")
        try {
            creditStatementsCollection.document(statementId).update("isPaid", true).await()
            Log.d("Repo", "markStatementAsPaidById END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error marking statement paid: $statementId", e)
        }
    }

    override suspend fun addBudget(budget: Budget) {
        Log.d("Repo", "addBudget START: category=${budget.category}, limit=${budget.monthlyLimit}")
        try {
            budgetsCollection.add(budget).await()
            Log.d("Repo", "addBudget END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error adding budget", e)
        }
    }

    override suspend fun updateBudget(budget: Budget) {
        Log.d("Repo", "updateBudget START: id=${budget.id}, category=${budget.category}, limit=${budget.monthlyLimit}")
        try {
            budgetsCollection.document(budget.id).set(budget).await()
            Log.d("Repo", "updateBudget END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error updating budget", e)
        }
    }

    override suspend fun deleteBudget(budgetId: String) {
        Log.d("Repo", "deleteBudget START: budgetId=$budgetId")
        try {
            budgetsCollection.document(budgetId).delete().await()
            Log.d("Repo", "deleteBudget END: success")
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error deleting budget", e)
        }
    }

    override fun getBudgets(): Flow<List<Budget>> = callbackFlow {
        Log.d("Repo", "getBudgets: registering snapshot listener")
        val subscription = budgetsCollection.addSnapshotListener { snapshot, error ->
            if (error != null) { close(error); return@addSnapshotListener }
            if (snapshot != null) {
                val budgets = snapshot.documents.mapNotNull { it.toObject(Budget::class.java)?.copy(id = it.id) }
                Log.d("Repo", "getBudgets: snapshot received with ${budgets.size} budgets")
                trySend(budgets).isSuccess
            }
        }
        awaitClose { subscription.remove() }
    }

    // Savings Goals
    override suspend fun addSavingsGoal(goal: SavingsGoal) { try { savingsGoalsCollection.add(goal).await() } catch (e: Exception) { Log.e("Repo", "addSavingsGoal", e) } }
    override suspend fun updateSavingsGoal(goal: SavingsGoal) { try { savingsGoalsCollection.document(goal.id).set(goal).await() } catch (e: Exception) { Log.e("Repo", "updateSavingsGoal", e) } }
    override suspend fun deleteSavingsGoal(id: String) { try { savingsGoalsCollection.document(id).delete().await() } catch (e: Exception) { Log.e("Repo", "deleteSavingsGoal", e) } }
    override fun getSavingsGoals(): Flow<List<SavingsGoal>> = callbackFlow {
        val sub = savingsGoalsCollection.addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.documents?.mapNotNull { it.toObject(SavingsGoal::class.java)?.copy(id = it.id) } ?: emptyList()).isSuccess
        }
        awaitClose { sub.remove() }
    }

    // Debts
    override suspend fun addDebt(debt: Debt) { try { debtsCollection.add(debt).await() } catch (e: Exception) { Log.e("Repo", "addDebt", e) } }
    override suspend fun updateDebt(debt: Debt) { try { debtsCollection.document(debt.id).set(debt).await() } catch (e: Exception) { Log.e("Repo", "updateDebt", e) } }
    override suspend fun deleteDebt(id: String) { try { debtsCollection.document(id).delete().await() } catch (e: Exception) { Log.e("Repo", "deleteDebt", e) } }
    override fun getDebts(): Flow<List<Debt>> = callbackFlow {
        val sub = debtsCollection.addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.documents?.mapNotNull { it.toObject(Debt::class.java)?.copy(id = it.id) } ?: emptyList()).isSuccess
        }
        awaitClose { sub.remove() }
    }

    // Bills
    override suspend fun addBill(bill: Bill) { try { billsCollection.add(bill).await() } catch (e: Exception) { Log.e("Repo", "addBill", e) } }
    override suspend fun updateBill(bill: Bill) { try { billsCollection.document(bill.id).set(bill).await() } catch (e: Exception) { Log.e("Repo", "updateBill", e) } }
    override suspend fun deleteBill(id: String) { try { billsCollection.document(id).delete().await() } catch (e: Exception) { Log.e("Repo", "deleteBill", e) } }
    override fun getBills(): Flow<List<Bill>> = callbackFlow {
        val sub = billsCollection.addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.documents?.mapNotNull { it.toObject(Bill::class.java)?.copy(id = it.id) } ?: emptyList()).isSuccess
        }
        awaitClose { sub.remove() }
    }

    // Receipt Photos
    override suspend fun uploadReceiptPhoto(userId: String, recordId: String, uri: Uri): String? {
        return try {
            val ref = Firebase.storage.reference.child("receipts/$userId/$recordId.jpg")
            ref.putFile(uri).await()
            ref.downloadUrl.await().toString()
        } catch (e: Exception) {
            Log.e("FirebaseRepository", "Error uploading receipt", e)
            null
        }
    }

    // Category Rules
    override suspend fun addCategoryRule(rule: CategoryRule): String? {
        return try {
            categoryRulesCollection.add(rule).await().id
        } catch (e: Exception) {
            Log.e("Repo", "addCategoryRule", e)
            null
        }
    }

    override suspend fun deleteCategoryRule(ruleId: String) {
        try { categoryRulesCollection.document(ruleId).delete().await() }
        catch (e: Exception) { Log.e("Repo", "deleteCategoryRule", e) }
    }

    override fun getCategoryRules(): Flow<List<CategoryRule>> = callbackFlow {
        val sub = categoryRulesCollection.addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.documents?.mapNotNull { it.toObject(CategoryRule::class.java)?.copy(id = it.id) } ?: emptyList()).isSuccess
        }
        awaitClose { sub.remove() }
    }

    override suspend fun addCustomSubCategory(sub: CustomSubCategory): String? {
        return try {
            val ref = customSubCategoriesCollection.add(sub).await()
            customSubCategoriesCollection.document(ref.id).update("id", ref.id).await()
            ref.id
        } catch (e: Exception) { Log.e("Repo", "addCustomSubCategory", e); null }
    }

    override suspend fun deleteCustomSubCategory(id: String) {
        try { customSubCategoriesCollection.document(id).delete().await() }
        catch (e: Exception) { Log.e("Repo", "deleteCustomSubCategory", e) }
    }

    override fun getCustomSubCategories(): Flow<List<CustomSubCategory>> = callbackFlow {
        val sub = customSubCategoriesCollection.addSnapshotListener { snap, err ->
            if (err != null) { close(err); return@addSnapshotListener }
            trySend(snap?.documents?.mapNotNull { it.toObject(CustomSubCategory::class.java) } ?: emptyList()).isSuccess
        }
        awaitClose { sub.remove() }
    }

    override suspend fun addNotification(notification: AppNotification) {
        try {
            val ref = notificationsCollection.add(notification).await()
            notificationsCollection.document(ref.id).update("id", ref.id).await()
        } catch (e: Exception) { Log.e("Repo", "addNotification", e) }
    }

    override fun getNotifications(): Flow<List<AppNotification>> = callbackFlow {
        val sub = notificationsCollection.orderBy("timestamp", com.google.firebase.firestore.Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, err ->
                if (err != null) { close(err); return@addSnapshotListener }
                trySend(snap?.documents?.mapNotNull { it.toObject(AppNotification::class.java) } ?: emptyList()).isSuccess
            }
        awaitClose { sub.remove() }
    }

    override suspend fun clearNotifications() {
        try {
            val docs = notificationsCollection.get().await()
            docs.documents.forEach { it.reference.delete() }
        } catch (e: Exception) { Log.e("Repo", "clearNotifications", e) }
    }
}
