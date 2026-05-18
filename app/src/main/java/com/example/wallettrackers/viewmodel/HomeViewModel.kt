package com.example.wallettrackers.viewmodel

import android.net.Uri
import java.util.Locale
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.wallettrackers.util.DeviceSmsReader
import com.example.wallettrackers.util.SmsParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.AppNotification
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.CategoryRule
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.CustomSubCategory
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SavingsGoal
import com.example.wallettrackers.repository.WalletRepository
import com.example.wallettrackers.util.BillReminderManager
import com.example.wallettrackers.util.BudgetCalculator
import com.example.wallettrackers.util.FinancialCalculator
import com.example.wallettrackers.util.NotificationHelper
import com.example.wallettrackers.util.PdfReportGenerator
import com.example.wallettrackers.util.ReminderManager
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Date

class HomeViewModel(
    private val repository: WalletRepository,
    private val userId: String = ""
) : ViewModel() {

    data class BalanceUpdate(
        val account: Account,
        val oldBalance: Double,
        val newBalance: Double
    )

    data class MonthlyInsight(
        val topCategory: String = "",
        val topAmount: Double = 0.0,
        val currency: String = "EGP",
        val totalExpense: Double = 0.0
    )

    data class SpendingForecast(
        val spentSoFar: Double = 0.0,
        val projectedTotal: Double = 0.0,
        val dailyBurnRate: Double = 0.0,
        val daysRemaining: Int = 0,
        val currency: String = "EGP"
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
    val installments = mutableStateOf<List<FinancialCalculator.InstallmentSeries>>(emptyList())
    val monthlyInsight = mutableStateOf(MonthlyInsight())
    val toastMessage = mutableStateOf<String?>(null)
    val isLoading = mutableStateOf(true)
    val pendingBalanceUpdates = mutableStateOf<List<BalanceUpdate>>(emptyList())
    val spendingForecast = mutableStateOf(SpendingForecast())
    val unusualRecordIds = mutableStateOf<Set<String>>(emptySet())
    // currency code → EGP rate  e.g. "USD" → 48.5
    val fxRates = mutableStateOf<Map<String, Double>>(emptyMap())

    data class BudgetAlert(val category: String, val spent: Double, val limit: Double, val currency: String)
    val pendingBudgetAlert = mutableStateOf<BudgetAlert?>(null)
    fun onBudgetAlertSent() { pendingBudgetAlert.value = null }
    private val alertedBudgetKeys = mutableSetOf<String>() // avoid re-alerting same session

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

    // Custom subcategories
    val customSubCategories = mutableStateOf<List<CustomSubCategory>>(emptyList())

    // Notifications
    val notifications = mutableStateOf<List<AppNotification>>(emptyList())

    // Unlinked records — saved from SMS but no account was matched
    val unlinkedRecords = mutableStateOf<List<Record>>(emptyList())

    // Dashboard summary
    val spentToday = mutableStateOf(0.0)
    val remainingBudgetTotal = mutableStateOf(0.0)
    val upcomingBillsCount = mutableStateOf(0)

    // Spending insights
    data class SpendingInsight(val message: String, val icon: String, val percentChange: Int, val isPositive: Boolean)
    val spendingInsights = mutableStateOf<List<SpendingInsight>>(emptyList())

    // Budget streak
    val budgetStreak = mutableStateOf(0)

    // Daily spending for 30-day trend
    val dailySpendingLast30Days = mutableStateOf<List<Double>>(emptyList())

    fun onAddRecordAccountChange(account: Account) {
        Log.d("ViewModel", "onAddRecordAccountChange: selected account='${account.name}' id=${account.id}")
        addRecordSelectedAccount.value = account
    }

    fun onAddRecordAmountChange(newAmount: String) {
        Log.d("ViewModel", "onAddRecordAmountChange: amount='$newAmount'")
        addRecordAmount.value = newAmount
    }

    fun onAddRecordPayFromAccountChange(account: Account) {
        Log.d("ViewModel", "onAddRecordPayFromAccountChange: payFrom='${account.name}' id=${account.id}")
        addRecordPayFromAccount.value = account
    }

    fun clearAddRecordState() {
        Log.d("ViewModel", "clearAddRecordState: resetting all add-record fields")
        addRecordSelectedAccount.value = null
        addRecordAmount.value = ""
        addRecordPayFromAccount.value = null
    }

    fun startEditing(record: Record) {
        Log.d("ViewModel", "startEditing: record id=${record.id} category=${record.category} amount=${record.amount}")
        editingRecord.value = record
        showEditDialog.value = true
    }

    fun updateEditingCategory(category: String) {
        Log.d("ViewModel", "updateEditingCategory: newCategory='$category'")
        editingRecord.value = editingRecord.value?.copy(category = category)
    }

    fun updateEditingAmount(amount: String) {
        Log.d("ViewModel", "updateEditingAmount: newAmount='$amount'")
        editingRecord.value = editingRecord.value?.copy(amount = amount)
    }

    fun updateEditingAccount(account: Account) {
        Log.d("ViewModel", "updateEditingAccount: newAccount='${account.name}' id=${account.id}")
        editingRecord.value = editingRecord.value?.copy(
            accountId = account.id,
            accountName = account.name,
            currency = account.currency
        )
    }

    fun updateEditingComment(comment: String) {
        Log.d("ViewModel", "updateEditingComment: comment='${comment.take(50)}'")
        editingRecord.value = editingRecord.value?.copy(comment = comment)
    }

    fun stopEditing() {
        Log.d("ViewModel", "stopEditing: closing edit dialog")
        editingRecord.value = null
        showEditDialog.value = false
    }

    fun saveEditedRecord(category: String? = null) {
        Log.d("ViewModel", "saveEditedRecord START: overrideCategory=${category ?: "none"}, editingRecord.id=${editingRecord.value?.id}")
        viewModelScope.launch {
            try {
                val recordToSave = if (category != null) {
                    Log.d("ViewModel", "saveEditedRecord: applying category override '$category'")
                    editingRecord.value?.copy(category = category)
                } else {
                    Log.d("ViewModel", "saveEditedRecord: using existing category '${editingRecord.value?.category}'")
                    editingRecord.value
                }
                recordToSave?.let { updateRecord(it) }
                Log.d("ViewModel", "saveEditedRecord END: success")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error saving edited record", e)
                toastMessage.value = "Update failed: ${e.message}"
            } finally {
                stopEditing()
            }
        }
    }

    init {
        Log.d("ViewModel", "init START: userId='$userId' — loading all data streams")
        loadAccounts()
        loadRecords()
        loadStatements()
        loadBudgets()
        loadSavingsGoals()
        loadDebts()
        loadBills()
        loadCategoryRules()
        loadCustomSubCategories()
        loadNotifications()
        Log.d("ViewModel", "init END: all data loaders launched")
    }

    private fun loadCategoryRules() {
        viewModelScope.launch {
            repository.getCategoryRules().catch { Log.e("ViewModel", "loadCategoryRules error", it) }.collect {
                Log.d("ViewModel", "loadCategoryRules: received ${it.size} rules")
                categoryRules.value = it
            }
        }
    }

    private fun loadCustomSubCategories() {
        viewModelScope.launch {
            repository.getCustomSubCategories().catch { Log.e("ViewModel", "loadCustomSubCategories error", it) }.collect {
                Log.d("ViewModel", "loadCustomSubCategories: received ${it.size} custom subcategories")
                customSubCategories.value = it
            }
        }
    }

    private fun loadNotifications() {
        viewModelScope.launch {
            repository.getNotifications().catch { Log.e("ViewModel", "loadNotifications error", it) }.collect {
                Log.d("ViewModel", "loadNotifications: received ${it.size} notifications")
                notifications.value = it
            }
        }
    }

    fun clearNotifications() {
        Log.d("ViewModel", "clearNotifications: clearing all notification history")
        viewModelScope.launch { repository.clearNotifications() }
    }

    fun addCustomSubCategory(parentCategory: String, name: String) {
        Log.d("ViewModel", "addCustomSubCategory: parent='$parentCategory' name='${name.trim()}'")
        viewModelScope.launch {
            repository.addCustomSubCategory(CustomSubCategory(parentCategory = parentCategory, name = name.trim()))
        }
    }

    fun deleteCustomSubCategory(id: String) {
        Log.d("ViewModel", "deleteCustomSubCategory: id=$id")
        viewModelScope.launch { repository.deleteCustomSubCategory(id) }
    }

    /** Returns built-in subcategory names + user-created ones for the given parent category. */
    fun customSubCategoryNamesFor(parentCategory: String): List<String> {
        return customSubCategories.value
            .filter { it.parentCategory == parentCategory }
            .map { it.name }
    }

    /** All category names (built-in + custom) for use in dropdowns. */
    fun allCategoryNames(): List<String> {
        val builtIn = Categories.list.flatMap { c -> (c.subCategories + c).map { it.name } }
        val custom = customSubCategories.value.map { it.name }
        return (builtIn + custom).distinct()
    }

    fun startPendingRule(record: Record) {
        pendingRuleRecord.value = record
    }

    fun clearPendingRule() {
        pendingRuleRecord.value = null
    }

    fun saveRuleAndResync(category: String) {
        Log.d("ViewModel", "saveRuleAndResync START: category='$category'")
        val record = pendingRuleRecord.value ?: run {
            Log.d("ViewModel", "saveRuleAndResync: no pending rule record, returning")
            return
        }
        val merchant = record.comment
            .removePrefix("To: ")
            .removePrefix("From: ")
            .trim()
        if (merchant.isBlank() || merchant.all { it.isDigit() }) {
            Log.d("ViewModel", "saveRuleAndResync: merchant='$merchant' is blank or numeric, skipping")
            clearPendingRule()
            return
        }
        Log.d("ViewModel", "saveRuleAndResync: creating rule for merchant='$merchant' → category='$category'")
        viewModelScope.launch {
            repository.addCategoryRule(CategoryRule(merchantKeyword = merchant, category = category, userId = userId))
            var updated = 0
            records.value.forEach { r ->
                if (r.comment.contains(merchant, ignoreCase = true) && r.category != category) {
                    repository.updateRecord(r.copy(category = category))
                    updated++
                }
            }
            Log.d("ViewModel", "saveRuleAndResync END: updated $updated existing records")
            toastMessage.value = if (updated > 0)
                "Rule saved — updated $updated record${if (updated > 1) "s" else ""}"
            else "Rule saved for \"$merchant\""
            clearPendingRule()
        }
    }

    fun deleteRule(ruleId: String) {
        Log.d("ViewModel", "deleteRule: ruleId=$ruleId")
        viewModelScope.launch {
            repository.deleteCategoryRule(ruleId)
            toastMessage.value = "Rule deleted"
        }
    }

    fun linkRecordToAccount(record: Record, account: Account) {
        Log.d("ViewModel", "linkRecordToAccount START: record.id=${record.id} amount=${record.amount} type=${record.type} → account='${account.name}'")
        viewModelScope.launch {
            val amount = record.amount.toDoubleOrNull() ?: 0.0
            val currentBal = account.amount.toDoubleOrNull() ?: 0.0
            val newBal = if (record.type == "Income") currentBal + amount else currentBal - amount
            Log.d("ViewModel", "linkRecordToAccount: currentBal=$currentBal → newBal=$newBal")
            repository.batchUpdateAccountAndRecord(
                account.copy(amount = newBal.toString()),
                record.copy(
                    accountId = account.id,
                    accountName = account.name,
                    balanceAfter = "%.2f".format(newBal)
                )
            )
            Log.d("ViewModel", "linkRecordToAccount END: linked to '${account.name}'")
            toastMessage.value = "Linked to ${account.name}"
        }
    }

    fun refresh() {
        Log.d("ViewModel", "refresh: reloading all data streams")
        isLoading.value = true
        loadAccounts()
        loadRecords()
        loadBudgets()
        loadSavingsGoals()
        loadDebts()
        loadBills()
    }

    fun syncBalancesFromSms(context: Context) {
        Log.d("ViewModel", "syncBalancesFromSms START: reading SMS from device")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allSms = DeviceSmsReader.readAll(context)
                    .filter { SmsParser.isBankSms(it.body) }
                    .sortedByDescending { it.date }
                Log.d("ViewModel", "syncBalancesFromSms: found ${allSms.size} bank SMS messages")

                val eligibleAccounts = accounts.value.filter { !it.isArchived && it.last4Digits.isNotBlank() }
                Log.d("ViewModel", "syncBalancesFromSms: checking ${eligibleAccounts.size} eligible accounts")

                val proposals = eligibleAccounts.mapNotNull { account ->
                        val sms = allSms.firstOrNull { s ->
                            SmsParser.extractLast4Digits(s.body) == account.last4Digits &&
                                SmsParser.extractBalanceFromSms(s.body) != null
                        } ?: return@mapNotNull null
                        val newBalance = SmsParser.extractBalanceFromSms(sms.body) ?: return@mapNotNull null
                        val oldBalance = account.amount.toDoubleOrNull() ?: 0.0
                        if (Math.abs(newBalance - oldBalance) < 0.001) return@mapNotNull null
                        Log.d("ViewModel", "syncBalancesFromSms: proposal for '${account.name}': $oldBalance → $newBalance")
                        BalanceUpdate(account, oldBalance, newBalance)
                    }

                if (proposals.isEmpty()) {
                    Log.d("ViewModel", "syncBalancesFromSms END: no balance changes detected")
                    toastMessage.value = "Balances already up to date"
                } else {
                    Log.d("ViewModel", "syncBalancesFromSms END: ${proposals.size} balance updates proposed")
                    pendingBalanceUpdates.value = proposals
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "syncBalancesFromSms failed", e)
                toastMessage.value = "Could not read SMS"
            }
        }
    }

    fun confirmBalanceUpdates(selected: Set<String>) {
        Log.d("ViewModel", "confirmBalanceUpdates START: ${selected.size} accounts selected")
        viewModelScope.launch(Dispatchers.IO) {
            val updates = pendingBalanceUpdates.value.filter { it.account.id in selected }
            updates.forEach { update ->
                Log.d("ViewModel", "confirmBalanceUpdates: updating '${update.account.name}' balance to ${update.newBalance}")
                repository.updateAccount(update.account.copy(amount = "%.2f".format(update.newBalance)))
            }
            pendingBalanceUpdates.value = emptyList()
            Log.d("ViewModel", "confirmBalanceUpdates END: updated ${updates.size} accounts")
            toastMessage.value = "Updated ${updates.size} account${if (updates.size > 1) "s" else ""}"
        }
    }

    fun dismissBalanceUpdates() {
        Log.d("ViewModel", "dismissBalanceUpdates: user cancelled balance updates")
        pendingBalanceUpdates.value = emptyList()
        toastMessage.value = "Update cancelled"
    }

    private fun loadAccounts() {
        Log.d("ViewModel", "loadAccounts: subscribing to accounts collection")
        viewModelScope.launch {
            repository.getAccounts()
                .catch { error ->
                    Log.e("HomeViewModel", "Error loading accounts", error)
                    toastMessage.value = error.message
                    isLoading.value = false
                }
                .collect { accountList ->
                    Log.d("ViewModel", "loadAccounts: received ${accountList.size} accounts")
                    accounts.value = accountList
                    isLoading.value = false
                    updateWidgetData()
                }
        }
    }

    private fun loadRecords() {
        Log.d("ViewModel", "loadRecords: subscribing to records collection")
        viewModelScope.launch {
            repository.getRecords()
                .catch { error ->
                    Log.e("HomeViewModel", "Error loading records", error)
                    toastMessage.value = error.message
                }
                .collect { recordList ->
                    Log.d("ViewModel", "loadRecords: received ${recordList.size} records, sorting and analyzing")
                    val sorted = recordList.sortedByDescending { it.timestamp }
                    records.value = sorted
                    val unlinked = sorted.filter { it.accountId.isEmpty() }
                    unlinkedRecords.value = unlinked
                    Log.d("ViewModel", "loadRecords: ${unlinked.size} unlinked records found")
                    installments.value = FinancialCalculator.detectInstallments(sorted)
                    updateInsights(sorted)
                    checkAllBudgets(sorted)
                }
        }
    }

    private fun loadBudgets() {
        Log.d("ViewModel", "loadBudgets: subscribing to budgets collection")
        viewModelScope.launch {
            repository.getBudgets().catch { Log.e("ViewModel", "loadBudgets error", it) }.collect {
                Log.d("ViewModel", "loadBudgets: received ${it.size} budgets")
                budgets.value = it
                updateRemainingBudget()
                checkAllBudgets(records.value)
            }
        }
    }

    private fun loadSavingsGoals() {
        viewModelScope.launch {
            repository.getSavingsGoals().catch { Log.e("ViewModel", "loadSavingsGoals error", it) }.collect {
                Log.d("ViewModel", "loadSavingsGoals: received ${it.size} goals")
                savingsGoals.value = it
            }
        }
    }

    private fun loadDebts() {
        viewModelScope.launch {
            repository.getDebts().catch { Log.e("ViewModel", "loadDebts error", it) }.collect {
                Log.d("ViewModel", "loadDebts: received ${it.size} debts")
                debts.value = it
            }
        }
    }

    private fun loadBills() {
        viewModelScope.launch {
            repository.getBills().catch { Log.e("ViewModel", "loadBills error", it) }.collect {
                Log.d("ViewModel", "loadBills: received ${it.size} bills")
                bills.value = it
                updateUpcomingBillsCount()
            }
        }
    }

    private fun updateRemainingBudget(recordList: List<Record> = records.value) {
        val cal = Calendar.getInstance()
        val thisMonth = cal.get(Calendar.MONTH)
        val thisYear = cal.get(Calendar.YEAR)
        val subcategoryMap = Categories.list.associate { cat ->
            cat.name to (cat.subCategories.map { it.name } +
                customSubCategories.value.filter { it.parentCategory == cat.name }.map { it.name })
        }
        remainingBudgetTotal.value = budgets.value.sumOf { budget ->
            val spent = BudgetCalculator.spentInMonth(recordList, budget.category, thisMonth, thisYear, subcategoryMap)
            (budget.monthlyLimit - spent).coerceAtLeast(0.0)
        }
    }

    private fun updateUpcomingBillsCount() {
        val todayCal = Calendar.getInstance()
        val currentDay = todayCal.get(Calendar.DAY_OF_MONTH)
        upcomingBillsCount.value = bills.value.count { bill ->
            if (!bill.isActive) return@count false
            val daysUntil = if (bill.dayOfMonth >= currentDay) bill.dayOfMonth - currentDay
                           else (todayCal.getActualMaximum(Calendar.DAY_OF_MONTH) - currentDay + bill.dayOfMonth)
            daysUntil in 0..7
        }
    }

    private fun updateInsights(recordList: List<Record>) {
        val cal = Calendar.getInstance()
        val thisMonth = cal.get(Calendar.MONTH)
        val thisYear = cal.get(Calendar.YEAR)
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)

        val thisMonthExpenses = recordList.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == thisMonth && rc.get(Calendar.YEAR) == thisYear &&
            r.type == "Expense" && !FinancialCalculator.isExcludedFromSpending(r)
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

        val dailyBurn = if (dayOfMonth > 0) totalExpense / dayOfMonth else 0.0
        val projected = dailyBurn * daysInMonth
        val currency = thisMonthExpenses.firstOrNull()?.currency ?: "EGP"
        spendingForecast.value = SpendingForecast(
            spentSoFar = totalExpense,
            projectedTotal = projected,
            dailyBurnRate = dailyBurn,
            daysRemaining = daysInMonth - dayOfMonth,
            currency = currency
        )

        // Spent today
        val todayCal = Calendar.getInstance()
        val todayDay = todayCal.get(Calendar.DAY_OF_MONTH)
        val todayMonth = todayCal.get(Calendar.MONTH)
        val todayYear = todayCal.get(Calendar.YEAR)
        spentToday.value = recordList.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.DAY_OF_MONTH) == todayDay && rc.get(Calendar.MONTH) == todayMonth &&
            rc.get(Calendar.YEAR) == todayYear && r.type == "Expense"
        }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }

        // Remaining budget (total limits - spent per category this month)
        updateRemainingBudget(recordList)

        // Upcoming bills (due within 7 days)
        updateUpcomingBillsCount()

        // Unusual transaction detection — flag records >2× their category average (min 3 samples)
        val expenseRecords = recordList.filter { it.type == "Expense" }
        val categoryAverages = expenseRecords
            .groupBy { it.category }
            .filter { it.value.size >= 3 }
            .mapValues { (_, rs) -> rs.map { it.amount.toDoubleOrNull() ?: 0.0 }.average() }
        unusualRecordIds.value = expenseRecords
            .filter { r ->
                val avg = categoryAverages[r.category] ?: return@filter false
                val amt = r.amount.toDoubleOrNull() ?: 0.0
                amt > avg * 2.0
            }
            .map { it.id }
            .toSet()

        // Month-over-month spending insights
        val lastMonthCal = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }
        val lastMonth = lastMonthCal.get(Calendar.MONTH)
        val lastYear = lastMonthCal.get(Calendar.YEAR)
        val lastMonthExpenses = recordList.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == lastMonth && rc.get(Calendar.YEAR) == lastYear &&
            r.type == "Expense" && !FinancialCalculator.isExcludedFromSpending(r)
        }
        val thisMonthByCategory = thisMonthExpenses.groupBy { it.category }
            .mapValues { (_, rs) -> rs.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
        val lastMonthByCategory = lastMonthExpenses.groupBy { it.category }
            .mapValues { (_, rs) -> rs.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }

        val insights = mutableListOf<SpendingInsight>()
        thisMonthByCategory.forEach { (category, thisAmt) ->
            val lastAmt = lastMonthByCategory[category] ?: return@forEach
            if (lastAmt < 50) return@forEach // skip tiny categories
            val pctChange = (((thisAmt - lastAmt) / lastAmt) * 100).toInt()
            if (pctChange > 20) {
                insights.add(SpendingInsight(
                    message = "You spent ${pctChange}% more on $category this month",
                    icon = "trending_up", percentChange = pctChange, isPositive = false
                ))
            } else if (pctChange < -20) {
                insights.add(SpendingInsight(
                    message = "You saved ${-pctChange}% on $category this month",
                    icon = "trending_down", percentChange = pctChange, isPositive = true
                ))
            }
        }
        // Total comparison
        val lastMonthTotal = lastMonthExpenses.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        if (lastMonthTotal > 100 && totalExpense > 0) {
            val totalPct = (((totalExpense - lastMonthTotal) / lastMonthTotal) * 100).toInt()
            if (totalPct > 10 || totalPct < -10) {
                insights.add(0, SpendingInsight(
                    message = if (totalPct > 0) "Total spending up ${totalPct}% vs last month"
                             else "Total spending down ${-totalPct}% vs last month",
                    icon = if (totalPct > 0) "warning" else "celebration",
                    percentChange = totalPct, isPositive = totalPct < 0
                ))
            }
        }
        spendingInsights.value = insights.take(5)

        // Budget streak — count consecutive days where spending was under daily budget average
        val totalBudgetLimit = budgets.value.sumOf { it.monthlyLimit }
        if (totalBudgetLimit > 0) {
            val dailyBudget = totalBudgetLimit / 30.0
            var streak = 0
            val cal2 = Calendar.getInstance()
            for (daysBack in 0..30) {
                val checkDay = cal2.get(Calendar.DAY_OF_MONTH)
                val checkMonth = cal2.get(Calendar.MONTH)
                val checkYear = cal2.get(Calendar.YEAR)
                val daySpend = recordList.filter { r ->
                    val rc = Calendar.getInstance().apply { time = r.timestamp }
                    rc.get(Calendar.DAY_OF_MONTH) == checkDay && rc.get(Calendar.MONTH) == checkMonth &&
                    rc.get(Calendar.YEAR) == checkYear && r.type == "Expense"
                }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
                if (daySpend <= dailyBudget) streak++ else break
                cal2.add(Calendar.DAY_OF_MONTH, -1)
            }
            budgetStreak.value = streak
        }

        // 30-day spending trend
        val trend = mutableListOf<Double>()
        val trendCal = Calendar.getInstance()
        for (i in 29 downTo 0) {
            trendCal.time = Date()
            trendCal.add(Calendar.DAY_OF_MONTH, -i)
            val d = trendCal.get(Calendar.DAY_OF_MONTH)
            val m = trendCal.get(Calendar.MONTH)
            val yr = trendCal.get(Calendar.YEAR)
            val dayTotal = recordList.filter { r ->
                val rc = Calendar.getInstance().apply { time = r.timestamp }
                rc.get(Calendar.DAY_OF_MONTH) == d && rc.get(Calendar.MONTH) == m &&
                rc.get(Calendar.YEAR) == yr && r.type == "Expense"
            }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
            trend.add(dayTotal)
        }
        dailySpendingLast30Days.value = trend
    }

    fun currentMonthSpendForCategory(category: String): Double {
        val cal = Calendar.getInstance()
        val subcategoryMap = Categories.list.associate { cat ->
            cat.name to (cat.subCategories.map { it.name } +
                customSubCategories.value.filter { it.parentCategory == cat.name }.map { it.name })
        }
        return BudgetCalculator.spentInMonth(
            records.value,
            category,
            cal.get(Calendar.MONTH),
            cal.get(Calendar.YEAR),
            subcategoryMap
        )
    }

    private fun loadStatements() {
        Log.d("ViewModel", "loadStatements: subscribing to credit statements collection")
        viewModelScope.launch {
            repository.getCreditStatements()
                .catch { error ->
                    Log.e("HomeViewModel", "Error loading statements", error)
                }
                .collect { statementList ->
                    Log.d("ViewModel", "loadStatements: received ${statementList.size} credit statements")
                    statements.value = statementList
                }
        }
    }

    fun addAccount(account: Account) {
        Log.d("ViewModel", "addAccount START: name='${account.name}' type=${account.accountType} currency=${account.currency}")
        viewModelScope.launch {
            try {
                repository.addAccount(account.copy(userId = userId))
                Log.d("ViewModel", "addAccount END: success")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding account", e)
                toastMessage.value = e.message
            }
        }
    }

    fun updateAccount(account: Account) {
        Log.d("ViewModel", "updateAccount START: id=${account.id} name='${account.name}' amount=${account.amount}")
        viewModelScope.launch {
            try {
                repository.updateAccount(account)
                Log.d("ViewModel", "updateAccount END: success")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error updating account", e)
                toastMessage.value = e.message
            }
        }
    }

    fun deleteAccount(accountId: String) {
        Log.d("ViewModel", "deleteAccount START: accountId=$accountId")
        viewModelScope.launch {
            try {
                repository.deleteAccount(accountId)
                Log.d("ViewModel", "deleteAccount END: success")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error deleting account", e)
                toastMessage.value = e.message
            }
        }
    }

    fun addSplitRecords(records: List<Record>) {
        Log.d("ViewModel", "addSplitRecords START: ${records.size} split records")
        viewModelScope.launch {
            try {
                if (records.isEmpty()) { Log.d("ViewModel", "addSplitRecords: empty list, returning"); return@launch }
                val accountId = records.first().accountId
                val account = accounts.value.find { it.id == accountId } ?: run {
                    Log.d("ViewModel", "addSplitRecords: account not found for id=$accountId"); return@launch
                }
                val totalAmount = records.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
                val newBalance = (account.amount.toDoubleOrNull() ?: 0.0) - totalAmount
                val formattedBal = formatBalance(newBalance)
                Log.d("ViewModel", "addSplitRecords: totalAmount=$totalAmount, newBalance=$formattedBal for '${account.name}'")
                repository.updateAccount(account.copy(amount = formattedBal))
                records.forEachIndexed { i, record ->
                    Log.d("ViewModel", "addSplitRecords: adding split ${i+1}/${records.size} category=${record.category} amount=${record.amount}")
                    repository.addRecord(record.copy(
                        userId = userId,
                        type = "Expense",
                        balanceAfter = if (i == records.size - 1) formattedBal else ""
                    ))
                }
                Log.d("ViewModel", "addSplitRecords END: success")
                toastMessage.value = "Split recorded across ${records.size} categories"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding split records", e)
                toastMessage.value = e.message
            }
        }
    }

    fun addRecord(record: Record) {
        Log.d("BudgetDebug", "addRecord CALLED: category=${record.category} amount=${record.amount} type=${record.type}")
        viewModelScope.launch {
            try {
                if (record.category == "Credit") {
                    Log.d("BudgetDebug", "addRecord → Credit path, skipping budget check")
                    val debitAccount = addRecordPayFromAccount.value
                    if (debitAccount == null) {
                        toastMessage.value = "Please select the account to pay from"
                        return@launch
                    }
                    handleManualCreditPayment(record, debitAccount)
                } else {
                    Log.d("BudgetDebug", "addRecord → handleNormalRecord path")
                    handleNormalRecord(record)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding record", e)
                toastMessage.value = e.message
            }
        }
    }

    private suspend fun handleManualCreditPayment(record: Record, debitAccount: Account) {
        Log.d("ViewModel", "handleManualCreditPayment START: amount=${record.amount} from='${debitAccount.name}' to creditAccountId=${record.accountId}")
        val creditAccount = accounts.value.find { it.id == record.accountId } ?: run {
            Log.d("ViewModel", "handleManualCreditPayment: credit card account NOT FOUND")
            toastMessage.value = "Credit card account not found"
            return
        }
        val paymentAmount = record.amount.toDoubleOrNull() ?: 0.0

        val newDebitBal = (debitAccount.amount.toDoubleOrNull() ?: 0.0) - paymentAmount
        val newCreditBal = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmount
        Log.d("ViewModel", "handleManualCreditPayment: debitBal→$newDebitBal, creditBal→$newCreditBal")

        // Mark any matching unpaid statement as paid
        val digits = creditAccount.last4Digits.filter { it.isDigit() }
        val unpaidStatement = statements.value.find { it.cardLast4Digits == digits && !it.isPaid }
        if (unpaidStatement != null) {
            Log.d("ViewModel", "handleManualCreditPayment: marking statement ${unpaidStatement.id} as paid (deleting)")
            repository.deleteCreditStatement(unpaidStatement.id)
        } else {
            Log.d("ViewModel", "handleManualCreditPayment: no unpaid statement found for digits=$digits")
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
        Log.d("ViewModel", "payCreditStatement START: statementId=${statement.id} amount=${statement.totalAmount} from='${debitAccount.name}'")
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

                Log.d("ViewModel", "payCreditStatement END: success")
                toastMessage.value = "Card paid and removed successfully"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error paying statement", e)
                toastMessage.value = "Failed to process payment: ${e.message}"
            }
        }
    }

    private var appContext: Context? = null

    fun setContext(context: Context) {
        appContext = context.applicationContext
        Log.d("ViewModel", "setContext: appContext is now SET")
    }

    private suspend fun handleNormalRecord(record: Record) {
        val account = accounts.value.find { it.id == record.accountId }
        Log.d("BudgetDebug", "handleNormalRecord: category=${record.category} amount=${record.amount} accountFound=${account != null}")
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

            Log.d("BudgetDebug", "isIncome=$isIncome → will checkBudgetAlert=${!isIncome}")
            if (!isIncome) checkBudgetAlert(record.category, amountDouble)
        }
    }

    private fun checkAllBudgets(recordList: List<Record>) {
        val budgetList = budgets.value
        if (budgetList.isEmpty()) {
            Log.d("BudgetDebug", "checkAllBudgets: no budgets configured")
            return
        }
        Log.d("BudgetDebug", "checkAllBudgets: checking ${budgetList.size} budgets against ${recordList.size} records")

        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val subcategoryMap = Categories.list.associate { cat ->
            cat.name to (cat.subCategories.map { it.name } +
                customSubCategories.value.filter { it.parentCategory == cat.name }.map { it.name })
        }

        for (budget in budgetList) {
            if (budget.monthlyLimit <= 0) continue
            val spent = BudgetCalculator.spentInMonth(recordList, budget.category, month, year, subcategoryMap)
            val pct = spent / budget.monthlyLimit
            Log.d("BudgetDebug", "checkAllBudgets: category=${budget.category} spent=$spent limit=${budget.monthlyLimit} pct=${"%.1f".format(pct * 100)}%")

            if (pct < 0.75) continue

            val alertKey = "${budget.id}_${if (pct >= 1.0) "over" else "warn"}"
            val isNewAlert = alertedBudgetKeys.add(alertKey)
            Log.d("BudgetDebug", "checkAllBudgets: alertKey=$alertKey isNewAlert=$isNewAlert")

            if (isNewAlert) {
                Log.d("BudgetDebug", "checkAllBudgets: FIRING ALERT for ${budget.category}")
                pendingBudgetAlert.value = BudgetAlert(budget.category, spent, budget.monthlyLimit, budget.currency)
                val ctx = appContext
                if (ctx != null) {
                    val prefs = ctx.getSharedPreferences("notification_prefs", android.content.Context.MODE_PRIVATE)
                    if (prefs.getBoolean("budget_alerts", true)) {
                        NotificationHelper.createChannels(ctx)
                        NotificationHelper.sendBudgetAlert(ctx, budget.category, spent, budget.monthlyLimit, budget.currency)
                        Log.d("BudgetDebug", "checkAllBudgets: push notification sent")
                    }
                } else {
                    Log.d("BudgetDebug", "checkAllBudgets: appContext NULL → push skipped")
                }
                // Log to Firestore notification history
                val pctInt = (pct * 100).toInt()
                val title = if (pct >= 1.0) "Budget Exceeded" else "Budget Warning"
                val msg = "${budget.category}: ${pctInt}% used (${budget.currency} ${"%.0f".format(spent)} / ${"%.0f".format(budget.monthlyLimit)})"
                viewModelScope.launch {
                    repository.addNotification(AppNotification(title = title, message = msg, type = "budget_alert"))
                }
            }
        }
    }

    private fun checkBudgetAlert(category: String, addedAmount: Double) {
        Log.d("BudgetDebug", "checkBudgetAlert: category=$category addedAmount=$addedAmount")
        Log.d("BudgetDebug", "budgets count=${budgets.value.size} budgetCategories=${budgets.value.map { it.category }}")
        Log.d("BudgetDebug", "appContext=${if (appContext != null) "SET" else "NULL"}")

        val matchingBudget = budgets.value.find { b ->
            b.category == category ||
            Categories.list.find { it.name == b.category }?.subCategories?.any { it.name == category } == true
        }
        if (matchingBudget == null) {
            Log.d("BudgetDebug", "NO matching budget found for category=$category → skipping")
            return
        }
        Log.d("BudgetDebug", "matchingBudget: category=${matchingBudget.category} limit=${matchingBudget.monthlyLimit} currency=${matchingBudget.currency}")

        if (matchingBudget.monthlyLimit <= 0) {
            Log.d("BudgetDebug", "limit is 0 or negative → skipping")
            return
        }

        val spentSoFar = currentMonthSpendForCategory(matchingBudget.category)
        val spent = spentSoFar + addedAmount
        val pct = spent / matchingBudget.monthlyLimit
        Log.d("BudgetDebug", "spentSoFar=$spentSoFar addedAmount=$addedAmount totalSpent=$spent limit=${matchingBudget.monthlyLimit} pct=${"%.1f".format(pct * 100)}%")

        if (pct < 0.75) {
            Log.d("BudgetDebug", "pct < 75% → no alert needed")
            return
        }

        val alertKey = "${matchingBudget.id}_${if (pct >= 1.0) "over" else "warn"}"
        val isNewAlert = alertedBudgetKeys.add(alertKey)
        Log.d("BudgetDebug", "alertKey=$alertKey isNewAlert=$isNewAlert alertedKeys=$alertedBudgetKeys")

        if (isNewAlert) {
            Log.d("BudgetDebug", "FIRING ALERT: in-app dialog + push notification")
            pendingBudgetAlert.value = BudgetAlert(
                matchingBudget.category, spent, matchingBudget.monthlyLimit, matchingBudget.currency
            )
            val ctx = appContext
            if (ctx != null) {
                val prefs = ctx.getSharedPreferences("notification_prefs", android.content.Context.MODE_PRIVATE)
                if (prefs.getBoolean("budget_alerts", true)) {
                    NotificationHelper.createChannels(ctx)
                    NotificationHelper.sendBudgetAlert(ctx, matchingBudget.category, spent, matchingBudget.monthlyLimit, matchingBudget.currency)
                    Log.d("BudgetDebug", "push notification sent")
                }
            } else {
                Log.d("BudgetDebug", "appContext is NULL → push notification skipped")
            }
        } else {
            Log.d("BudgetDebug", "alert already sent this session → skipped")
        }
    }

    // ── #7: Recompute balanceAfter for all records of an account from opening balance ──
    private suspend fun recalculateBalancesForAccount(accountId: String) {
        val account = accounts.value.find { it.id == accountId } ?: return
        val accountRecords = records.value
            .filter { it.accountId == accountId && !it.accountName.contains("->") }
            .sortedBy { it.timestamp }
        if (accountRecords.isEmpty()) return
        // Derive opening balance by reversing all records from the current account balance
        val currentBal = account.amount.toDoubleOrNull() ?: 0.0
        val netEffect = accountRecords.sumOf { r ->
            val a = r.amount.toDoubleOrNull() ?: 0.0
            if (r.type == "Income") a else -a
        }
        var running = currentBal - netEffect
        val updated = accountRecords.map { r ->
            val a = r.amount.toDoubleOrNull() ?: 0.0
            running = if (r.type == "Income") running + a else running - a
            r.copy(balanceAfter = formatBalance(running))
        }
        repository.batchUpdateRecords(updated)
    }

    fun recalculateAllBalances() {
        Log.d("ViewModel", "recalculateAllBalances START")
        viewModelScope.launch {
            try {
                val activeAccounts = accounts.value.filter { !it.isArchived }
                Log.d("ViewModel", "recalculateAllBalances: processing ${activeAccounts.size} active accounts")
                activeAccounts.forEach { recalculateBalancesForAccount(it.id) }
                Log.d("ViewModel", "recalculateAllBalances END: success")
                toastMessage.value = "All balances recalculated"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error recalculating balances", e)
                toastMessage.value = "Recalculation failed: ${e.message}"
            }
        }
    }

    fun updateRecord(record: Record) {
        Log.d("ViewModel", "updateRecord START: id=${record.id} category=${record.category} amount=${record.amount} account='${record.accountName}'")
        viewModelScope.launch {
            try {
                val original = records.value.find { it.id == record.id }
                Log.d("ViewModel", "updateRecord: original found=${original != null}, originalCategory=${original?.category}, originalAmount=${original?.amount}")

                // #3: Transfer record — reverse old effect, apply new effect on both accounts
                if (original != null && original.accountName.contains("->")) {
                    Log.d("ViewModel", "updateRecord: TRANSFER record path")
                    val origParts = original.accountName.split("->")
                    val origSource = accounts.value.find { it.name == origParts.getOrNull(0)?.trim() }
                    val origDest   = accounts.value.find { it.name == origParts.getOrNull(1)?.trim() }
                    val origAmt    = original.amount.toDoubleOrNull() ?: 0.0
                    val newAmt     = record.amount.toDoubleOrNull() ?: 0.0
                    if (origAmt != newAmt || original.accountName != record.accountName) {
                        val updated = mutableListOf<Account>()
                        if (origSource != null) updated.add(origSource.copy(amount = formatBalance((origSource.amount.toDoubleOrNull() ?: 0.0) + origAmt - newAmt)))
                        if (origDest != null)   updated.add(origDest.copy(amount = formatBalance((origDest.amount.toDoubleOrNull() ?: 0.0) - origAmt + newAmt)))
                        if (updated.isNotEmpty()) repository.batchUpdateMultipleAccountsAndRecord(updated, record)
                        else repository.updateRecord(record)
                    } else {
                        repository.updateRecord(record)
                    }
                    toastMessage.value = "Record updated"
                    return@launch
                }

                if (original == null) {
                    Log.d("ViewModel", "updateRecord: no original found, simple update")
                    repository.updateRecord(record)
                    toastMessage.value = "Record updated"
                    return@launch
                }

                val origAmount  = original.amount.toDoubleOrNull() ?: 0.0
                val newAmount   = record.amount.toDoubleOrNull() ?: 0.0
                val isOrigIncome = original.type == "Income"
                val isNewIncome  = record.type == "Income"

                if (original.accountId == record.accountId) {
                    Log.d("ViewModel", "updateRecord: same account path, recalculating balance")
                    val account = accounts.value.find { it.id == record.accountId }
                    if (account != null) {
                        val cur      = account.amount.toDoubleOrNull() ?: 0.0
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
                    Log.d("ViewModel", "updateRecord: account CHANGED from ${original.accountId} to ${record.accountId}")
                    val oldAcc = accounts.value.find { it.id == original.accountId }
                    val newAcc = accounts.value.find { it.id == record.accountId }
                    if (oldAcc != null && newAcc != null) {
                        val restoredOld = (oldAcc.amount.toDoubleOrNull() ?: 0.0).let { if (isOrigIncome) it - origAmount else it + origAmount }
                        val updatedNew  = (newAcc.amount.toDoubleOrNull() ?: 0.0).let { if (isNewIncome) it + newAmount else it - newAmount }
                        repository.batchUpdateTwoAccountsAndRecord(
                            oldAcc.copy(amount = formatBalance(restoredOld)),
                            newAcc.copy(amount = formatBalance(updatedNew)),
                            record.copy(balanceAfter = formatBalance(updatedNew))
                        )
                        recalculateBalancesForAccount(original.accountId) // #1 cascade on old account
                    } else {
                        repository.updateRecord(record)
                    }
                }

                recalculateBalancesForAccount(record.accountId) // #1 cascade on current account

                // #8: Auto-create category rule when user corrects a category on an SMS-linked record
                if (original.category != record.category && !record.smsId.isNullOrBlank()) {
                    Log.d("ViewModel", "updateRecord: category changed on SMS record, checking for auto-rule creation")
                    val merchant = record.comment.trim().removePrefix("To: ").removePrefix("From: ").trim()
                    if (merchant.length >= 3 && !merchant.all { it.isDigit() }) {
                        repository.addCategoryRule(CategoryRule(merchantKeyword = merchant, category = record.category, userId = userId))
                        toastMessage.value = "Rule created: \"$merchant\" → ${record.category}"
                        return@launch
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
        Log.d("ViewModel", "deleteRecord START: recordId=$recordId")
        viewModelScope.launch {
            try {
                val record = records.value.find { it.id == recordId }
                Log.d("ViewModel", "deleteRecord: found=${record != null} category=${record?.category} amount=${record?.amount}")

                // #2: Transfer deletion — reverse both accounts
                if (record != null && record.accountName.contains("->")) {
                    Log.d("ViewModel", "deleteRecord: TRANSFER deletion path")
                    val parts  = record.accountName.split("->")
                    val source = accounts.value.find { it.name == parts.getOrNull(0)?.trim() }
                    val dest   = accounts.value.find { it.name == parts.getOrNull(1)?.trim() }
                    val amount = record.amount.toDoubleOrNull() ?: 0.0
                    if (source != null && dest != null) {
                        repository.batchUpdateTwoAccountsAndDeleteRecord(
                            source.copy(amount = formatBalance((source.amount.toDoubleOrNull() ?: 0.0) + amount)),
                            dest.copy(amount = formatBalance((dest.amount.toDoubleOrNull() ?: 0.0) - amount)),
                            recordId
                        )
                    } else {
                        repository.deleteRecord(recordId)
                    }
                    return@launch
                }

                // Normal record deletion with balance rollback + cascade recompute (#1)
                if (record != null) {
                    Log.d("ViewModel", "deleteRecord: normal deletion with balance rollback")
                    val account = accounts.value.find { it.id == record.accountId }
                    if (account != null) {
                        val amount   = record.amount.toDoubleOrNull() ?: 0.0
                        val cur      = account.amount.toDoubleOrNull() ?: 0.0
                        val restored = if (record.type == "Income") cur - amount else cur + amount
                        repository.batchUpdateAccountAndDeleteRecord(account.copy(amount = formatBalance(restored)), recordId)
                        recalculateBalancesForAccount(record.accountId)
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

    fun exportMonthlyReport(context: android.content.Context, month: Int, year: Int) {
        viewModelScope.launch {
            val success = PdfReportGenerator.generateMonthlyReport(
                context, records.value, budgets.value, month, year
            )
            toastMessage.value = if (success) "Report saved to Downloads" else "Failed to generate report"
        }
    }

    fun deleteUser() {
        Log.d("ViewModel", "deleteUser START: deleting all user data from Firestore")
        viewModelScope.launch {
            try {
                repository.deleteAllUserData()
                Log.d("ViewModel", "deleteUser END: success")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error deleting user", e)
                toastMessage.value = e.message
            }
        }
    }

    /** Awaitable version — use from coroutines that need to know when deletion is done. */
    suspend fun deleteUserAndAwait() {
        Log.d("ViewModel", "deleteUserAndAwait START")
        repository.deleteAllUserData()
        Log.d("ViewModel", "deleteUserAndAwait END")
    }

    fun onToastShown() {
        toastMessage.value = null
    }

    fun addCreditStatement(statement: CreditStatement) {
        Log.d("ViewModel", "addCreditStatement: card=${statement.cardLast4Digits} amount=${statement.totalAmount}")
        viewModelScope.launch {
            repository.addCreditStatement(statement.copy(userId = userId))
        }
    }

    fun deleteCreditStatement(id: String) {
        Log.d("ViewModel", "deleteCreditStatement: id=$id")
        viewModelScope.launch {
            repository.deleteCreditStatement(id)
        }
    }

    fun markStatementAsPaid(statement: CreditStatement) {
        Log.d("ViewModel", "markStatementAsPaid: id=${statement.id} amount=${statement.totalAmount}")
        viewModelScope.launch {
            repository.updateCreditStatement(statement.copy(isPaid = true))
        }
    }

    fun markStatementAsPaidNoAccount(statement: CreditStatement) {
        Log.d("ViewModel", "markStatementAsPaidNoAccount START: id=${statement.id}")
        viewModelScope.launch {
            try {
                repository.deleteCreditStatement(statement.id)
                Log.d("ViewModel", "markStatementAsPaidNoAccount END: success")
                toastMessage.value = "Statement marked as paid"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error marking statement as paid", e)
                toastMessage.value = "Failed to mark as paid"
            }
        }
    }

    // Savings Goals
    fun addSavingsGoal(goal: SavingsGoal) { Log.d("ViewModel", "addSavingsGoal: name=${goal.name} target=${goal.targetAmount}"); viewModelScope.launch { repository.addSavingsGoal(goal.copy(userId = userId)) } }
    fun updateSavingsGoal(goal: SavingsGoal) { Log.d("ViewModel", "updateSavingsGoal: id=${goal.id} saved=${goal.savedAmount}"); viewModelScope.launch { repository.updateSavingsGoal(goal) } }
    fun deleteSavingsGoal(id: String) { Log.d("ViewModel", "deleteSavingsGoal: id=$id"); viewModelScope.launch { repository.deleteSavingsGoal(id) } }

    // Debts
    fun addDebt(debt: Debt) { Log.d("ViewModel", "addDebt: person=${debt.personName} amount=${debt.amount}"); viewModelScope.launch { repository.addDebt(debt.copy(userId = userId)) } }
    fun updateDebt(debt: Debt) { Log.d("ViewModel", "updateDebt: id=${debt.id}"); viewModelScope.launch { repository.updateDebt(debt) } }
    fun deleteDebt(id: String) { Log.d("ViewModel", "deleteDebt: id=$id"); viewModelScope.launch { repository.deleteDebt(id) } }

    // Bills
    fun addBill(bill: Bill, context: Context) {
        Log.d("ViewModel", "addBill: name='${bill.name}' amount=${bill.amount} dayOfMonth=${bill.dayOfMonth}")
        viewModelScope.launch {
            repository.addBill(bill.copy(userId = userId))
            BillReminderManager.scheduleBillReminder(context, bill)
            Log.d("ViewModel", "addBill END: bill added and reminder scheduled")
        }
    }
    fun updateBill(bill: Bill, context: Context) {
        Log.d("ViewModel", "updateBill: id=${bill.id} name='${bill.name}' isActive=${bill.isActive}")
        viewModelScope.launch {
            repository.updateBill(bill)
            BillReminderManager.cancelBillReminder(context, bill.id)
            if (bill.isActive) {
                BillReminderManager.scheduleBillReminder(context, bill)
                Log.d("ViewModel", "updateBill: reminder rescheduled")
            } else {
                Log.d("ViewModel", "updateBill: bill inactive, reminder cancelled")
            }
        }
    }
    fun deleteBill(id: String, context: Context) {
        Log.d("ViewModel", "deleteBill: id=$id")
        viewModelScope.launch {
            repository.deleteBill(id)
            BillReminderManager.cancelBillReminder(context, id)
        }
    }

    fun addBudget(budget: Budget) {
        Log.d("ViewModel", "addBudget: category='${budget.category}' limit=${budget.monthlyLimit} currency=${budget.currency}")
        viewModelScope.launch { repository.addBudget(budget.copy(userId = userId)) }
    }

    fun updateBudget(budget: Budget) {
        Log.d("ViewModel", "updateBudget: id=${budget.id} category='${budget.category}' limit=${budget.monthlyLimit}")
        viewModelScope.launch { repository.updateBudget(budget) }
    }

    fun deleteBudget(budgetId: String) {
        Log.d("ViewModel", "deleteBudget: id=$budgetId")
        viewModelScope.launch { repository.deleteBudget(budgetId) }
    }

    fun archiveAccount(accountId: String) {
        Log.d("ViewModel", "archiveAccount: id=$accountId")
        val account = accounts.value.find { it.id == accountId } ?: return
        viewModelScope.launch { repository.updateAccount(account.copy(isArchived = true)) }
    }

    fun unarchiveAccount(accountId: String) {
        Log.d("ViewModel", "unarchiveAccount: id=$accountId")
        val account = accounts.value.find { it.id == accountId } ?: return
        viewModelScope.launch { repository.updateAccount(account.copy(isArchived = false)) }
    }

    fun reorderAccount(accountId: String, direction: Int) { Log.d("ViewModel", "reorderAccount: id=$accountId direction=$direction") // direction: -1 = left, +1 = right
        val active = accounts.value.filter { !it.isArchived }
        val hasSortOrder = active.any { it.sortOrder > 0 }
        val sorted = if (hasSortOrder) active.sortedBy { it.sortOrder }
                     else active.sortedWith(compareBy { when (it.accountType.lowercase()) {
                         "cash" -> 0; "debit" -> 1; "credit", "credit card" -> 2; "gold" -> 3; else -> 4
                     }})
        // Assign fresh sequential sortOrders to every account so none remain at 0
        val withOrder = sorted.mapIndexed { i, a -> a.copy(sortOrder = i + 1) }.toMutableList()
        val idx = withOrder.indexOfFirst { it.id == accountId }
        if (idx < 0) return
        val swapIdx = idx + direction
        if (swapIdx < 0 || swapIdx >= withOrder.size) return
        // Swap the two entries and fix their sortOrder values
        val tmp = withOrder[idx]
        withOrder[idx] = withOrder[swapIdx].copy(sortOrder = idx + 1)
        withOrder[swapIdx] = tmp.copy(sortOrder = swapIdx + 1)
        viewModelScope.launch {
            withOrder.forEach { repository.updateAccount(it) }
        }
    }

    fun transferBetweenAccounts(fromAccount: Account, toAccount: Account, amount: Double, note: String) {
        Log.d("ViewModel", "transferBetweenAccounts START: from='${fromAccount.name}' to='${toAccount.name}' amount=$amount")
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
                Log.d("ViewModel", "transferBetweenAccounts END: success")
                toastMessage.value = "Transfer completed"
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Transfer error", e)
                toastMessage.value = "Transfer failed: ${e.message}"
            }
        }
    }

    fun exportToCsvString(recordList: List<Record>) = FinancialCalculator.exportToCsvString(recordList)

    fun importFromCsv(context: Context, uri: android.net.Uri) {
        Log.d("ViewModel", "importFromCsv START: uri=$uri")
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val lines = context.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.readLines() ?: run {
                        toastMessage.value = "Could not read file"; return@withContext
                    }
                    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ENGLISH)
                    var imported = 0
                    lines.drop(1).forEach { line ->
                        if (line.isBlank()) return@forEach
                        val cols = parseCsvLine(line)
                        if (cols.size < 5) return@forEach
                        try {
                            val date = try { fmt.parse(cols[0]) ?: Date() } catch (_: Exception) { Date() }
                            repository.addRecord(Record(
                                timestamp    = date,
                                accountName  = cols.getOrElse(1) { "" },
                                category     = cols.getOrElse(2) { "Other" },
                                type         = cols.getOrElse(3) { "Expense" },
                                amount       = cols.getOrElse(4) { "0" },
                                currency     = cols.getOrElse(5) { "EGP" },
                                comment      = cols.getOrElse(6) { "" },
                                balanceAfter = cols.getOrElse(7) { "" },
                                userId       = userId
                            ))
                            imported++
                        } catch (_: Exception) {}
                    }
                    Log.d("ViewModel", "importFromCsv END: imported $imported records from ${lines.size - 1} CSV rows")
                    toastMessage.value = "Imported $imported record${if (imported != 1) "s" else ""}"
                } catch (e: Exception) {
                    toastMessage.value = "Import failed: ${e.message}"
                }
            }
        }
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in line) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { result.add(current.toString().trim()); current.clear() }
                else -> current.append(c)
            }
        }
        result.add(current.toString().trim())
        return result
    }

    private fun updateWidgetData() {
        val ctx = appContext ?: return
        val total = accounts.value
            .filter { !it.accountType.contains("Credit", ignoreCase = true) && !it.isArchived }
            .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        ctx.getSharedPreferences("wallet_widget", Context.MODE_PRIVATE)
            .edit()
            .putString("total_balance", String.format(java.util.Locale.getDefault(), "%,.2f EGP", total))
            .putString("spent_today", String.format(java.util.Locale.getDefault(), "%,.0f", spentToday.value))
            .putString("budget_left", String.format(java.util.Locale.getDefault(), "%,.0f", remainingBudgetTotal.value))
            .putLong("last_updated", System.currentTimeMillis())
            .apply()
        try {
            val mgr = android.appwidget.AppWidgetManager.getInstance(ctx)
            val comp = android.content.ComponentName(ctx, "com.example.wallettrackers.widget.BalanceWidget")
            val ids = mgr.getAppWidgetIds(comp)
            if (ids.isNotEmpty()) {
                ctx.sendBroadcast(android.content.Intent(ctx, Class.forName("com.example.wallettrackers.widget.BalanceWidget")).apply {
                    action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
                    putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                })
            }
        } catch (_: Exception) {}
    }

    private fun normaliseCurrency(currency: String) = FinancialCalculator.normaliseCurrency(currency)

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

    fun detectRecurringBills(context: Context? = null) {
        Log.d("ViewModel", "detectRecurringBills START")
        viewModelScope.launch {
            // Load persisted dismissed keys
            context?.let { loadDismissedSuggestionKeys(it) }
            val oneMonthAgoDate = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.time
            val twoMonthsAgoDate = Calendar.getInstance().apply { add(Calendar.MONTH, -2) }.time
            val excluded = setOf("Credit Payment", "Transfer", "Credit", "Instapay income", "Instapay outcome")

            // Collect existing bill names to avoid re-suggesting already-added bills
            val existingBillNames = bills.value.map { it.name.lowercase().trim() }.toSet()

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
                    if (existingBillNames.contains(normalizedName)) return@forEach
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
                    if (existingBillNames.contains(normalizedName)) return@forEach
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
        dismissBillSuggestion(suggestion, context)
        toastMessage.value = "\"${suggestion.name}\" added to bills"
    }

    fun dismissBillSuggestion(suggestion: RecurringBillSuggestion, context: Context? = null) {
        dismissedSuggestionKeys.add(suggestion.name.lowercase())
        suggestedBills.value = suggestedBills.value.filter {
            it.name.lowercase() !in dismissedSuggestionKeys
        }
        // Persist dismissed keys
        context?.let { saveDismissedSuggestionKeys(it) }
    }

    private fun saveDismissedSuggestionKeys(context: Context) {
        context.getSharedPreferences("bill_suggestions", Context.MODE_PRIVATE)
            .edit()
            .putStringSet("dismissed_keys_$userId", dismissedSuggestionKeys.toSet())
            .apply()
    }

    private fun loadDismissedSuggestionKeys(context: Context) {
        val saved = context.getSharedPreferences("bill_suggestions", Context.MODE_PRIVATE)
            .getStringSet("dismissed_keys_$userId", emptySet()) ?: emptySet()
        dismissedSuggestionKeys.addAll(saved)
    }

    fun attachReceiptToRecord(record: Record, uri: Uri) {
        Log.d("ViewModel", "attachReceiptToRecord START: recordId=${record.id} uri=$uri")
        viewModelScope.launch {
            val url = repository.uploadReceiptPhoto(userId, record.id, uri)
            if (url != null) {
                Log.d("ViewModel", "attachReceiptToRecord: upload success, url=${url.take(60)}...")
                repository.updateRecord(record.copy(receiptUrl = url))
                toastMessage.value = "Receipt attached"
            } else {
                Log.d("ViewModel", "attachReceiptToRecord: upload FAILED")
                toastMessage.value = "Failed to upload receipt"
            }
        }
    }

    private fun formatBalance(value: Double): String = String.format(Locale.ENGLISH, "%.2f", value)
}
