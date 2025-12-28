package com.example.wallettrackers

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.wallettrackers.auth.AuthViewModel
import com.example.wallettrackers.auth.AuthViewModelFactory
import com.example.wallettrackers.auth.FacebookAuthUiClient
import com.example.wallettrackers.auth.GoogleAuthUiClient
import com.example.wallettrackers.auth.SignInResult
import com.example.wallettrackers.screens.*
import com.example.wallettrackers.ui.theme.WalletTrackersTheme
import com.example.wallettrackers.viewmodel.HomeViewModel
import com.example.wallettrackers.viewmodel.HomeViewModelFactory
import com.facebook.CallbackManager
import com.facebook.FacebookCallback
import com.facebook.FacebookException
import com.facebook.login.LoginManager
import com.facebook.login.LoginResult
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.launch
import java.security.MessageDigest

class MainActivity : ComponentActivity() {

    private val googleAuthUiClient by lazy {
        GoogleAuthUiClient(
            context = applicationContext,
            oneTapClient = Identity.getSignInClient(applicationContext)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.forEach { signature ->
                val md = MessageDigest.getInstance("SHA")
                md.update(signature.toByteArray())
                val hash = Base64.encodeToString(md.digest(), Base64.DEFAULT)
                Log.d("KeyHash", "KeyHash: $hash")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        enableEdgeToEdge()
        setContent {
            val isSystemInDarkTheme = isSystemInDarkTheme()
            var isDarkTheme by rememberSaveable { mutableStateOf(isSystemInDarkTheme) }
            WalletTrackersTheme(darkTheme = isDarkTheme) {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "login") {
                    composable("login") {
                        val viewModel: AuthViewModel = viewModel(
                            factory = AuthViewModelFactory(googleAuthUiClient)
                        )
                        val state by viewModel.state.collectAsStateWithLifecycle()

                        val callbackManager = remember { CallbackManager.Factory.create() }
                        val facebookLauncher = rememberLauncherForActivityResult(
                            contract = LoginManager.getInstance().createLogInActivityResultContract(callbackManager, null)
                        ) { /* The result is handled by the callback */ }

                        LoginManager.getInstance().registerCallback(callbackManager, object : FacebookCallback<LoginResult> {
                            override fun onSuccess(result: LoginResult) {
                                lifecycleScope.launch {
                                    val signInResult = FacebookAuthUiClient.handleFacebookLoginResult(result)
                                    viewModel.onSignInResult(signInResult)
                                }
                            }

                            override fun onCancel() {
                                viewModel.onSignInResult(SignInResult(data = null, errorMessage = "Facebook sign in cancelled."))
                            }

                            override fun onError(error: FacebookException) {
                                viewModel.onSignInResult(SignInResult(data = null, errorMessage = error.message))
                            }
                        })

                        LaunchedEffect(key1 = Unit) {
                            if (googleAuthUiClient.getSignedInUser() != null) {
                                navController.navigate("home")
                            }
                        }

                        val googleLauncher = rememberLauncherForActivityResult(
                            contract = ActivityResultContracts.StartIntentSenderForResult()
                        ) { result ->
                            if (result.resultCode == RESULT_OK) {
                                lifecycleScope.launch {
                                    val signInResult = googleAuthUiClient.signInWithIntent(
                                        intent = result.data ?: return@launch
                                    )
                                    viewModel.onSignInResult(signInResult)
                                }
                            }
                        }

                        LaunchedEffect(key1 = state.isSignInSuccessful) {
                            if (state.isSignInSuccessful) {
                                Toast.makeText(applicationContext, "Sign in successful", Toast.LENGTH_LONG).show()
                                navController.navigate("home")
                                viewModel.resetState()
                            }
                        }

                        LoginScreen(
                            onSignInClick = {
                                lifecycleScope.launch {
                                    val signInIntentSender = googleAuthUiClient.signIn()
                                    if (signInIntentSender == null) {
                                        Toast.makeText(applicationContext, "Failed to start Google Sign-In. Check your configuration.", Toast.LENGTH_LONG).show()
                                        return@launch
                                    }
                                    googleLauncher.launch(
                                        IntentSenderRequest.Builder(signInIntentSender).build()
                                    )
                                }
                            },
                            onFacebookSignInClick = {
                                facebookLauncher.launch(listOf("email", "public_profile"))
                            },
                            onLoginSuccess = {
                                navController.navigate("home")
                            },
                            onSignInWithEmail = viewModel::signInWithEmail,
                            onSignUpClick = {
                                navController.navigate("signup")
                            }
                        )
                    }
                    composable("signup") {
                        val viewModel: AuthViewModel = viewModel(
                            factory = AuthViewModelFactory(googleAuthUiClient)
                        )
                        val state by viewModel.state.collectAsStateWithLifecycle()
                        SignUpScreen(
                            state = state,
                            onSignUp = viewModel::signUpWithEmail,
                            onSignUpSuccess = {
                                navController.navigate("login")
                            }
                        )
                    }
                    composable("home") {
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val homeViewModel: HomeViewModel = viewModel(
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            HomeScreen(
                                userData = signedInUser,
                                onSignOut = {
                                    lifecycleScope.launch {
                                        googleAuthUiClient.signOut()
                                        LoginManager.getInstance().logOut() // Also log out from Facebook
                                        Toast.makeText(applicationContext, "Signed out", Toast.LENGTH_LONG).show()
                                        navController.navigate("login")
                                    }
                                },
                                onDeleteAccount = {
                                    lifecycleScope.launch {
                                        homeViewModel.deleteUser()
                                        googleAuthUiClient.deleteAccount()
                                        LoginManager.getInstance().logOut() // Also log out from Facebook
                                        Toast.makeText(applicationContext, "Account deleted", Toast.LENGTH_LONG).show()
                                        navController.navigate("login")
                                    }
                                },
                                viewModel = homeViewModel,
                                onAddRecord = {
                                    navController.navigate("add_record")
                                },
                                onSeeAllRecords = {
                                    navController.navigate("all_records")
                                },
                                isDarkTheme = isDarkTheme,
                                onThemeChange = { isDarkTheme = it },
                                onCurrencyConverter = {
                                    navController.navigate("currency_converter")
                                }
                            )
                        }
                    }
                    composable("add_record") {
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val homeViewModel: HomeViewModel = viewModel(
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            AddRecordScreen(
                                accounts = homeViewModel.accounts.value,
                                onAddRecord = {
                                    homeViewModel.addRecord(it)
                                    navController.popBackStack()
                                },
                                onCancel = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                    composable("all_records") {
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val homeViewModel: HomeViewModel = viewModel(
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            AllRecordsScreen(
                                records = homeViewModel.records.value,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                    composable("currency_converter") {
                        CurrencyConverterScreen(
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
