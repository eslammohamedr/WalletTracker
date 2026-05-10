package com.example.wallettrackers.repository

import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.CategoryRule
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SavingsGoal
import kotlinx.coroutines.flow.Flow

interface WalletRepository {

    // ── Accounts ──────────────────────────────────────────────────────────
    suspend fun addAccount(account: Account)
    suspend fun addAccountAndGetId(account: Account): String?
    suspend fun updateAccount(account: Account)
    suspend fun deleteAccount(accountId: String)
    fun getAccounts(): Flow<List<Account>>

    // ── Records ───────────────────────────────────────────────────────────
    suspend fun addRecord(record: Record)
    suspend fun updateRecord(record: Record)
    suspend fun deleteRecord(recordId: String)
    fun getRecords(): Flow<List<Record>>
    suspend fun recordWithSmsIdExists(smsId: String): Boolean
    suspend fun findRecordBySmsId(smsId: String): Record?
    suspend fun findRecentCardPaymentRecord(amount: String): Record?
    suspend fun findRecentDebitExpenseRecord(amount: String): Record?

    // ── Batch operations ──────────────────────────────────────────────────
    suspend fun batchAddRecordAndUpdateAccount(account: Account, record: Record)
    suspend fun batchUpdateAccountAndRecord(account: Account, record: Record)
    suspend fun batchUpdateTwoAccountsAndRecord(account1: Account, account2: Account, record: Record)
    suspend fun batchUpdateTwoAccountsAndAddRecord(account1: Account, account2: Account, record: Record)
    suspend fun batchUpdateAccountAndDeleteRecord(account: Account, recordId: String)
    suspend fun batchUpdateTwoAccountsAndDeleteRecord(account1: Account, account2: Account, recordId: String)
    suspend fun batchUpdateMultipleAccountsAndRecord(updatedAccounts: List<Account>, record: Record)
    suspend fun batchUpdateRecords(records: List<Record>)

    // ── Credit statements ─────────────────────────────────────────────────
    suspend fun addCreditStatement(statement: CreditStatement)
    suspend fun updateCreditStatement(statement: CreditStatement)
    suspend fun deleteCreditStatement(statementId: String)
    fun getCreditStatements(): Flow<List<CreditStatement>>
    suspend fun statementWithSmsIdExists(smsId: String): Boolean

    // ── Budgets ───────────────────────────────────────────────────────────
    suspend fun addBudget(budget: Budget)
    suspend fun updateBudget(budget: Budget)
    suspend fun deleteBudget(budgetId: String)
    fun getBudgets(): Flow<List<Budget>>

    // ── Savings goals ─────────────────────────────────────────────────────
    suspend fun addSavingsGoal(goal: SavingsGoal)
    suspend fun updateSavingsGoal(goal: SavingsGoal)
    suspend fun deleteSavingsGoal(id: String)
    fun getSavingsGoals(): Flow<List<SavingsGoal>>

    // ── Debts ─────────────────────────────────────────────────────────────
    suspend fun addDebt(debt: Debt)
    suspend fun updateDebt(debt: Debt)
    suspend fun deleteDebt(id: String)
    fun getDebts(): Flow<List<Debt>>

    // ── Bills ─────────────────────────────────────────────────────────────
    suspend fun addBill(bill: Bill)
    suspend fun updateBill(bill: Bill)
    suspend fun deleteBill(id: String)
    fun getBills(): Flow<List<Bill>>

    // ── Category rules ────────────────────────────────────────────────────
    suspend fun addCategoryRule(rule: CategoryRule): String?
    suspend fun deleteCategoryRule(ruleId: String)
    fun getCategoryRules(): Flow<List<CategoryRule>>

    // ── User data ─────────────────────────────────────────────────────────
    suspend fun deleteAllUserData()
}
