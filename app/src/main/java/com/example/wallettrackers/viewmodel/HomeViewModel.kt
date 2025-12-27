package com.example.wallettrackers.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class HomeViewModel(private val userId: String) : ViewModel() {

    private val repository = FirebaseRepository(userId)

    val accounts = mutableStateOf<List<Account>>(emptyList())
    val records = mutableStateOf<List<Record>>(emptyList())
    val toastMessage = mutableStateOf<String?>(null)

    init {
        loadAccounts()
        loadRecords()
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
                repository.addRecord(record.copy(userId = userId))
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding record", e)
                toastMessage.value = e.message
            }
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
}
