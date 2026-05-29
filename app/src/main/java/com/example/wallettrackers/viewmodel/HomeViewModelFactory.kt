package com.example.wallettrackers.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.wallettrackers.BuildConfig
import com.example.wallettrackers.db.WalletDatabase
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.repository.OfflineFirstRepository
import com.example.wallettrackers.service.AiService

class HomeViewModelFactory(
    private val userId: String,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
            val db = WalletDatabase.getInstance(context)
            val firebase = FirebaseRepository(userId)
            val repository = OfflineFirstRepository(firebase, db.recordDao(), db.accountDao())
            val aiService = AiService(
                groqApiKey = BuildConfig.GROQ_API_KEY,
                cerebrasApiKey = BuildConfig.CEREBRAS_API_KEY,
                geminiApiKey = BuildConfig.GEMINI_API_KEY
            )
            @Suppress("UNCHECKED_CAST")
            return HomeViewModel(repository, userId, aiService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
