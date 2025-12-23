package com.example.wallettrackers.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AuthViewModel(private val authClient: GoogleAuthUiClient) : ViewModel() {

    private val _state = MutableStateFlow(SignInState())
    val state = _state.asStateFlow()

    fun onSignInResult(result: SignInResult) {
        _state.update {
            it.copy(
                isSignInSuccessful = result.data != null,
                signInError = result.errorMessage
            )
        }
    }

    fun signInWithEmail(email: String, password: String) {
        viewModelScope.launch {
            val result = authClient.signInWithEmail(email, password)
            onSignInResult(result)
        }
    }

    fun signUpWithEmail(email: String, password: String) {
        viewModelScope.launch {
            val result = authClient.signUpWithEmail(email, password)
            onSignInResult(result)
        }
    }

    fun resetState() {
        _state.update { SignInState() }
    }
}

@Suppress("UNCHECKED_CAST")
class AuthViewModelFactory(private val authClient: GoogleAuthUiClient) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            return AuthViewModel(authClient) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

data class SignInState(
    val isSignInSuccessful: Boolean = false,
    val signInError: String? = null
)
