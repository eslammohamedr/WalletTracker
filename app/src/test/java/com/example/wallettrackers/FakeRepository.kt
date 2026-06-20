package com.example.wallettrackers

import android.net.Uri
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
import com.example.wallettrackers.repository.WalletRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeRepository : WalletRepository {

    private val _accounts      = MutableStateFlow<List<Account>>(emptyList())
    private val _records       = MutableStateFlow<List<Record>>(emptyList())
    private val _statements    = MutableStateFlow<List<CreditStatement>>(emptyList())
    private val _budgets       = MutableStateFlow<List<Budget>>(emptyList())
    private val _savingsGoals  = MutableStateFlow<List<SavingsGoal>>(emptyList())
    private val _debts         = MutableStateFlow<List<Debt>>(emptyList())
    private val _bills         = MutableStateFlow<List<Bill>>(emptyList())
    private val _categoryRules = MutableStateFlow<List<CategoryRule>>(emptyList())
    private val _notifications  = MutableStateFlow<List<AppNotification>>(emptyList())
    private val _customSubCats  = MutableStateFlow<List<CustomSubCategory>>(emptyList())

    fun setAccounts(list: List<Account>)           { _accounts.value = list }
    fun setRecords(list: List<Record>)             { _records.value = list }
    fun setBudgets(list: List<Budget>)             { _budgets.value = list }
    fun setStatements(list: List<CreditStatement>) { _statements.value = list }
    fun setCategoryRules(list: List<CategoryRule>) { _categoryRules.value = list }

    // ── Accounts ──────────────────────────────────────────────────────────
    override fun getAccounts(): Flow<List<Account>> = _accounts
    override suspend fun addAccount(account: Account) {
        _accounts.value = _accounts.value + account
    }
    override suspend fun addAccountAndGetId(account: Account): String? {
        val id = "acc-${_accounts.value.size}"
        _accounts.value = _accounts.value + account.copy(id = id)
        return id
    }
    override suspend fun updateAccount(account: Account) {
        _accounts.value = _accounts.value.map { if (it.id == account.id) account else it }
    }
    override suspend fun deleteAccount(accountId: String) {
        _accounts.value = _accounts.value.filter { it.id != accountId }
    }

    // ── Records ───────────────────────────────────────────────────────────
    override fun getRecords(): Flow<List<Record>> = _records
    override suspend fun addRecord(record: Record) {
        _records.value = _records.value + record
    }
    override suspend fun updateRecord(record: Record) {
        _records.value = _records.value.map { if (it.id == record.id) record else it }
    }
    override suspend fun deleteRecord(recordId: String) {
        _records.value = _records.value.filter { it.id != recordId }
    }
    override suspend fun recordWithSmsIdExists(smsId: String) =
        _records.value.any { it.smsId == smsId }
    override suspend fun findRecordBySmsId(smsId: String) =
        _records.value.find { it.smsId == smsId }
    override suspend fun findRecentCardPaymentRecord(amount: String): Record? = null
    override suspend fun findRecentDebitExpenseRecord(amount: String): Record? = null

    // ── Batch operations ──────────────────────────────────────────────────
    override suspend fun batchAddRecordAndUpdateAccount(account: Account, record: Record) {
        updateAccount(account)
        addRecord(record)
    }
    override suspend fun batchUpdateAccountAndRecord(account: Account, record: Record) {
        updateAccount(account)
        updateRecord(record)
    }
    override suspend fun batchUpdateTwoAccountsAndRecord(
        account1: Account, account2: Account, record: Record
    ) {
        updateAccount(account1); updateAccount(account2); updateRecord(record)
    }
    override suspend fun batchUpdateTwoAccountsAndAddRecord(
        account1: Account, account2: Account, record: Record
    ) {
        updateAccount(account1); updateAccount(account2); addRecord(record)
    }
    override suspend fun batchUpdateAccountAndDeleteRecord(account: Account, recordId: String) {
        updateAccount(account); deleteRecord(recordId)
    }
    override suspend fun batchUpdateTwoAccountsAndDeleteRecord(
        account1: Account, account2: Account, recordId: String
    ) {
        updateAccount(account1); updateAccount(account2); deleteRecord(recordId)
    }
    override suspend fun batchUpdateMultipleAccountsAndRecord(
        updatedAccounts: List<Account>, record: Record
    ) {
        updatedAccounts.forEach { updateAccount(it) }; updateRecord(record)
    }
    override suspend fun batchUpdateRecords(records: List<Record>) {
        records.forEach { updateRecord(it) }
    }

    // ── Credit statements ─────────────────────────────────────────────────
    override fun getCreditStatements(): Flow<List<CreditStatement>> = _statements
    override suspend fun addCreditStatement(statement: CreditStatement) {
        _statements.value = _statements.value + statement
    }
    override suspend fun updateCreditStatement(statement: CreditStatement) {
        _statements.value = _statements.value.map { if (it.id == statement.id) statement else it }
    }
    override suspend fun deleteCreditStatement(statementId: String) {
        _statements.value = _statements.value.filter { it.id != statementId }
    }
    override suspend fun statementWithSmsIdExists(smsId: String) =
        _statements.value.any { it.smsId == smsId }
    override suspend fun getUnpaidStatementsOnce(): List<CreditStatement> =
        _statements.value.filter { !it.isPaid }
    override suspend fun markStatementAsPaidById(statementId: String) {
        _statements.value = _statements.value.map {
            if (it.id == statementId) it.copy(isPaid = true) else it
        }
    }

    // ── Budgets ───────────────────────────────────────────────────────────
    override fun getBudgets(): Flow<List<Budget>> = _budgets
    override suspend fun addBudget(budget: Budget) { _budgets.value = _budgets.value + budget }
    override suspend fun updateBudget(budget: Budget) {
        _budgets.value = _budgets.value.map { if (it.id == budget.id) budget else it }
    }
    override suspend fun deleteBudget(budgetId: String) {
        _budgets.value = _budgets.value.filter { it.id != budgetId }
    }

    // ── Savings goals ─────────────────────────────────────────────────────
    override fun getSavingsGoals(): Flow<List<SavingsGoal>> = _savingsGoals
    override suspend fun addSavingsGoal(goal: SavingsGoal) {
        _savingsGoals.value = _savingsGoals.value + goal
    }
    override suspend fun updateSavingsGoal(goal: SavingsGoal) {
        _savingsGoals.value = _savingsGoals.value.map { if (it.id == goal.id) goal else it }
    }
    override suspend fun deleteSavingsGoal(id: String) {
        _savingsGoals.value = _savingsGoals.value.filter { it.id != id }
    }

    // ── Debts ─────────────────────────────────────────────────────────────
    override fun getDebts(): Flow<List<Debt>> = _debts
    override suspend fun addDebt(debt: Debt) { _debts.value = _debts.value + debt }
    override suspend fun updateDebt(debt: Debt) {
        _debts.value = _debts.value.map { if (it.id == debt.id) debt else it }
    }
    override suspend fun deleteDebt(id: String) { _debts.value = _debts.value.filter { it.id != id } }

    // ── Bills ─────────────────────────────────────────────────────────────
    override fun getBills(): Flow<List<Bill>> = _bills
    override suspend fun addBill(bill: Bill) { _bills.value = _bills.value + bill }
    override suspend fun updateBill(bill: Bill) {
        _bills.value = _bills.value.map { if (it.id == bill.id) bill else it }
    }
    override suspend fun deleteBill(id: String) { _bills.value = _bills.value.filter { it.id != id } }

    // ── Category rules ────────────────────────────────────────────────────
    override fun getCategoryRules(): Flow<List<CategoryRule>> = _categoryRules
    override suspend fun addCategoryRule(rule: CategoryRule): String? {
        val id = "rule-${_categoryRules.value.size}"
        _categoryRules.value = _categoryRules.value + rule.copy(id = id)
        return id
    }
    override suspend fun deleteCategoryRule(ruleId: String) {
        _categoryRules.value = _categoryRules.value.filter { it.id != ruleId }
    }

    // ── Notifications ─────────────────────────────────────────────────────
    override fun getNotifications(): Flow<List<AppNotification>> = _notifications
    override suspend fun addNotification(notification: AppNotification) {
        _notifications.value = _notifications.value + notification
    }
    override suspend fun clearNotifications() { _notifications.value = emptyList() }

    // ── Custom subcategories ──────────────────────────────────────────────
    override fun getCustomSubCategories(): Flow<List<CustomSubCategory>> = _customSubCats
    override suspend fun addCustomSubCategory(sub: CustomSubCategory): String? {
        val id = "sub-${_customSubCats.value.size}"
        _customSubCats.value = _customSubCats.value + sub.copy(id = id)
        return id
    }
    override suspend fun deleteCustomSubCategory(id: String) {
        _customSubCats.value = _customSubCats.value.filter { it.id != id }
    }

    // ── Receipt photos ────────────────────────────────────────────────────
    override suspend fun uploadReceiptPhoto(userId: String, recordId: String, uri: Uri): String? = null

    // ── User data ─────────────────────────────────────────────────────────
    override suspend fun deleteAllUserData() {
        _accounts.value = emptyList(); _records.value = emptyList()
        _statements.value = emptyList(); _budgets.value = emptyList()
        _savingsGoals.value = emptyList(); _debts.value = emptyList()
        _bills.value = emptyList(); _categoryRules.value = emptyList()
        _notifications.value = emptyList(); _customSubCats.value = emptyList()
    }
}
