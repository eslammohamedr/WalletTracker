package com.example.wallettrackers.viewmodel

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.repository.FirebaseRepository
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class HomeViewModel(userId: String) : ViewModel() {

    private val repository = FirebaseRepository(userId)

    val accounts = mutableStateOf<List<Account>>(emptyList())
    val toastMessage = mutableStateOf<String?>(null)

    init {
        loadAccounts()
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

    fun addAccount(account: Account) {
        viewModelScope.launch {
            try {
                repository.addAccount(account)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Error adding account", e)
                toastMessage.value = e.message
            }
        }
    }

    fun onToastShown() {
        toastMessage.value = null
    }
}
