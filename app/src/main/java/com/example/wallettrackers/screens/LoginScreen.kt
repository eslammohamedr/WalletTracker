package com.example.wallettrackers.screens

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wallettrackers.auth.AuthViewModel
import com.example.wallettrackers.ui.theme.WalletTrackersTheme

@Composable
fun LoginScreen(
    viewModel: AuthViewModel = viewModel(),
    onSignInClick: () -> Unit,
    onFacebookSignInClick: () -> Unit, // Added for Facebook
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(key1 = state.isSignInSuccessful) {
        if (state.isSignInSuccessful) {
            Toast.makeText(
                context,
                "Sign in successful",
                Toast.LENGTH_LONG
            ).show()
            onLoginSuccess()
            viewModel.resetState()
        }
    }

    LaunchedEffect(key1 = state.signInError) {
        state.signInError?.let {
            Toast.makeText(
                context,
                it,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "Welcome to Wallet Trackers", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = { /* TODO: Email/Password Login */ }) {
            Text("Sign in with Email")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = { /* TODO: Phone Number Login */ }) {
            Text("Sign in with Phone")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onSignInClick) {
            Text("Sign in with Google")
        }
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onFacebookSignInClick) { // Added for Facebook
            Text("Sign in with Facebook")
        }
    }
}

@Preview(showBackground = true)
@Composable
fun LoginScreenPreview() {
    WalletTrackersTheme {
        LoginScreen(onSignInClick = {}, onFacebookSignInClick = {}, onLoginSuccess = {})
    }
}
