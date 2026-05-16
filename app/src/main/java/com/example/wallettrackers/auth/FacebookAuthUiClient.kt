package com.example.wallettrackers.auth

import android.util.Log
import com.facebook.login.LoginResult
import com.google.firebase.auth.FacebookAuthProvider
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import java.util.concurrent.CancellationException

object FacebookAuthUiClient {

    private const val TAG = "FacebookAuth"

    suspend fun handleFacebookLoginResult(loginResult: LoginResult): SignInResult {
        Log.d(TAG, "handleFacebookLoginResult START: processing Facebook credential")
        val credential = FacebookAuthProvider.getCredential(loginResult.accessToken.token)
        return try {
            val user = FirebaseAuth.getInstance().signInWithCredential(credential).await().user
            Log.d(TAG, "handleFacebookLoginResult END: success, userId=${user?.uid}")
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
            Log.e(TAG, "handleFacebookLoginResult: auth error", e)
            if (e is CancellationException) throw e
            SignInResult(
                data = null,
                errorMessage = e.message
            )
        }
    }
}
