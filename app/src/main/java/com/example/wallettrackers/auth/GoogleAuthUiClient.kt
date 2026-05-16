package com.example.wallettrackers.auth

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.util.Log
import com.google.android.gms.auth.api.identity.BeginSignInRequest
import com.google.android.gms.auth.api.identity.SignInClient
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.util.concurrent.CancellationException

class GoogleAuthUiClient(
    private val context: Context,
    private val oneTapClient: SignInClient
) {

    private val auth = Firebase.auth

    suspend fun signIn(): IntentSender? {
        Log.d("Auth", "signIn START: initiating Google One-Tap sign-in")
        val result = try {
            oneTapClient.beginSignIn(
                buildSignInRequest()
            ).await()
        } catch (e: Exception) {
            Log.d("Auth", "signIn CATCH: exception=${e.message}")
            Log.e("GoogleAuth", "Auth error", e)
            if (e is CancellationException) throw e
            null
        }
        Log.d("Auth", "signIn END: intentSender=${if (result?.pendingIntent?.intentSender != null) "obtained" else "null"}")
        return result?.pendingIntent?.intentSender
    }

    suspend fun signInWithIntent(intent: Intent): SignInResult {
        Log.d("Auth", "signInWithIntent START: processing Google credential")
        val credential = oneTapClient.getSignInCredentialFromIntent(intent)
        val googleIdToken = credential.googleIdToken
        val googleCredentials = GoogleAuthProvider.getCredential(googleIdToken, null)
        return try {
            val user = auth.signInWithCredential(googleCredentials).await().user
            Log.d("Auth", "signInWithIntent END: success, userId=${user?.uid}")
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
            Log.d("Auth", "signInWithIntent CATCH: failure, error=${e.message}")
            Log.e("GoogleAuth", "Auth error", e)
            if (e is CancellationException) throw e
            SignInResult(
                data = null,
                errorMessage = e.message
            )
        }
    }

    suspend fun signInWithEmail(email: String, password: String): SignInResult {
        Log.d("Auth", "signInWithEmail START: email=$email")
        return try {
            val user = auth.signInWithEmailAndPassword(email, password).await().user
            Log.d("Auth", "signInWithEmail END: success, userId=${user?.uid}")
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
            Log.d("Auth", "signInWithEmail CATCH: failure, error=${e.message}")
            Log.e("GoogleAuth", "Auth error", e)
            if (e is CancellationException) throw e
            SignInResult(
                data = null,
                errorMessage = e.message
            )
        }
    }

    suspend fun signUpWithEmail(email: String, password: String): SignInResult {
        Log.d("Auth", "signUpWithEmail START: email=$email")
        return try {
            val user = auth.createUserWithEmailAndPassword(email, password).await().user
            Log.d("Auth", "signUpWithEmail END: success, userId=${user?.uid}")
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
            Log.d("Auth", "signUpWithEmail CATCH: failure, error=${e.message}")
            Log.e("GoogleAuth", "Auth error", e)
            if (e is CancellationException) throw e
            SignInResult(
                data = null,
                errorMessage = e.message
            )
        }
    }

    suspend fun signOut() {
        Log.d("Auth", "signOut START")
        try {
            oneTapClient.signOut().await()
            auth.signOut()
            Log.d("Auth", "signOut END: completed successfully")
        } catch (e: Exception) {
            Log.d("Auth", "signOut CATCH: failure, error=${e.message}")
            Log.e("GoogleAuth", "Auth error", e)
            if (e is CancellationException) throw e
        }
    }

    fun isGoogleUser(): Boolean {
        Log.d("Auth", "isGoogleUser START: currentUser=${auth.currentUser?.uid}")
        val result = auth.currentUser?.providerData?.any { it.providerId == GoogleAuthProvider.PROVIDER_ID } == true
        Log.d("Auth", "isGoogleUser END: result=$result")
        return result
    }

    suspend fun deleteAccount() {
        Log.d("Auth", "deleteAccount START: currentUser=${auth.currentUser?.uid}")
        // May throw FirebaseAuthRecentLoginRequiredException — callers must handle it.
        auth.currentUser?.delete()?.await()
        oneTapClient.signOut().await()
        auth.signOut()
        Log.d("Auth", "deleteAccount END: completed successfully")
    }

    fun getSignedInUser(): UserData? {
        val user = auth.currentUser?.run {
            UserData(
                userId = uid,
                username = displayName,
                profilePictureUrl = photoUrl?.toString()
            )
        }
        Log.d("Auth", "getSignedInUser: returning userId=${user?.userId}, username=${user?.username}")
        return user
    }

    private fun buildSignInRequest(): BeginSignInRequest {
        return BeginSignInRequest.Builder()
            .setGoogleIdTokenRequestOptions(
                BeginSignInRequest.GoogleIdTokenRequestOptions.builder()
                    .setSupported(true)
                    .setServerClientId(context.getString(com.example.wallettrackers.R.string.default_web_client_id))
                    .setFilterByAuthorizedAccounts(false)
                    .build()
            )
            .setAutoSelectEnabled(true)
            .build()
    }
}
