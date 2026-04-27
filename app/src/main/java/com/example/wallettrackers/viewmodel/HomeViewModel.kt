package com.example.wallettrackers.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SavingsGoal
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.util.BillReminderManager
import com.example.wallettrackers.util.NotificationHelper
import com.example.wallettrackers.util.ReminderManager
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HomeViewModel(private val userId: String) : ViewModel() {

    private val repository = FirebaseRepository(userId)

    data class MonthlyInsight(
        val topCategory: String = "",
        val topAmount: Double = 0.0,
        val currency: String = "EGP",
        val totalExpense: Double = 0.0
    )

    val accounts = mutableStateOf<List<Account>>(emptyList())
    val records = mutableStateOf<List<Record>>(emptyList())
    val statements = mutableStateOf<List<CreditStatement>>(emptyList())
    val budgets = mutableStateOf<List<Budget>>(emptyList())
    val savingsGoals = mutableStateOf<List<SavingsGoal>>(emptyList())
    val debts = mutableStateOf<List<Debt>>(emptyList())
    val bills = mutableStateOf<List<Bill>>(emptyList())
    val monthlyInsight = mutableStateOf(MonthlyInsight())
    val toastMessage = mutableStateOf<String?>(null)

    // State for AddRecordScreen
    val addRecordSelectedAccount = mutableStateOf<Account?>(null)
    val addRecordAmount = mutableStateOf("")
    val addRecordPayFromAccount = mutableStateOf<Account?>(null)

    // State for Editing Record
    val editingRecord = mutableStateOf<Record?>(null)
    val showEditDialog = mutableStateOf(false)

    fun onAddRecordAccountChange(account: Account) {
        addRecordSelectedAccount.value = account
    }

    fun onAddRecordAmountChange(newAmount: String) {
        addRecordAmount.value = newAmount
    }

    fun onAddRecordPayFromAccountChange(account: Account) {
        addRecordPayFromAccount.value = account
    }

    fun clearAddRecordState() {
        addRecordSelectedAccount.value = null
        addRecordAmount.value = ""
        addRecordPayFromAccount.value = null
    }

    fun startEditing(record: Record) {
        editingRecord.value = record
        showEditDialog.value = true
    }

    fun updateEditingCategory(category: String) {
        editingRecord.value = editingRecord.value?.copy(category = category)
    }

    fun updateEditingAmount(amount: String) {
        editingRecord.value = editingRecord.value?.copy(amount = amount)
    }

    fun updateEditingAccount(account: Account) {
        editingRecord.value = editingRecord.value?.copy(
            accountId = account.id,
            accountName = account.name,
            currency = account.currency
        )
    }

    fun updateEditingComment(comment: String) {
        editingRecord.value = editingRecord.value?.copy(comment = comment)
    }

    fun stopEditing() {
        editingRecord.value = null
        showEditDialog.value = false
    }

    fun saveEditedRecord(category: String? = null) {
        viewModelScope.launch {
            try {
                val recordToSave = if (category != null) {
                    editingRecord.value?.copy(category = category)
                } else {
                    editingRecord.value
                }
                recordToSave?.let { updateRecord(it) }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error saving edited record", e)
                toastMessage.value = "Update failed: ${e.message}"
            } finally {
                stopEditing()
            }
        }
    }

    init {
        loadAccounts()
        loadRecords()
        loadStatements()
        loadBudgets()
        loadSavingsGoals()
        loadDebts()
        loadBills()
    }

    private fun loadAccounts() {
        viewModelScope.launch {
            repository.getAccounts()
                .catch { error ->
                    Log.e("HomeViewModel", "Error loading accounts", error)
                    toastMessage.value = error.message
                }
                .collect { accountList ->
                    accounts.value = accountList
                }
        }
    }

    private fun loadRecords() {
        viewModelScope.launch {
            repository.getRecords()
                .catch { error ->
                    Log.e("HomeViewModel", "Error loading records", error)
                    toastMessage.value = error.message
                }
                .collect { recordList ->
                    val sorted = recordList.sortedByDescending { it.timestamp }
                    records.value = sorted
                    updateInsights(sorted)
                }
        }
    }

    private fun loadBudgets() {
        viewModelScope.launch {
            repository.getBudgets().catch { }.collect { budgets.value = it }
        }
    }

    private fun loadSavingsGoals() {
        viewModelScope.launch {
            repository.getSavingsGoals().catch { }.collect { savingsGoals.value = it }
        }
    }

    private fun loadDebts() {
        viewModelScope.launch {
            repository.getDebts().catch { }.collect { debts.value = it }
        }
    }

    private fun loadBills() {
        viewModelScope.launch {
            repository.getBills().catch { }.collect { bills.value = it }
        }
    }

    private fun updateInsights(recordList: List<Record>) {
        val cal = Calendar.getInstance()
        val thisMonth = cal.get(Calendar.MONTH)
        val thisYear = cal.get(Calendar.YEAR)
        val thisMonthExpenses = recordList.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == thisMonth && rc.get(Calendar.YEAR) == thisYear &&
            r.type == "Expense" && !r.accountName.contains("->")
        }
        val totalExpense = thisMonthExpenses.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        val top = thisMonthExpenses
            .groupBy { it.category }
            .mapValues { (_, rs) -> rs.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
            .maxByOrNull { it.value }
        monthlyInsight.value = MonthlyInsight(
            topCategory = top?.key ?: "",
            topAmount = top?.value ?: 0.0,
            totalExpense = totalExpense
        )
    }

    fun currentMonthSpendForCategory(category: String): Double {
        val cal = Calendar.getInstance()
        val thisMonth = cal.get(Calendar.MONTH)
        val thisYear = cal.get(Calendar.YEAR)
        val parentCat = Categories.list.find { it.name == category }
        return records.value.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == thisMonth && rc.get(Calendar.YEAR) == thisYear &&
            r.type == "Expense" &&
            (r.category == category || parentCat?.subCategories?.any { it.name == r.category } == true)
        }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    }

    private fun loadStatements() {
        viewModelScope.launch {
            repository.getCreditStatements()
                .catch { error ->
                    Log.e("HomeViewModel", "Error loading statements", error)
                }
                .collect { statementList ->
                    statements.value = statementList
                }
        }
    }

    fun addAccount(account: Account) {
        viewModelScope.launch {
            try {
                repository.addAccount(account.copy(userId = userId))
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding account", e)
                toastMessage.value = e.message
            }
        }
    }

    fun updateAccount(account: Account) {
        viewModelScope.launch {
            try {
                repository.updateAccount(account)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error updating account", e)
                toastMessage.value = e.message
            }
        }
    }

    fun deleteAccount(accountId: String) {
        viewModelScope.launch {
            try {
                repository.deleteAccount(accountId)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error deleting account", e)
                toastMessage.value = e.message
            }
        }
    }

    fun addRecord(record: Record) {
        viewModelScope.launch {
            try {
                if (record.category == "Credit") {
                    val debitAccount = addRecordPayFromAccount.value
                    if (debitAccount == null) {
                        toastMessage.value = "Please select the account to pay from"
                        return@launch
                    }
                    handleManualCreditPayment(record, debitAccount)
                } else {
                    handleNormalRecord(record)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding record", e)
                toastMessage.value = e.message
            }
        }
    }

    private suspend fun handleManualCreditPayment(record: Record, debitAccount: Account) {
        val creditAccount = accounts.value.find { it.id == record.accountId } ?: run {
            toastMessage.value = "Credit card account not found"
            return
        }
        val paymentAmount = record.amount.toDoubleOrNull() ?: 0.0

        val newDebitBal = (debitAccount.amount.toDoubleOrNull() ?: 0.0) - paymentAmount
        val newCreditBal = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmount

        // Mark any matching unpaid statement as paid
        val digits = creditAccount.last4Digits.filter { it.isDigit() }
        val unpaidStatement = statements.value.find { it.cardLast4Digits == digits && !it.isPaid }
        if (unpaidStatement != null) {
            repository.deleteCreditStatement(unpaidStatement.id)
        }

        val finalRecord = record.copy(
            userId = userId,
            type = "Expense",
            balanceAfter = formatBalance(newDebitBal),
            accountId = debitAccount.id,
            accountName = "${debitAccount.name} -> ${creditAccount.name}"
        )

        repository.batchUpdateTwoAccountsAndAddRecord(
            debitAccount.copy(amount = formatBalance(newDebitBal)),
            creditAccount.copy(amount = formatBalance(newCreditBal)),
            finalRecord
        )
    }

    fun payCreditStatement(statement: CreditStatement, debitAccount: Account) {
        viewModelScope.launch {
            try {
                repository.deleteCreditStatement(statement.id)

                val creditAccount = accounts.value.find {
                    it.id == statement.accountId ||
                    it.last4Digits.endsWith(statement.cardLast4Digits)
                }

                val amountToPay = statement.totalAmount
                val newDebitBal = (debitAccount.amount.toDoubleOrNull() ?: 0.0) - amountToPay

                val record = Record(
                    accountId = debitAccount.id,
                    accountName = if (creditAccount != null)
                        "${debitAccount.name} -> ${creditAccount.name}"
                    else
                        "${debitAccount.name} (CC Payment)",
                    amount = amountToPay.toString(),
                    currency = debitAccount.currency,
                    category = "Credit",
                    type = "Expense",
                    timestamp = Date(),
                    userId = userId,
                    balanceAfter = formatBalance(newDebitBal)
                )

                if (creditAccount != null) {
                    val newCreditBal = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + amountToPay
                    repository.batchUpdateTwoAccountsAndAddRecord(
                        debitAccount.copy(amount = formatBalance(newDebitBal)),
                        creditAccount.copy(amount = formatBalance(newCreditBal)),
                        record
                    )
                } else {
                    repository.batchAddRecordAndUpdateAccount(
                        debitAccount.copy(amount = formatBalance(newDebitBal)),
                        record
                    )
                }

                toastMessage.value = "Card paid and removed successfully"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error paying statement", e)
                toastMessage.value = "Failed to process payment: ${e.message}"
            }
        }
    }

    private var appContext: Context? = null

    fun setContext(context: Context) { appContext = context.applicationContext }

    private suspend fun handleNormalRecord(record: Record) {
        val account = accounts.value.find { it.id == record.accountId }
        if (account != null) {
            val isIncome = Categories.isIncomeCategory(record.category) || record.type == "Income"
            val amountDouble = record.amount.toDoubleOrNull() ?: 0.0
            val currentBal = account.amount.toDoubleOrNull() ?: 0.0
            val newBalance = if (isIncome) currentBal + amountDouble else currentBal - amountDouble

            repository.batchAddRecordAndUpdateAccount(
                account.copy(amount = formatBalance(newBalance)),
                record.copy(
                    userId = userId,
                    balanceAfter = formatBalance(newBalance),
                    type = if (isIncome) "Income" else "Expense"
                )
            )

            if (!isIncome) checkBudgetAlert(record.category, amountDouble)
        }
    }

    private fun checkBudgetAlert(category: String, addedAmount: Double) {
        val ctx = appContext ?: return
        val matchingBudget = budgets.value.find { b ->
            b.category == category ||
            Categories.list.find { it.name == b.category }?.subCategories?.any { it.name == category } == true
        } ?: return
        val spent = currentMonthSpendForCategory(matchingBudget.category) + addedAmount
        val pct = spent / matchingBudget.monthlyLimit
        if (pct >= 0.8) {
            NotificationHelper.createChannels(ctx)
            NotificationHelper.sendBudgetAlert(ctx, matchingBudget.category, spent, matchingBudget.monthlyLimit, matchingBudget.currency)
        }
    }

    fun updateRecord(record: Record) {
        viewModelScope.launch {
            try {
                val original = records.value.find { it.id == record.id }

                // Skip balance adjustment for transfer records (cross-account payments)
                if (original == null || original.accountName.contains("->")) {
                    repository.updateRecord(record)
                    toastMessage.value = "Record updated"
                    return@launch
                }

                val origAmount = original.amount.toDoubleOrNull() ?: 0.0
                val newAmount = record.amount.toDoubleOrNull() ?: 0.0
                val isOrigIncome = original.type == "Income"
                val isNewIncome = record.type == "Income"

                if (original.accountId == record.accountId) {
                    val account = accounts.value.find { it.id == record.accountId }
                    if (account != null) {
                        val cur = account.amount.toDoubleOrNull() ?: 0.0
                        val reversed = if (isOrigIncome) cur - origAmount else cur + origAmount
                        val finalBal = if (isNewIncome) reversed + newAmount else reversed - newAmount
                        repository.batchUpdateAccountAndRecord(
                            account.copy(amount = formatBalance(finalBal)),
                            record.copy(balanceAfter = formatBalance(finalBal))
                        )
                    } else {
                        repository.updateRecord(record)
                    }
                } else {
                    // Account changed: reverse on old account, apply on new account
                    val oldAcc = accounts.value.find { it.id == original.accountId }
                    val newAcc = accounts.value.find { it.id == record.accountId }
                    if (oldAcc != null && newAcc != null) {
                        val restoredOld = (oldAcc.amount.toDoubleOrNull() ?: 0.0).let {
                            if (isOrigIncome) it - origAmount else it + origAmount
                        }
                        val updatedNew = (newAcc.amount.toDoubleOrNull() ?: 0.0).let {
                            if (isNewIncome) it + newAmount else it - newAmount
                        }
                        repository.batchUpdateTwoAccountsAndRecord(
                            oldAcc.copy(amount = formatBalance(restoredOld)),
                            newAcc.copy(amount = formatBalance(updatedNew)),
                            record.copy(balanceAfter = formatBalance(updatedNew))
                        )
                    } else {
                        repository.updateRecord(record)
                    }
                }
                toastMessage.value = "Record updated"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error updating record", e)
                toastMessage.value = e.message
            }
        }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            try {
                val record = records.value.find { it.id == recordId }

                if (record != null && !record.accountName.contains("->")) {
                    val account = accounts.value.find { it.id == record.accountId }
                    if (account != null) {
                        val amount = record.amount.toDoubleOrNull() ?: 0.0
                        val cur = account.amount.toDoubleOrNull() ?: 0.0
                        val restored = if (record.type == "Income") cur - amount else cur + amount
                        repository.batchUpdateAccountAndDeleteRecord(
                            account.copy(amount = formatBalance(restored)),
                            recordId
                        )
                        return@launch
                    }
                }
                repository.deleteRecord(recordId)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error deleting record", e)
                toastMessage.value = e.message
            }
        }
    }

    fun deleteUser() {
        viewModelScope.launch {
            try {
                repository.deleteAllUserData()
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error deleting user", e)
                toastMessage.value = e.message
            }
        }
    }

    fun onToastShown() {
        toastMessage.value = null
    }

    fun addCreditStatement(statement: CreditStatement) {
        viewModelScope.launch {
            repository.addCreditStatement(statement.copy(userId = userId))
        }
    }

    fun deleteCreditStatement(id: String) {
        viewModelScope.launch {
            repository.deleteCreditStatement(id)
        }
    }

    fun markStatementAsPaid(statement: CreditStatement) {
        viewModelScope.launch {
            repository.updateCreditStatement(statement.copy(isPaid = true))
        }
    }

    // Savings Goals
    fun addSavingsGoal(goal: SavingsGoal) { viewModelScope.launch { repository.addSavingsGoal(goal.copy(userId = userId)) } }
    fun updateSavingsGoal(goal: SavingsGoal) { viewModelScope.launch { repository.updateSavingsGoal(goal) } }
    fun deleteSavingsGoal(id: String) { viewModelScope.launch { repository.deleteSavingsGoal(id) } }

    // Debts
    fun addDebt(debt: Debt) { viewModelScope.launch { repository.addDebt(debt.copy(userId = userId)) } }
    fun updateDebt(debt: Debt) { viewModelScope.launch { repository.updateDebt(debt) } }
    fun deleteDebt(id: String) { viewModelScope.launch { repository.deleteDebt(id) } }

    // Bills
    fun addBill(bill: Bill, context: Context) {
        viewModelScope.launch {
            repository.addBill(bill.copy(userId = userId))
            BillReminderManager.scheduleBillReminder(context, bill)
        }
    }
    fun updateBill(bill: Bill, context: Context) {
        viewModelScope.launch {
            repository.updateBill(bill)
            BillReminderManager.cancelBillReminder(context, bill.id)
            if (bill.isActive) BillReminderManager.scheduleBillReminder(context, bill)
        }
    }
    fun deleteBill(id: String, context: Context) {
        viewModelScope.launch {
            repository.deleteBill(id)
            BillReminderManager.cancelBillReminder(context, id)
        }
    }

    fun addBudget(budget: Budget) {
        viewModelScope.launch { repository.addBudget(budget.copy(userId = userId)) }
    }

    fun updateBudget(budget: Budget) {
        viewModelScope.launch { repository.updateBudget(budget) }
    }

    fun deleteBudget(budgetId: String) {
        viewModelScope.launch { repository.deleteBudget(budgetId) }
    }

    fun archiveAccount(accountId: String) {
        val account = accounts.value.find { it.id == accountId } ?: return
        viewModelScope.launch { repository.updateAccount(account.copy(isArchived = true)) }
    }

    fun unarchiveAccount(accountId: String) {
        val account = accounts.value.find { it.id == accountId } ?: return
        viewModelScope.launch { repository.updateAccount(account.copy(isArchived = false)) }
    }

    fun transferBetweenAccounts(fromAccount: Account, toAccount: Account, amount: Double, note: String) {
        viewModelScope.launch {
            try {
                val newFromBal = (fromAccount.amount.toDoubleOrNull() ?: 0.0) - amount
                val newToBal = (toAccount.amount.toDoubleOrNull() ?: 0.0) + amount
                val record = Record(
                    accountId = fromAccount.id,
                    accountName = "${fromAccount.name} -> ${toAccount.name}",
                    amount = formatBalance(amount),
                    currency = fromAccount.currency,
                    category = "Transfer",
                    type = "Expense",
                    timestamp = Date(),
                    userId = userId,
                    comment = note,
                    balanceAfter = formatBalance(newFromBal)
                )
                repository.batchUpdateTwoAccountsAndAddRecord(
                    fromAccount.copy(amount = formatBalance(newFromBal)),
                    toAccount.copy(amount = formatBalance(newToBal)),
                    record
                )
                toastMessage.value = "Transfer completed"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Transfer error", e)
                toastMessage.value = "Transfer failed: ${e.message}"
            }
        }
    }

    fun exportToCsvString(recordList: List<Record>): String {
        val sb = StringBuilder()
        sb.appendLine("Date,Account,Category,Type,Amount,Currency,Comment,Balance After")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        recordList.forEach { r ->
            sb.appendLine(
                "${fmt.format(r.timestamp)},\"${r.accountName}\",\"${r.category}\"," +
                "${r.type},${r.amount},${r.currency},\"${r.comment}\",${r.balanceAfter}"
            )
        }
        return sb.toString()
    }

    private fun formatBalance(value: Double): String = String.format("%.2f", value)
}
