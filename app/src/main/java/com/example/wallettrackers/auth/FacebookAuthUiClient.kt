package com.example.wallettrackers.auth

import com.facebook.login.LoginResult
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import java.util.concurrent.CancellationException

object FacebookAuthUiClient {

    suspend fun handleFacebookLoginResult(loginResult: LoginResult): SignInResult {
        val credential = FacebookAuthProvider.getCredential(loginResult.accessToken.token)
        return try {
            val user = FirebaseAuth.getInstance().signInWithCredential(credential).await().user
            SignInResult(
                data = user?.run {
                    UserData(
                        userId = uid,
                        username = displayName,
                        profilePictureUrl = photoUrl?.toString()
                    )
                },
                errorMessage = null
            )
        } catch (e: Exception) {
            e.printStackTrace()
            if (e is CancellationException) throw e
            SignInResult(
                data = null,
                errorMessage = e.message
            )
        }
    }
}
