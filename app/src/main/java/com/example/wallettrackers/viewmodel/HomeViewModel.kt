package com.example.wallettrackers.viewmodel

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.CategoryRule
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SavingsGoal
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.util.BillReminderManager
import com.example.wallettrackers.util.BudgetCalculator
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

    data class RecurringBillSuggestion(
        val name: String,
        val amount: Double,
        val currency: String,
        val dayOfMonth: Int,
        val category: String,
        val monthsDetected: Int,
        val detectionType: String = "Payment"
    )

    val accounts = mutableStateOf<List<Account>>(emptyList())
    val records = mutableStateOf<List<Record>>(emptyList())
    val statements = mutableStateOf<List<CreditStatement>>(emptyList())
    val budgets = mutableStateOf<List<Budget>>(emptyList())
    val savingsGoals = mutableStateOf<List<SavingsGoal>>(emptyList())
    val debts = mutableStateOf<List<Debt>>(emptyList())
    val bills = mutableStateOf<List<Bill>>(emptyList())
    val suggestedBills = mutableStateOf<List<RecurringBillSuggestion>>(emptyList())
    val monthlyInsight = mutableStateOf(MonthlyInsight())
    val toastMessage = mutableStateOf<String?>(null)

    private val dismissedSuggestionKeys = mutableSetOf<String>()

    // State for AddRecordScreen
    val addRecordSelectedAccount = mutableStateOf<Account?>(null)
    val addRecordAmount = mutableStateOf("")
    val addRecordPayFromAccount = mutableStateOf<Account?>(null)

    // State for Editing Record
    val editingRecord = mutableStateOf<Record?>(null)
    val showEditDialog = mutableStateOf(false)

    // Category Rules
    val categoryRules = mutableStateOf<List<CategoryRule>>(emptyList())
    val pendingRuleRecord = mutableStateOf<Record?>(null)

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
        loadCategoryRules()
    }

    private fun loadCategoryRules() {
        viewModelScope.launch {
            repository.getCategoryRules().catch { }.collect { categoryRules.value = it }
        }
    }

    fun startPendingRule(record: Record) {
        pendingRuleRecord.value = record
    }

    fun clearPendingRule() {
        pendingRuleRecord.value = null
    }

    fun saveRuleAndResync(category: String) {
        val record = pendingRuleRecord.value ?: return
        val merchant = record.comment
            .removePrefix("To: ")
            .removePrefix("From: ")
            .trim()
        if (merchant.isBlank() || merchant.all { it.isDigit() }) {
            clearPendingRule()
            return
        }
        viewModelScope.launch {
            repository.addCategoryRule(CategoryRule(merchantKeyword = merchant, category = category, userId = userId))
            var updated = 0
            records.value.forEach { r ->
                if (r.comment.contains(merchant, ignoreCase = true) && r.category != category) {
                    repository.updateRecord(r.copy(category = category))
                    updated++
                }
            }
            toastMessage.value = if (updated > 0)
                "Rule saved — updated $updated record${if (updated > 1) "s" else ""}"
            else "Rule saved for \"$merchant\""
            clearPendingRule()
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            repository.deleteCategoryRule(ruleId)
            toastMessage.value = "Rule deleted"
        }
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
        val subcategoryMap = Categories.list.associate { it.name to it.subCategories.map { s -> s.name } }
        return BudgetCalculator.spentInMonth(
            records.value,
            category,
            cal.get(Calendar.MONTH),
            cal.get(Calendar.YEAR),
            subcategoryMap
        )
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

    private fun normaliseCurrency(currency: String): String = when {
        currency.contains("Dollar", ignoreCase = true) || currency.equals("USD", ignoreCase = true) -> "USD"
        currency.contains("Euro", ignoreCase = true)   || currency.equals("EUR", ignoreCase = true) -> "EUR"
        currency.contains("Pound", ignoreCase = true)  || currency.equals("GBP", ignoreCase = true) -> "GBP"
        currency.equals("SAR", ignoreCase = true) -> "SAR"
        currency.equals("AED", ignoreCase = true) -> "AED"
        else -> "EGP"
    }

    private fun extractBalanceCurrencyFromSms(body: String): String? =
        Regex(
            """avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)\s*(?:[:\-]|is)?\s*(EGP|USD|EUR|GBP|SAR|AED)""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)?.uppercase()

    fun fixCreditCardCurrencies(context: Context) {
        viewModelScope.launch {
            try {
                val creditAccounts = accounts.value.filter {
                    it.accountType.contains("Credit", ignoreCase = true)
                }
                if (creditAccounts.isEmpty()) return@launch

                withContext(Dispatchers.IO) {
                    val cursor = context.contentResolver.query(
                        Uri.parse("content://sms/inbox"),
                        arrayOf("body"),
                        null, null,
                        "date DESC"
                    ) ?: return@withContext

                    val smsBodies = mutableListOf<String>()
                    cursor.use {
                        val bodyIdx = it.getColumnIndex("body")
                        while (it.moveToNext()) {
                            if (bodyIdx >= 0) smsBodies.add(it.getString(bodyIdx) ?: "")
                        }
                    }

                    creditAccounts.forEach { account ->
                        val digits = account.last4Digits.filter { c -> c.isDigit() }
                        if (digits.isEmpty()) return@forEach

                        val detectedCurrency = smsBodies
                            .firstOrNull { body ->
                                body.contains(digits) && extractBalanceCurrencyFromSms(body) != null
                            }
                            ?.let { extractBalanceCurrencyFromSms(it) }
                            ?: return@forEach

                        if (normaliseCurrency(account.currency) != detectedCurrency) {
                            repository.updateAccount(account.copy(currency = detectedCurrency))
                            Log.d("HomeViewModel", "Fixed ${account.name} currency: ${account.currency} → $detectedCurrency")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error fixing credit card currencies", e)
            }
        }
    }

    private fun displayRecordName(r: Record): String {
        return r.comment.trim()
            .removePrefix("To: ").removePrefix("From: ").trim()
            .ifBlank { r.category }
            .replaceFirstChar { it.uppercase() }
    }

    private fun isSubscriptionRecord(r: Record): Boolean {
        val name = displayRecordName(r).lowercase()
        return r.category.equals("Subscriptions", ignoreCase = true) ||
            r.category.equals("Subscription", ignoreCase = true) ||
            name.contains("netflix") ||
            name.contains("youtube") ||
            name.contains("amazon") ||
            name.contains("spotify") ||
            name.contains("disney") ||
            name.contains("yango")
    }

    fun addLastMonthSubscriptionsAsBills(context: Context) {
        viewModelScope.launch {
            val lastMonthCal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
            val lastMonthNum = lastMonthCal.get(Calendar.MONTH)
            val lastMonthYear = lastMonthCal.get(Calendar.YEAR)

            val lastMonthRecords = records.value.filter { r ->
                val rc = Calendar.getInstance().apply { time = r.timestamp }
                rc.get(Calendar.MONTH) == lastMonthNum &&
                rc.get(Calendar.YEAR) == lastMonthYear &&
                r.type == "Expense" &&
                !r.accountName.contains("->") &&
                isSubscriptionRecord(r)
            }

            val existingBillNames = bills.value.map { it.name.lowercase() }.toSet()

            val grouped = lastMonthRecords.groupBy { displayRecordName(it).lowercase() }

            var added = 0
            grouped.forEach { (nameKey, recs) ->
                if (nameKey !in existingBillNames) {
                    val record = recs.maxByOrNull { it.timestamp } ?: return@forEach
                    val amount = record.amount.replace(",", "").trim().toDoubleOrNull() ?: return@forEach
                    val name = displayRecordName(record)
                    val dayOfMonth = Calendar.getInstance().apply { time = record.timestamp }.get(Calendar.DAY_OF_MONTH)
                    addBill(Bill(name = name, amount = amount, currency = record.currency, dayOfMonth = dayOfMonth, category = record.category, userId = userId), context)
                    added++
                }
            }

            toastMessage.value = if (added > 0)
                "$added subscription${if (added > 1) "s" else ""} added to monthly bills"
            else
                "No new subscriptions found from last month"
        }
    }

    fun detectRecurringBills() {
        viewModelScope.launch {
            val oneMonthAgoDate = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.time
            val twoMonthsAgoDate = Calendar.getInstance().apply { add(Calendar.MONTH, -2) }.time
            val excluded = setOf("Credit Payment", "Transfer", "Credit", "Instapay income", "Instapay outcome")

            val recentExpenses = records.value.filter { r ->
                r.type == "Expense" &&
                !r.accountName.contains("->") &&
                r.category !in excluded
            }

            fun recordCalendar(r: Record): Calendar {
                return Calendar.getInstance().apply { time = r.timestamp }
            }

            fun recordMonthKey(r: Record): Int {
                val cal = recordCalendar(r)
                return monthKey(cal)
            }

            fun parseAmount(amount: String): Double? {
                return amount.replace(",", "").trim().toDoubleOrNull()
            }

            val addedSuggestionKeys = mutableSetOf<String>()
            val suggestions = mutableListOf<RecurringBillSuggestion>()

            recentExpenses
                .filter { isSubscriptionRecord(it) && !it.timestamp.before(oneMonthAgoDate) }
                .groupBy { r ->
                    val amount = parseAmount(r.amount) ?: 0.0
                    "${displayRecordName(r).lowercase()}|${"%.2f".format(amount)}|${recordCalendar(r).get(Calendar.DAY_OF_MONTH)}|${r.currency}"
                }
                .forEach { (_, recs) ->
                    val record = recs.maxByOrNull { it.timestamp } ?: return@forEach
                    val amount = parseAmount(record.amount) ?: return@forEach
                    val name = displayRecordName(record)
                    val normalizedName = name.lowercase()
                    val dayOfMonth = recordCalendar(record).get(Calendar.DAY_OF_MONTH)
                    val suggestionKey = "${normalizedName}|${"%.2f".format(amount)}|$dayOfMonth|${record.currency}"
                    if (dismissedSuggestionKeys.contains(normalizedName)) return@forEach
                    if (!addedSuggestionKeys.add(suggestionKey)) return@forEach

                    suggestions.add(
                        RecurringBillSuggestion(
                            name = name,
                            amount = amount,
                            currency = record.currency,
                            dayOfMonth = dayOfMonth,
                            category = record.category,
                            monthsDetected = 1,
                            detectionType = "Subscription"
                        )
                    )
                }

            recentExpenses
                .filter { !isSubscriptionRecord(it) && !it.timestamp.before(twoMonthsAgoDate) }
                .groupBy { r ->
                    val amount = parseAmount(r.amount) ?: 0.0
                    "${"%.2f".format(amount)}|${recordCalendar(r).get(Calendar.DAY_OF_MONTH)}|${r.currency}"
                }
                .forEach { (_, recs) ->
                    val monthGroups = recs.groupBy { recordMonthKey(it) }
                    if (monthGroups.size < 2) return@forEach

                    val record = recs.maxByOrNull { it.timestamp } ?: return@forEach
                    val amount = parseAmount(record.amount) ?: return@forEach
                    val name = displayRecordName(record)
                    val normalizedName = name.lowercase()
                    val dayOfMonth = recordCalendar(record).get(Calendar.DAY_OF_MONTH)
                    val suggestionKey = "${normalizedName}|${"%.2f".format(amount)}|$dayOfMonth|${record.currency}"
                    if (dismissedSuggestionKeys.contains(normalizedName)) return@forEach
                    if (!addedSuggestionKeys.add(suggestionKey)) return@forEach

                    suggestions.add(
                        RecurringBillSuggestion(
                            name = name,
                            amount = amount,
                            currency = record.currency,
                            dayOfMonth = dayOfMonth,
                            category = record.category,
                            monthsDetected = 2,
                            detectionType = "Payment"
                        )
                    )
                }

            suggestedBills.value = suggestions.sortedWith(
                compareBy<HomeViewModel.RecurringBillSuggestion> { it.dayOfMonth }
                    .thenBy { it.detectionType }
                    .thenBy { it.name.lowercase() }
            )
        }
    }

    private fun monthKey(calendar: Calendar): Int {
        return calendar.get(Calendar.YEAR) * 100 + calendar.get(Calendar.MONTH)
    }

    fun confirmBillSuggestion(suggestion: RecurringBillSuggestion, context: Context) {
        val bill = Bill(
            name = suggestion.name,
            amount = suggestion.amount,
            currency = suggestion.currency,
            dayOfMonth = suggestion.dayOfMonth,
            category = suggestion.category,
            userId = userId
        )
        addBill(bill, context)
        dismissBillSuggestion(suggestion)
        toastMessage.value = "\"${suggestion.name}\" added to bills"
    }

    fun dismissBillSuggestion(suggestion: RecurringBillSuggestion) {
        dismissedSuggestionKeys.add(suggestion.name.lowercase())
        suggestedBills.value = suggestedBills.value.filter {
            it.name.lowercase() !in dismissedSuggestionKeys
        }
    }

    private fun formatBalance(value: Double): String = String.format("%.2f", value)
}
