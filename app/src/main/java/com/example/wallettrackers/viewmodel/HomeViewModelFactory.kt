package com.example.wallettrackers.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.wallettrackers.repository.FirebaseRepository

class HomeViewModelFactory(private val userId: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(FirebaseRepository(userId), userId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
