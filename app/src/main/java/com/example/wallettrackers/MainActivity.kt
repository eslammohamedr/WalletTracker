package com.example.wallettrackers

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.wallettrackers.auth.AuthViewModel
import com.example.wallettrackers.auth.AuthViewModelFactory
import com.example.wallettrackers.auth.FacebookAuthUiClient
import com.example.wallettrackers.auth.GoogleAuthUiClient
import com.example.wallettrackers.auth.SignInResult
import com.example.wallettrackers.screens.*
import com.example.wallettrackers.ui.theme.WalletTrackersTheme
import com.example.wallettrackers.viewmodel.HomeViewModel
import com.example.wallettrackers.viewmodel.HomeViewModelFactory
import com.example.wallettrackers.viewmodel.SmsViewModel
import com.example.wallettrackers.viewmodel.SmsViewModelFactory
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
            @Suppress("DEPRECATION")
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
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
            
            // Permission Handling
            val context = LocalContext.current
            var showNotificationDialog by remember { mutableStateOf(false) }

            val smsPermissions = listOf(
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_SMS
            )
            
            val notificationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.POST_NOTIFICATIONS
            } else null

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestMultiplePermissions()
            ) { permissions ->
                val smsGranted = smsPermissions.all { permissions[it] == true }
                val notifyGranted = notificationPermission == null || permissions[notificationPermission] == true
                
                if (!smsGranted) {
                    Toast.makeText(context, "SMS permissions are required to auto-track transactions.", Toast.LENGTH_LONG).show()
                }
                if (!notifyGranted) {
                    showNotificationDialog = true
                }
            }

            LaunchedEffect(Unit) {
                val permissionsToRequest = mutableListOf<String>().apply {
                    addAll(smsPermissions)
                    notificationPermission?.let { add(it) }
                }
                
                val notGrantedPermissions = permissionsToRequest.filter {
                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                }
                
                if (notGrantedPermissions.isNotEmpty()) {
                    permissionLauncher.launch(notGrantedPermissions.toTypedArray())
                }
            }

            if (showNotificationDialog) {
                AlertDialog(
                    onDismissRequest = { showNotificationDialog = false },
                    title = { Text("Enable Notifications") },
                    text = { Text("Notifications help you stay informed about transactions tracked automatically from your SMS.") },
                    confirmButton = {
                        TextButton(onClick = { 
                            showNotificationDialog = false
                            notificationPermission?.let { 
                                permissionLauncher.launch(arrayOf(it))
                            }
                        }) {
                            Text("Enable")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNotificationDialog = false }) {
                            Text("Later")
                        }
                    }
                )
            }

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
                                },
                                onCategoriesClick = {
                                    navController.navigate("categories")
                                },
                                onStatisticsClick = {
                                    navController.navigate("statistics")
                                },
                                onSmsClick = {
                                    navController.navigate("sms")
                                }
                            )
                        }
                    }
                    composable(
                        route = "add_record?category={category}",
                        arguments = listOf(navArgument("category") {
                            type = NavType.StringType
                            nullable = true
                        })
                    ) { backStackEntry ->
                        val selectedCategory = backStackEntry.arguments?.getString("category")
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry("home")
                            }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            AddRecordScreen(
                                accounts = homeViewModel.accounts.value,
                                onAddRecord = {
                                    homeViewModel.addRecord(it)
                                    homeViewModel.clearAddRecordState()
                                    navController.popBackStack()
                                },
                                onCancel = {
                                    homeViewModel.clearAddRecordState()
                                    navController.popBackStack()
                                },
                                onCategoryClick = {
                                    navController.navigate("categories")
                                },
                                selectedCategory = selectedCategory,
                                selectedAccount = homeViewModel.addRecordSelectedAccount.value,
                                onAccountChange = homeViewModel::onAddRecordAccountChange,
                                amount = homeViewModel.addRecordAmount.value,
                                onAmountChange = homeViewModel::onAddRecordAmountChange
                            )
                        }
                    }
                    composable("all_records") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry("home")
                            }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
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
                    composable("categories") {
                        CategoriesScreen(
                            onCategoryClick = {
                                navController.navigate("subcategories/${it.name}")
                            },
                            onBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                    composable(
                        "subcategories/{categoryName}",
                        arguments = listOf(navArgument("categoryName") { type = NavType.StringType })
                    ) {
                        SubCategoriesScreen(
                            categoryName = it.arguments?.getString("categoryName") ?: "" ,
                            onBack = { navController.popBackStack() },
                            onSubCategoryClick = {
                                navController.navigate("add_record?category=$it") {
                                    popUpTo("add_record") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("statistics") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry("home")
                            }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            StatisticsScreen(
                                accounts = homeViewModel.accounts.value,
                                records = homeViewModel.records.value,
                                onBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                    composable("sms") {
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val smsViewModel: SmsViewModel = viewModel(
                                factory = SmsViewModelFactory(applicationContext as Application, signedInUser.userId)
                            )
                            SmsScreen(
                                viewModel = smsViewModel,
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
}