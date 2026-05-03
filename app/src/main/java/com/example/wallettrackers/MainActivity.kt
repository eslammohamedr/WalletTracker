package com.example.wallettrackers

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
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
import com.example.wallettrackers.viewmodel.OnboardingViewModelFactory
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

class MainActivity : AppCompatActivity() {

    private val googleAuthUiClient by lazy {
        GoogleAuthUiClient(
            context = applicationContext,
            oneTapClient = Identity.getSignInClient(applicationContext)
        )
    }

    private var wasInBackground = false
    private val _isAppLocked = mutableStateOf(false)
    private val _biometricEnabled = mutableStateOf(false)

    override fun onStart() {
        super.onStart()
        if (wasInBackground && _biometricEnabled.value) {
            _isAppLocked.value = true
        }
        wasInBackground = false
    }

    override fun onStop() {
        super.onStop()
        if (_biometricEnabled.value) wasInBackground = true
    }

    private fun promptBiometric() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                _isAppLocked.value = false
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Keep locked; user can retry
            }
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Wallet Trackers")
            .setSubtitle("Authenticate to access your wallet")
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val isSystemInDarkTheme = isSystemInDarkTheme()
            var isDarkTheme by rememberSaveable { mutableStateOf(isSystemInDarkTheme) }

            // Init biometric setting from prefs
            LaunchedEffect(Unit) {
                val prefs = getSharedPreferences("wallet_prefs", MODE_PRIVATE)
                _biometricEnabled.value = prefs.getBoolean("biometric_enabled", false)
            }

            val isAppLocked by _isAppLocked
            val biometricEnabled by _biometricEnabled

            val context = LocalContext.current
            var showSettingsDialog by remember { mutableStateOf(false) }

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
                    // Check if we should show the settings dialog because it's permanently blocked
                    val shouldShowRationale = notificationPermission?.let { 
                        ActivityCompat.shouldShowRequestPermissionRationale(this, it) 
                    } ?: false
                    
                    if (!shouldShowRationale) {
                        showSettingsDialog = true
                    }
                }
            }

            LaunchedEffect(Unit) {
                val permissionsToRequest = mutableListOf<String>().apply {
                    addAll(smsPermissions)
                    notificationPermission?.let { add(it) }
                }
                permissionLauncher.launch(permissionsToRequest.toTypedArray())
            }

            if (showSettingsDialog) {
                AlertDialog(
                    onDismissRequest = { showSettingsDialog = false },
                    title = { Text("Notifications Blocked") },
                    text = { Text("You have disabled notifications. To receive transaction alerts, please enable them in the App Settings.") },
                    confirmButton = {
                        TextButton(onClick = { 
                            showSettingsDialog = false
                            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                            startActivity(intent)
                        }) {
                            Text("Open Settings")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSettingsDialog = false }) {
                            Text("Later")
                        }
                    }
                )
            }

            WalletTrackersTheme(darkTheme = isDarkTheme) {
                // Biometric lock overlay
                if (isAppLocked) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(16.dp))
                            Text("Wallet Trackers is locked",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(24.dp))
                            Button(
                                onClick = { promptBiometric() },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Unlock with Biometric")
                            }
                        }
                    }
                    return@WalletTrackersTheme
                }

                val navController = rememberNavController()
                
                // Handle navigation from notification intent
                LaunchedEffect(intent) {
                    if (intent?.getStringExtra("navigate_to") == "all_records") {
                        navController.navigate("all_records") {
                            // Ensure "home" is in the backstack if user is signed in
                            if (googleAuthUiClient.getSignedInUser() != null) {
                                popUpTo("home") { saveState = true }
                            }
                        }
                    }
                }

                NavHost(navController = navController, startDestination = "login") {
                    composable("login") {
                        val viewModel: AuthViewModel = viewModel(
                            factory = AuthViewModelFactory(googleAuthUiClient)
                        )
                        val state by viewModel.state.collectAsStateWithLifecycle()

                        val callbackManager = remember { CallbackManager.Factory.create() }
                        val facebookLauncher = rememberLauncherForActivityResult(
                            contract = LoginManager.getInstance().createLogInActivityResultContract(callbackManager, null)
                        ) { }

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
                            val user = googleAuthUiClient.getSignedInUser()
                            if (user != null) {
                                val prefs = getSharedPreferences("wallet_prefs", MODE_PRIVATE)
                                val isFirst = prefs.getBoolean("first_launch_${user.userId}", true)
                                if (isFirst) {
                                    navController.navigate("onboarding/${user.userId}") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                } else {
                                    navController.navigate("home")
                                }
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
                                val uid = googleAuthUiClient.getSignedInUser()?.userId
                                val prefs = getSharedPreferences("wallet_prefs", MODE_PRIVATE)
                                val isFirst = uid != null && prefs.getBoolean("first_launch_$uid", true)
                                if (isFirst && uid != null) {
                                    navController.navigate("onboarding/$uid") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                } else {
                                    navController.navigate("home")
                                }
                                viewModel.resetState()
                            }
                        }

                        LoginScreen(
                            onSignInClick = {
                                lifecycleScope.launch {
                                    val signInIntentSender = googleAuthUiClient.signIn()
                                    if (signInIntentSender != null) {
                                        googleLauncher.launch(IntentSenderRequest.Builder(signInIntentSender).build())
                                    }
                                }
                            },
                            onFacebookSignInClick = {
                                facebookLauncher.launch(listOf("email", "public_profile"))
                            },
                            onLoginSuccess = {
                                val uid = googleAuthUiClient.getSignedInUser()?.userId
                                val prefs = getSharedPreferences("wallet_prefs", MODE_PRIVATE)
                                val isFirst = uid != null && prefs.getBoolean("first_launch_$uid", true)
                                if (isFirst && uid != null) {
                                    navController.navigate("onboarding/$uid") {
                                        popUpTo("login") { inclusive = true }
                                    }
                                } else {
                                    navController.navigate("home")
                                }
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
                                val uid = googleAuthUiClient.getSignedInUser()?.userId
                                if (uid != null) {
                                    navController.navigate("onboarding/$uid") {
                                        popUpTo("signup") { inclusive = true }
                                    }
                                } else {
                                    navController.navigate("login")
                                }
                            }
                        )
                    }
                    composable("home") {
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val homeViewModel: HomeViewModel = viewModel(
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            val fixContext = LocalContext.current
                            val accountsSize = homeViewModel.accounts.value.size
                            LaunchedEffect(accountsSize) {
                                if (accountsSize > 0) {
                                    homeViewModel.fixCreditCardCurrencies(fixContext)
                                }
                            }
                            HomeScreen(
                                userData = signedInUser,
                                onSignOut = {
                                    lifecycleScope.launch {
                                        googleAuthUiClient.signOut()
                                        LoginManager.getInstance().logOut()
                                        navController.navigate("login")
                                    }
                                },
                                onDeleteAccount = {
                                    lifecycleScope.launch {
                                        homeViewModel.deleteUser()
                                        googleAuthUiClient.deleteAccount()
                                        LoginManager.getInstance().logOut()
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
                                },
                                onBudgetClick = {
                                    navController.navigate("budget")
                                },
                                onTransferClick = {
                                    navController.navigate("transfer")
                                },
                                onGoalsClick = {
                                    navController.navigate("goals")
                                },
                                onDebtsClick = {
                                    navController.navigate("debts")
                                },
                                onBillsClick = {
                                    navController.navigate("bills")
                                },
                                onCalendarClick = {
                                    navController.navigate("calendar")
                                },
                                biometricEnabled = biometricEnabled,
                                onBiometricToggle = { enabled ->
                                    _biometricEnabled.value = enabled
                                    getSharedPreferences("wallet_prefs", MODE_PRIVATE)
                                        .edit().putBoolean("biometric_enabled", enabled).apply()
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
                                onAmountChange = homeViewModel::onAddRecordAmountChange,
                                payFromAccount = homeViewModel.addRecordPayFromAccount.value,
                                onPayFromAccountChange = homeViewModel::onAddRecordPayFromAccountChange
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
                                viewModel = homeViewModel,
                                onCategoryClick = {
                                    navController.navigate("categories")
                                },
                                onSaveAsRule = { record ->
                                    homeViewModel.startPendingRule(record)
                                    navController.navigate("categories")
                                },
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
                    ) { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry("home")
                            }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            SubCategoriesScreen(
                                categoryName = backStackEntry.arguments?.getString("categoryName") ?: "" ,
                                onBack = { navController.popBackStack() },
                                onSubCategoryClick = { subCategory ->
                                    when {
                                        homeViewModel.pendingRuleRecord.value != null -> {
                                            homeViewModel.saveRuleAndResync(subCategory)
                                            navController.popBackStack("categories", inclusive = true)
                                        }
                                        homeViewModel.editingRecord.value != null -> {
                                            homeViewModel.updateEditingCategory(subCategory)
                                            navController.popBackStack("categories", inclusive = true)
                                        }
                                        else -> {
                                            navController.navigate("add_record?category=$subCategory") {
                                                popUpTo("add_record") { inclusive = true }
                                            }
                                        }
                                    }
                                }
                            )
                        }
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
                                statements = homeViewModel.statements.value,
                                toastMessage = homeViewModel.toastMessage.value,
                                onToastShown = homeViewModel::onToastShown,
                                onPayClick = { statement, account -> homeViewModel.payCreditStatement(statement, account) },
                                onDismissStatement = { statement -> homeViewModel.dismissCreditStatement(statement.id) },
                                onEditDueDate = { statement, date -> homeViewModel.updateStatementDueDate(statement, date) },
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
                    composable("budget") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry("home")
                            }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            BudgetScreen(
                                viewModel = homeViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                    composable("transfer") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) {
                                navController.getBackStackEntry("home")
                            }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            TransferScreen(
                                viewModel = homeViewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                    }
                    composable("goals") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("home") }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            SavingsGoalScreen(viewModel = homeViewModel, onBack = { navController.popBackStack() })
                        }
                    }
                    composable("debts") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("home") }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            DebtScreen(viewModel = homeViewModel, onBack = { navController.popBackStack() })
                        }
                    }
                    composable("bills") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("home") }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            BillScreen(viewModel = homeViewModel, onBack = { navController.popBackStack() })
                        }
                    }
                    composable("calendar") { backStackEntry ->
                        val signedInUser = googleAuthUiClient.getSignedInUser()
                        if (signedInUser?.userId != null) {
                            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry("home") }
                            val homeViewModel: HomeViewModel = viewModel(
                                viewModelStoreOwner = parentEntry,
                                factory = HomeViewModelFactory(signedInUser.userId)
                            )
                            CalendarScreen(viewModel = homeViewModel, onBack = { navController.popBackStack() })
                        }
                    }
                    composable(
                        route = "onboarding/{userId}",
                        arguments = listOf(navArgument("userId") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val uid = backStackEntry.arguments?.getString("userId") ?: return@composable
                        val onboardingViewModel: com.example.wallettrackers.viewmodel.OnboardingViewModel = viewModel(
                            factory = OnboardingViewModelFactory(applicationContext as Application, uid)
                        )
                        OnboardingScreen(
                            viewModel = onboardingViewModel,
                            onDone = {
                                getSharedPreferences("wallet_prefs", MODE_PRIVATE)
                                    .edit().putBoolean("first_launch_$uid", false).apply()
                                navController.navigate("home") {
                                    popUpTo("onboarding/$uid") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
