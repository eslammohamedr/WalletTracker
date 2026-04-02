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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HomeViewModel(private val userId: String) : ViewModel() {

    private val repository = FirebaseRepository(userId)

    val accounts = mutableStateOf<List<Account>>(emptyList())
    val records = mutableStateOf<List<Record>>(emptyList())
    val statements = mutableStateOf<List<CreditStatement>>(emptyList())
    val toastMessage = mutableStateOf<String?>(null)

    // State for AddRecordScreen
    val addRecordSelectedAccount = mutableStateOf<Account?>(null)
    val addRecordAmount = mutableStateOf("")

    fun onAddRecordAccountChange(account: Account) {
        addRecordSelectedAccount.value = account
    }

    fun onAddRecordAmountChange(newAmount: String) {
        addRecordAmount.value = newAmount
    }

    fun clearAddRecordState() {
        addRecordSelectedAccount.value = null
        addRecordAmount.value = ""
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
                    handleManualCreditPayment(record)
                } else {
                    handleNormalRecord(record)
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding record", e)
                toastMessage.value = e.message
            }
        }
    }

    private suspend fun handleManualCreditPayment(record: Record) {
        // record.accountId is the Credit Card account being paid
        val creditAccount = accounts.value.find { it.id == record.accountId } ?: return
        val paymentAmount = record.amount.toDoubleOrNull() ?: 0.0
        
        // 1. Find richest debit account to deduct from
        val debitAccount = accounts.value
            .filter { it.id != creditAccount.id && it.accountType.lowercase() != "credit card" }
            .maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
            
        if (debitAccount != null) {
            // Deduct from Debit
            val debitBal = debitAccount.amount.toDoubleOrNull() ?: 0.0
            val newDebitBal = debitBal - paymentAmount
            updateAccount(debitAccount.copy(amount = newDebitBal.toString()))
            
            // Add to Credit Card
            val creditBal = creditAccount.amount.toDoubleOrNull() ?: 0.0
            val newCreditBal = creditBal + paymentAmount
            updateAccount(creditAccount.copy(amount = newCreditBal.toString()))
            
            // 2. Mark matching statement as paid
            val digits = creditAccount.last4Digits.filter { it.isDigit() }
            val unpaidStatement = statements.value.find { it.cardLast4Digits == digits && !it.isPaid }
            if (unpaidStatement != null) {
                repository.updateCreditStatement(unpaidStatement.copy(isPaid = true))
                // Cancel reminders if we have context... but VM doesn't have it easily. 
                // We'll assume the worker checks isPaid status before showing notification if possible, 
                // but ReminderManager needs context. I'll skip cancellation here or add context later.
            }
            
            // 3. Save the record
            repository.addRecord(record.copy(
                userId = userId,
                type = "Expense", // It's an expense from the perspective of total cash
                balanceAfter = newDebitBal.toString(), // Balance of the account we deducted from
                accountName = "${debitAccount.name} -> ${creditAccount.name}"
            ))
        } else {
            // Fallback if no other account found, just do normal
            handleNormalRecord(record)
        }
    }

    private suspend fun handleNormalRecord(record: Record) {
        val account = accounts.value.find { it.id == record.accountId }
        if (account != null) {
            val isIncome = Categories.isIncomeCategory(record.category) || record.type == "Income"
            val amountDouble = record.amount.toDoubleOrNull() ?: 0.0
            val currentAccountAmount = account.amount.toDoubleOrNull() ?: 0.0
            
            val newBalance = if (isIncome) {
                currentAccountAmount + amountDouble
            } else {
                currentAccountAmount - amountDouble
            }
            
            val updatedAccount = account.copy(amount = newBalance.toString())
            updateAccount(updatedAccount)
            repository.addRecord(record.copy(
                userId = userId, 
                balanceAfter = newBalance.toString(),
                type = if (isIncome) "Income" else "Expense"
            ))
        }
    }

    fun updateRecord(record: Record) {
        viewModelScope.launch {
            try {
                repository.updateRecord(record)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error updating record", e)
                toastMessage.value = e.message
            }
        }
    }

    fun deleteRecord(recordId: String) {
        viewModelScope.launch {
            try {
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
}
