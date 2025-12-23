package com.example.wallettrackers.auth

data class SignInResult(
    val data: UserData?,
    val errorMessage: String?
)
