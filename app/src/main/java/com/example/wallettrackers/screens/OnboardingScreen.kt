package com.example.wallettrackers.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.util.DeviceSms
import com.example.wallettrackers.viewmodel.DiscoveredAccount
import com.example.wallettrackers.viewmodel.OnboardingStep
import com.example.wallettrackers.viewmodel.OnboardingViewModel
import java.text.SimpleDateFormat
import java.util.Locale

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onDone: () -> Unit
) {
    val step by viewModel.step
    val discoveredAccounts = viewModel.discoveredAccounts
    val importTotal by viewModel.importTotal
    val importCurrent by viewModel.importCurrent
    val errorMessage by viewModel.errorMessage
    val smsSheetAccount by viewModel.smsSheetAccount

    errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = viewModel::clearError,
            containerColor = AppSurface,
            title = { Text("Error", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
            text = { Text(msg, color = AppTextSecondary) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text("OK", color = AppVioletLight) }
            }
        )
    }

    if (smsSheetAccount != null) {
        SmsBottomSheet(
            account = smsSheetAccount!!,
            onDismiss = viewModel::closeSmsSheet
        )
    }

    Scaffold(containerColor = AppBackground) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) { currentStep ->
            when (currentStep) {
                OnboardingStep.WELCOME -> WelcomeStep(
                    onStart = viewModel::startScan,
                    onSkip = {
                        viewModel.skipOnboarding()
                        onDone()
                    }
                )
                OnboardingStep.SCANNING -> ScanningStep()
                OnboardingStep.ACCOUNTS_FOUND -> AccountsFoundStep(
                    accounts = discoveredAccounts,
                    onToggle = viewModel::toggleAccountSelection,
                    onNameChange = viewModel::updateAccountName,
                    onCreditLimitChange = viewModel::updateCreditLimit,
                    onConfirm = viewModel::startImport,
                    onSmsClick = viewModel::openSmsSheet,
                    onMerge = viewModel::mergeAccounts,
                    onSkip = {
                        viewModel.skipOnboarding()
                        onDone()
                    }
                )
                OnboardingStep.IMPORTING -> ImportingStep(
                    current = importCurrent,
                    total = importTotal
                )
                OnboardingStep.DONE -> DoneStep(onDone = onDone)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmsBottomSheet(
    account: DiscoveredAccount,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppSurface, scrimColor = Color.Black.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppPrimary.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Message,
                        contentDescription = null,
                        tint = AppVioletLight,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = account.confirmedName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                    Text(
                        text = "${account.smsCount} messages found",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = AppPrimary.copy(alpha = 0.2f))
            if (account.smsList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No messages to show", color = AppTextSecondary)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.heightIn(max = 480.dp)
                ) {
                    items(account.smsList) { sms ->
                        SmsCard(sms = sms, dateFormat = dateFormat)
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsCard(sms: DeviceSms, dateFormat: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppBackground.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sms.sender,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppVioletLight
                )
                Text(
                    text = dateFormat.format(sms.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppTextMuted
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = sms.body,
                style = MaterialTheme.typography.bodySmall,
                color = AppTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit, onSkip: () -> Unit) {
    // Floating logo animation
    val infiniteTransition = rememberInfiniteTransition(label = "welcome_logo")
    val logoOffsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -12f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_float"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_glow"
    )

    // Content entry animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        visible = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Animated floating logo with pulse glow ring
        Box(
            modifier = Modifier.graphicsLayer { translationY = logoOffsetY },
            contentAlignment = Alignment.Center
        ) {
            // Outer pulsing glow ring
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(AppViolet.copy(alpha = glowAlpha), Color.Transparent)
                        )
                    )
            )
            // Inner soft ring
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(AppPrimary.copy(alpha = glowAlpha * 0.6f), Color.Transparent)
                        )
                    )
            )
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AccentGradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Wallet,
                    contentDescription = null,
                    modifier = Modifier.size(44.dp),
                    tint = Color.White
                )
            }
        }

        Spacer(Modifier.height(36.dp))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { -30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "Welcome to Wallet Trackers",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    color = AppTextPrimary,
                    letterSpacing = (-1).sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "We can read your bank SMS messages to automatically discover accounts and transaction history — so you start with an accurate picture right away.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = AppTextSecondary,
                    lineHeight = 24.sp
                )
                Spacer(Modifier.height(12.dp))
                Surface(color = AppPrimary.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp)) {
                    Text(
                        text = "Your data stays on your device and in your private account. Nothing is shared with third parties.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = AppVioletLight,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(48.dp))

        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500, delayMillis = 200)) + slideInVertically(tween(500, delayMillis = 200)) { it / 3 }
        ) {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(AccentGradient)
                ) {
                    Button(
                        onClick = onStart,
                        modifier = Modifier.fillMaxSize(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text("Scan My SMS History", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(
                    onClick = onSkip,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Skip — I'll add accounts manually", color = AppTextSecondary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ScanningStep() {
    // Rotating dots animation
    val infiniteTransition = rememberInfiniteTransition(label = "scanning")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan_rotate"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan_pulse"
    )

    Column(
        modifier = Modifier.fillMaxSize().background(AppBackground),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Pulsing outer ring
            Box(
                modifier = Modifier
                    .size(110.dp)
                    .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale }
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(AppViolet.copy(alpha = 0.25f), Color.Transparent)
                        )
                    )
            )
            CircularProgressIndicator(
                modifier = Modifier
                    .size(70.dp)
                    .graphicsLayer { rotationZ = rotation },
                color = AppVioletLight,
                strokeWidth = 5.dp,
                trackColor = AppPrimary.copy(alpha = 0.15f)
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Analyzing SMS History",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = AppTextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Identifying bank accounts and transfers...",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary
        )
    }
}

@Composable
private fun AccountsFoundStep(
    accounts: List<DiscoveredAccount>,
    onToggle: (Int) -> Unit,
    onNameChange: (Int, String) -> Unit,
    onCreditLimitChange: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    onSmsClick: (DiscoveredAccount) -> Unit,
    onMerge: (keepDigits: String, dropDigits: String) -> Unit,
    onSkip: () -> Unit
) {
    val selectedCount = accounts.count { it.selected }

    Column(modifier = Modifier.fillMaxSize().background(AppBackground)) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
            Text(
                text = if (accounts.isEmpty()) "No Accounts Found" else "Accounts Discovered",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = AppTextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (accounts.isEmpty())
                    "We didn't find any bank alerts on this device. You can still proceed and set up your accounts manually."
                else
                    "Select the accounts you want to import and verify their names for the dashboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                lineHeight = 20.sp
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(accounts) { index, account ->
                val duplicateName = account.possibleDuplicateDigits?.let { digits ->
                    accounts.find { it.last4Digits == digits }?.confirmedName
                }
                DiscoveredAccountCard(
                    account = account,
                    onToggle = { onToggle(index) },
                    onNameChange = { onNameChange(index, it) },
                    onCreditLimitChange = { onCreditLimitChange(index, it) },
                    onSmsClick = { onSmsClick(account) },
                    possibleDuplicateName = duplicateName,
                    onMerge = account.possibleDuplicateDigits?.let { dupeDigits ->
                        { onMerge(account.last4Digits, dupeDigits) }
                    }
                )
            }
        }

        Column(modifier = Modifier.padding(24.dp)) {
            if (accounts.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (selectedCount > 0) AccentGradient else SolidColor(AppPrimary.copy(alpha = 0.2f)))
                ) {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxSize(),
                        enabled = selectedCount > 0,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, disabledContainerColor = Color.Transparent),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text(
                            if (selectedCount == 0) "Select an account to proceed"
                            else "Import $selectedCount Accounts",
                            fontWeight = FontWeight.Bold,
                            color = if (selectedCount > 0) Color.White else AppTextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip to Manual Setup", color = AppTextSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiscoveredAccountCard(
    account: DiscoveredAccount,
    onToggle: () -> Unit,
    onNameChange: (String) -> Unit,
    onCreditLimitChange: (String) -> Unit,
    onSmsClick: () -> Unit,
    possibleDuplicateName: String? = null,
    onMerge: (() -> Unit)? = null
) {
    var editingName by remember(account.confirmedName) { mutableStateOf(account.confirmedName) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (account.selected) AppSurface else AppSurface.copy(alpha = 0.5f)
        ),
        border = if (account.selected) BorderStroke(1.dp, AppVioletLight.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = account.selected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(checkedColor = AppVioletLight, uncheckedColor = AppPrimary.copy(alpha = 0.5f))
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(AppViolet.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (account.inferredType == "Credit Card") Icons.Default.CreditCard else Icons.Default.AccountBalance,
                        contentDescription = null,
                        tint = AppVioletLight,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = editingName,
                        onValueChange = {
                            editingName = it
                            onNameChange(it)
                        },
                        label = { Text("Display Name") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { onNameChange(editingName) }),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedContainerColor = AppBackground.copy(alpha = 0.3f),
                            unfocusedContainerColor = AppBackground.copy(alpha = 0.3f),
                            focusedBorderColor = AppVioletLight.copy(alpha = 0.5f),
                            unfocusedBorderColor = AppPrimary.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = AppPrimary.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                    Text(account.inferredType, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = AppVioletLight, fontWeight = FontWeight.Bold)
                }
                Surface(
                    onClick = onSmsClick,
                    color = AppBackground.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, AppPrimary.copy(alpha = 0.3f))
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Message, null, modifier = Modifier.size(12.dp), tint = AppTextSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text("${account.smsCount} SMS", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "•••• ${account.last4Digits}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = AppTextPrimary
                )
            }

            Spacer(Modifier.height(12.dp))

            if (account.inferredType == "Credit Card") {
                val dueFmt = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
                var editingLimit by remember(account.creditLimit) {
                    mutableStateOf(account.creditLimit?.let { "%.0f".format(it) } ?: "")
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Est. Balance", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                        Text("%.2f EGP".format(account.estimatedBalance), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                    }
                    OutlinedTextField(
                        value = editingLimit,
                        onValueChange = { editingLimit = it; onCreditLimitChange(it) },
                        label = { Text("Credit Limit") },
                        placeholder = { Text("50000") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                        modifier = Modifier.width(130.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedBorderColor = AppVioletLight.copy(alpha = 0.4f),
                            unfocusedBorderColor = AppPrimary.copy(alpha = 0.2f)
                        )
                    )
                }

                if (account.pendingStatementAmount != null) {
                    val dueStr = account.pendingStatementDueDate?.let { dueFmt.format(it) } ?: "soon"
                    Surface(color = AppRed.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
                        Text(
                            text = "Upcoming payment: %.2f EGP due $dueStr".format(account.pendingStatementAmount),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppRed,
                            modifier = Modifier.padding(8.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Inferred balance from history", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                    Text("%.2f EGP".format(account.estimatedBalance), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.ExtraBold, color = AppGreen)
                }
            }

            if (possibleDuplicateName != null && onMerge != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = AppPrimary.copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CallMerge, null, modifier = Modifier.size(14.dp), tint = AppVioletLight)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Same as $possibleDuplicateName?",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextPrimary,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onMerge) {
                            Text("Merge", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = AppVioletLight)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportingStep(current: Int, total: Int) {
    val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f

    Column(
        modifier = Modifier.fillMaxSize().background(AppBackground).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(100.dp),
                strokeWidth = 8.dp,
                color = AppVioletLight,
                trackColor = AppPrimary.copy(alpha = 0.1f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Black, color = AppTextPrimary, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(40.dp))
        Text(
            text = "Building Dashboard",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = AppTextPrimary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (total > 0) "Importing transaction $current of $total..." else "Creating account profiles...",
            style = MaterialTheme.typography.bodyMedium,
            color = AppTextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DoneStep(onDone: () -> Unit) {
    // Scale-in animation for the check icon
    var iconVisible by remember { mutableStateOf(false) }
    var contentVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(100)
        iconVisible = true
        kotlinx.coroutines.delay(300)
        contentVisible = true
    }

    val iconScale by animateFloatAsState(
        targetValue = if (iconVisible) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "done_icon_scale"
    )

    // Pulsing glow on success icon
    val infiniteTransition = rememberInfiniteTransition(label = "done_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.40f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "done_glow_alpha"
    )

    Column(
        modifier = Modifier.fillMaxSize().background(AppBackground).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.graphicsLayer { scaleX = iconScale; scaleY = iconScale },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(AppGreen.copy(alpha = glowAlpha), Color.Transparent)
                        )
                    )
            )
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = AppGreen
            )
        }
        Spacer(Modifier.height(32.dp))
        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(500)) + slideInVertically(tween(500)) { 30 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "You're All Set!",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    color = AppTextPrimary,
                    letterSpacing = (-1).sp
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "Your financial profile is ready. Future bank alerts will be tracked and categorized automatically.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = AppTextSecondary,
                    lineHeight = 24.sp
                )
            }
        }
        Spacer(Modifier.height(48.dp))
        AnimatedVisibility(
            visible = contentVisible,
            enter = fadeIn(tween(500, delayMillis = 150)) + slideInVertically(tween(500, delayMillis = 150)) { it / 3 }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AccentGradient)
            ) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.fillMaxSize(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text("Enter Dashboard", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}
