package com.example.wallettrackers.repository

import android.net.Uri
import android.util.Log
import com.example.wallettrackers.db.AccountDao
import com.example.wallettrackers.db.RecordDao
import com.example.wallettrackers.db.toEntity
import com.example.wallettrackers.db.toModel
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.AppNotification
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.CategoryRule
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.CustomSubCategory
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.LocalReceiptStore
import com.example.wallettrackers.model.SavingsGoal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Offline-first repository for Records and Accounts.
 *
 * Reads come from Room (instant, works offline).
 * Writes go to Room first, then sync to Firestore in the background.
 * A Firestore snapshot listener keeps Room in sync with remote changes.
 *
 * All other entities (budgets, statements, etc.) delegate directly to FirebaseRepository.
 */
class OfflineFirstRepository(
    private val firebase: FirebaseRepository,
    private val recordDao: RecordDao,
    private val accountDao: AccountDao,
    private val userId: String
) : WalletRepository {

    private val TAG = "OfflineFirstRepo"
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Start Firestore → Room sync listeners
        syncScope.launch { syncRecordsFromFirestore() }
        syncScope.launch { syncAccountsFromFirestore() }
    }

    private suspend fun syncRecordsFromFirestore() {
        try {
            firebase.getRecords().collect { remoteRecords ->
                val localReceipts = recordDao.getAll(userId).first()
                    .filter { LocalReceiptStore.isLocalReceipt(it.receiptUrl) }
                    .associate { it.id to it.receiptUrl }
                recordDao.replaceAllForUser(userId, remoteRecords.map { record ->
                    record.copy(
                        userId = userId,
                        receiptUrl = LocalReceiptStore.receiptUrlAfterSync(
                            record.receiptUrl,
                            localReceipts[record.id].orEmpty()
                        )
                    ).toEntity()
                })
                Log.d(TAG, "Synced ${remoteRecords.size} records from Firestore → Room")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Record sync listener failed", e)
        }
    }

    private suspend fun syncAccountsFromFirestore() {
        try {
            firebase.getAccounts().collect { remoteAccounts ->
                accountDao.replaceAllForUser(userId, remoteAccounts.map { it.copy(userId = userId).toEntity() })
                Log.d(TAG, "Synced ${remoteAccounts.size} accounts from Firestore → Room")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Account sync listener failed", e)
        }
    }

    // ── Accounts (offline-first) ─────────────────────────────────────────

    override suspend fun addAccount(account: Account) {
        val accountWithId = if (account.id.isBlank()) account.copy(id = java.util.UUID.randomUUID().toString()) else account
        accountDao.insert(accountWithId.copy(userId = userId).toEntity()) // Room first
        syncScope.launch {
            try {
                firebase.updateAccount(accountWithId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync account to Firestore", e)
            }
        }
    }

    override suspend fun addAccountAndGetId(account: Account): String? {
        val accountWithId = if (account.id.isBlank()) account.copy(id = java.util.UUID.randomUUID().toString()) else account
        accountDao.insert(accountWithId.copy(userId = userId).toEntity()) // Room first
        syncScope.launch {
            try {
                firebase.updateAccount(accountWithId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync account to Firestore", e)
            }
        }
        return accountWithId.id
    }

    override suspend fun updateAccount(account: Account) {
        accountDao.insert(account.copy(userId = userId).toEntity()) // Room first
        syncScope.launch {
            try {
                firebase.updateAccount(account)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync account update to Firestore", e)
            }
        }
    }

    override suspend fun deleteAccount(accountId: String) {
        accountDao.deleteById(accountId, userId) // Room first
        syncScope.launch {
            try {
                firebase.deleteAccount(accountId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync account delete to Firestore", e)
            }
        }
    }

    override fun getAccounts(): Flow<List<Account>> {
        return accountDao.getAll(userId).map { entities -> entities.map { it.toModel() } }
    }

    // ── Records (offline-first) ──────────────────────────────────────────

    override suspend fun addRecord(record: Record) {
        val recordWithId = if (record.id.isBlank()) record.copy(id = java.util.UUID.randomUUID().toString()) else record
        recordDao.insert(recordWithId.copy(userId = userId).toEntity()) // Room first
        syncScope.launch {
            try {
                firebase.updateRecord(recordWithId.withoutLocalReceipt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync record to Firestore", e)
            }
        }
    }

    override suspend fun updateRecord(record: Record) {
        recordDao.insert(record.copy(userId = userId).toEntity()) // Room first
        syncScope.launch {
            try {
                firebase.updateRecord(record.withoutLocalReceipt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync record update to Firestore", e)
            }
        }
    }

    override suspend fun updateRecordLocally(record: Record) {
        recordDao.insert(record.copy(userId = userId).toEntity())
    }

    override suspend fun deleteRecord(recordId: String) {
        recordDao.deleteById(recordId, userId) // Room first
        syncScope.launch {
            try {
                firebase.deleteRecord(recordId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync record delete to Firestore", e)
            }
        }
    }

    override fun getRecords(): Flow<List<Record>> {
        return recordDao.getAll(userId).map { entities -> entities.map { it.toModel() } }
    }

    private fun Record.withoutLocalReceipt(): Record =
        copy(receiptUrl = LocalReceiptStore.cloudSafeReceiptUrl(receiptUrl))

    override suspend fun recordWithSmsIdExists(smsId: String): Boolean {
        return recordDao.existsBySmsId(smsId, userId)
    }

    override suspend fun findRecordBySmsId(smsId: String): Record? {
        return recordDao.findBySmsId(smsId, userId)?.toModel()
    }

    override suspend fun findRecentCardPaymentRecord(amount: String): Record? {
        // Complex query — delegate to Firestore
        return firebase.findRecentCardPaymentRecord(amount)
    }

    override suspend fun findRecentDebitExpenseRecord(amount: String, eventTimestampMillis: Long): Record? {
        return firebase.findRecentDebitExpenseRecord(amount, eventTimestampMillis)
    }

    // ── Batch operations (write to Room first, sync to Firebase) ────────

    override suspend fun batchAddRecordAndUpdateAccount(account: Account, record: Record) {
        val recordWithId = if (record.id.isBlank()) record.copy(id = java.util.UUID.randomUUID().toString()) else record
        accountDao.insert(account.copy(userId = userId).toEntity())
        recordDao.insert(recordWithId.copy(userId = userId).toEntity())
        syncScope.launch {
            try {
                firebase.batchUpdateAccountAndRecord(account, recordWithId.withoutLocalReceipt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch add record and update account", e)
            }
        }
    }

    override suspend fun batchUpdateAccountAndRecord(account: Account, record: Record) {
        accountDao.insert(account.copy(userId = userId).toEntity())
        recordDao.insert(record.copy(userId = userId).toEntity())
        syncScope.launch {
            try {
                firebase.batchUpdateAccountAndRecord(account, record.withoutLocalReceipt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch update", e)
            }
        }
    }

    override suspend fun batchUpdateTwoAccountsAndRecord(account1: Account, account2: Account, record: Record) {
        accountDao.insert(account1.copy(userId = userId).toEntity())
        accountDao.insert(account2.copy(userId = userId).toEntity())
        recordDao.insert(record.copy(userId = userId).toEntity())
        syncScope.launch {
            try {
                firebase.batchUpdateTwoAccountsAndRecord(account1, account2, record.withoutLocalReceipt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch update", e)
            }
        }
    }

    override suspend fun batchUpdateTwoAccountsAndAddRecord(account1: Account, account2: Account, record: Record) {
        val recordWithId = if (record.id.isBlank()) record.copy(id = java.util.UUID.randomUUID().toString()) else record
        accountDao.insert(account1.copy(userId = userId).toEntity())
        accountDao.insert(account2.copy(userId = userId).toEntity())
        recordDao.insert(recordWithId.copy(userId = userId).toEntity())
        syncScope.launch {
            try {
                firebase.batchUpdateTwoAccountsAndRecord(account1, account2, recordWithId.withoutLocalReceipt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch transfer", e)
            }
        }
    }

    override suspend fun batchUpdateAccountAndDeleteRecord(account: Account, recordId: String) {
        accountDao.insert(account.copy(userId = userId).toEntity())
        recordDao.deleteById(recordId, userId)
        syncScope.launch {
            try {
                firebase.batchUpdateAccountAndDeleteRecord(account, recordId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch delete", e)
            }
        }
    }

    override suspend fun deleteRecordAndRestoreAccount(accountId: String, recordId: String): Account? {
        val updatedAccount = firebase.deleteRecordAndRestoreAccount(accountId, recordId)
        recordDao.deleteById(recordId, userId)
        if (updatedAccount != null) accountDao.insert(updatedAccount.copy(userId = userId).toEntity())
        return updatedAccount
    }

    override suspend fun batchUpdateTwoAccountsAndDeleteRecord(account1: Account, account2: Account, recordId: String) {
        accountDao.insert(account1.copy(userId = userId).toEntity())
        accountDao.insert(account2.copy(userId = userId).toEntity())
        recordDao.deleteById(recordId, userId)
        syncScope.launch {
            try {
                firebase.batchUpdateTwoAccountsAndDeleteRecord(account1, account2, recordId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch delete", e)
            }
        }
    }

    override suspend fun batchUpdateMultipleAccountsAndRecord(updatedAccounts: List<Account>, record: Record) {
        accountDao.insertAll(updatedAccounts.map { it.copy(userId = userId).toEntity() })
        recordDao.insert(record.copy(userId = userId).toEntity())
        syncScope.launch {
            try {
                firebase.batchUpdateMultipleAccountsAndRecord(updatedAccounts, record.withoutLocalReceipt())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch update", e)
            }
        }
    }

    override suspend fun batchUpdateRecords(records: List<Record>) {
        recordDao.insertAll(records.map { it.copy(userId = userId).toEntity() })
        syncScope.launch {
            try {
                firebase.batchUpdateRecords(records.map { it.withoutLocalReceipt() })
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync batch records update", e)
            }
        }
    }

    // ── Pass-through to Firebase (non-MVP entities) ──────────────────────

    override suspend fun addCreditStatement(statement: CreditStatement) = firebase.addCreditStatement(statement)
    override suspend fun updateCreditStatement(statement: CreditStatement) = firebase.updateCreditStatement(statement)
    override suspend fun deleteCreditStatement(statementId: String) = firebase.deleteCreditStatement(statementId)
    override fun getCreditStatements(): Flow<List<CreditStatement>> = firebase.getCreditStatements()
    override suspend fun statementWithSmsIdExists(smsId: String): Boolean = firebase.statementWithSmsIdExists(smsId)
    override suspend fun getUnpaidStatementsOnce(): List<CreditStatement> = firebase.getUnpaidStatementsOnce()
    override suspend fun markStatementAsPaidById(statementId: String) = firebase.markStatementAsPaidById(statementId)

    override suspend fun addBudget(budget: Budget) = firebase.addBudget(budget)
    override suspend fun updateBudget(budget: Budget) = firebase.updateBudget(budget)
    override suspend fun deleteBudget(budgetId: String) = firebase.deleteBudget(budgetId)
    override fun getBudgets(): Flow<List<Budget>> = firebase.getBudgets()

    override suspend fun addSavingsGoal(goal: SavingsGoal) = firebase.addSavingsGoal(goal)
    override suspend fun updateSavingsGoal(goal: SavingsGoal) = firebase.updateSavingsGoal(goal)
    override suspend fun deleteSavingsGoal(id: String) = firebase.deleteSavingsGoal(id)
    override fun getSavingsGoals(): Flow<List<SavingsGoal>> = firebase.getSavingsGoals()

    override suspend fun addDebt(debt: Debt) = firebase.addDebt(debt)
    override suspend fun updateDebt(debt: Debt) = firebase.updateDebt(debt)
    override suspend fun deleteDebt(id: String) = firebase.deleteDebt(id)
    override fun getDebts(): Flow<List<Debt>> = firebase.getDebts()

    override suspend fun addBill(bill: Bill) = firebase.addBill(bill)
    override suspend fun updateBill(bill: Bill) = firebase.updateBill(bill)
    override suspend fun deleteBill(id: String) = firebase.deleteBill(id)
    override fun getBills(): Flow<List<Bill>> = firebase.getBills()

    override suspend fun addCategoryRule(rule: CategoryRule): String? = firebase.addCategoryRule(rule)
    override suspend fun deleteCategoryRule(ruleId: String) = firebase.deleteCategoryRule(ruleId)
    override fun getCategoryRules(): Flow<List<CategoryRule>> = firebase.getCategoryRules()

    override suspend fun addNotification(notification: AppNotification) = firebase.addNotification(notification)
    override fun getNotifications(): Flow<List<AppNotification>> = firebase.getNotifications()
    override suspend fun clearNotifications() = firebase.clearNotifications()

    override suspend fun addCustomSubCategory(sub: CustomSubCategory): String? = firebase.addCustomSubCategory(sub)
    override suspend fun deleteCustomSubCategory(id: String) = firebase.deleteCustomSubCategory(id)
    override fun getCustomSubCategories(): Flow<List<CustomSubCategory>> = firebase.getCustomSubCategories()

    override suspend fun uploadReceiptPhoto(userId: String, recordId: String, uri: Uri): String? =
        firebase.uploadReceiptPhoto(userId, recordId, uri)

    override suspend fun deleteAllUserData() {
        firebase.deleteAllUserData()
        recordDao.deleteAllForUser(userId)
        accountDao.deleteAllForUser(userId)
    }
}
