package com.example.wallettrackers.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.util.ReminderManager
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.util.Date

class HomeViewModel(private val userId: String) : ViewModel() {

    private val repository = FirebaseRepository(userId)

    val accounts = mutableStateOf<List<Account>>(emptyList())
    val records = mutableStateOf<List<Record>>(emptyList())
    val statements = mutableStateOf<List<CreditStatement>>(emptyList())
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
                    records.value = recordList.sortedByDescending { it.timestamp }
                }
        }
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

    private fun formatBalance(value: Double): String = String.format("%.2f", value)
}
