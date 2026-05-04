package com.example.wallettrackers.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
            containerColor = DGSurface,
            title = { Text("Error", fontWeight = FontWeight.Bold, color = DGTextPrimary) },
            text = { Text(msg, color = DGTextSecondary) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text("OK", color = DGVioletLight) }
            }
        )
    }

    if (smsSheetAccount != null) {
        SmsBottomSheet(
            account = smsSheetAccount!!,
            onDismiss = viewModel::closeSmsSheet
        )
    }

    Scaffold(containerColor = DGBackground) { padding ->
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

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = DGSurface, scrimColor = Color.Black.copy(alpha = 0.5f)) {
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
                        .background(DGIndigo.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Message,
                        contentDescription = null,
                        tint = DGVioletLight,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = account.confirmedName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DGTextPrimary
                    )
                    Text(
                        text = "${account.smsCount} messages found",
                        style = MaterialTheme.typography.bodySmall,
                        color = DGTextSecondary
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = DGIndigo.copy(alpha = 0.2f))
            if (account.smsList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No messages to show", color = DGTextSecondary)
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
        colors = CardDefaults.cardColors(containerColor = DGBackground.copy(alpha = 0.5f))
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
                    color = DGVioletLight
                )
                Text(
                    text = dateFormat.format(sms.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = DGTextMuted
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = sms.body,
                style = MaterialTheme.typography.bodySmall,
                color = DGTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(DGViolet.copy(alpha = 0.2f), Color.Transparent)))
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
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Welcome to Wallet Trackers",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            color = DGTextPrimary,
            letterSpacing = (-1).sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "We can read your bank SMS messages to automatically discover accounts and transaction history — so you start with an accurate picture right away.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = DGTextSecondary,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(12.dp))
        Surface(color = DGIndigo.copy(alpha = 0.1f), shape = RoundedCornerShape(12.dp)) {
            Text(
                text = "Your data stays on your device and in your private account. Nothing is shared with third parties.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = DGVioletLight,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(48.dp))
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
        TextButton(onClick = onSkip) {
            Text("Skip — I'll add accounts manually", color = DGTextSecondary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ScanningStep() {
    Column(
        modifier = Modifier.fillMaxSize().background(DGBackground),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp), color = DGVioletLight, strokeWidth = 5.dp)
        Spacer(Modifier.height(32.dp))
        Text(
            text = "Analyzing SMS History",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = DGTextPrimary
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Identifying bank accounts and transfers...",
            style = MaterialTheme.typography.bodyMedium,
            color = DGTextSecondary
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

    Column(modifier = Modifier.fillMaxSize().background(DGBackground)) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
            Text(
                text = if (accounts.isEmpty()) "No Accounts Found" else "Accounts Discovered",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Black,
                color = DGTextPrimary
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (accounts.isEmpty())
                    "We didn't find any bank alerts on this device. You can still proceed and set up your accounts manually."
                else
                    "Select the accounts you want to import and verify their names for the dashboard.",
                style = MaterialTheme.typography.bodyMedium,
                color = DGTextSecondary,
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
                        .background(if (selectedCount > 0) AccentGradient else SolidColor(DGIndigo.copy(alpha = 0.2f)))
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
                            color = if (selectedCount > 0) Color.White else DGTextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip to Manual Setup", color = DGTextSecondary, fontWeight = FontWeight.SemiBold)
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
            containerColor = if (account.selected) DGSurface else DGSurface.copy(alpha = 0.5f)
        ),
        border = if (account.selected) BorderStroke(1.dp, DGVioletLight.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = account.selected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(checkedColor = DGVioletLight, uncheckedColor = DGIndigo.copy(alpha = 0.5f))
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(DGViolet.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (account.inferredType == "Credit Card") Icons.Default.CreditCard else Icons.Default.AccountBalance,
                        contentDescription = null,
                        tint = DGVioletLight,
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
                            focusedTextColor = DGTextPrimary,
                            unfocusedTextColor = DGTextPrimary,
                            focusedContainerColor = DGBackground.copy(alpha = 0.3f),
                            unfocusedContainerColor = DGBackground.copy(alpha = 0.3f),
                            focusedBorderColor = DGVioletLight.copy(alpha = 0.5f),
                            unfocusedBorderColor = DGIndigo.copy(alpha = 0.2f)
                        )
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = DGIndigo.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                    Text(account.inferredType, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = DGVioletLight, fontWeight = FontWeight.Bold)
                }
                Surface(
                    onClick = onSmsClick,
                    color = DGBackground.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(0.5.dp, DGIndigo.copy(alpha = 0.3f))
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Message, null, modifier = Modifier.size(12.dp), tint = DGTextSecondary)
                        Spacer(Modifier.width(6.dp))
                        Text("${account.smsCount} SMS", style = MaterialTheme.typography.labelSmall, color = DGTextSecondary, fontWeight = FontWeight.Medium)
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "•••• ${account.last4Digits}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = DGTextPrimary
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
                        Text("Est. Balance", style = MaterialTheme.typography.labelSmall, color = DGTextSecondary)
                        Text("%.2f EGP".format(account.estimatedBalance), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = DGTextPrimary)
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
                            focusedTextColor = DGTextPrimary,
                            unfocusedTextColor = DGTextPrimary,
                            focusedBorderColor = DGVioletLight.copy(alpha = 0.4f),
                            unfocusedBorderColor = DGIndigo.copy(alpha = 0.2f)
                        )
                    )
                }

                if (account.pendingStatementAmount != null) {
                    val dueStr = account.pendingStatementDueDate?.let { dueFmt.format(it) } ?: "soon"
                    Surface(color = DGRed.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp), modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) {
                        Text(
                            text = "Upcoming payment: %.2f EGP due $dueStr".format(account.pendingStatementAmount),
                            style = MaterialTheme.typography.labelSmall,
                            color = DGRed,
                            modifier = Modifier.padding(8.dp),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Inferred balance from history", style = MaterialTheme.typography.labelSmall, color = DGTextSecondary)
                    Text("%.2f EGP".format(account.estimatedBalance), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.ExtraBold, color = DGGreen)
                }
            }

            if (possibleDuplicateName != null && onMerge != null) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = DGIndigo.copy(alpha = 0.15f),
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CallMerge, null, modifier = Modifier.size(14.dp), tint = DGVioletLight)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Same as $possibleDuplicateName?",
                            style = MaterialTheme.typography.labelSmall,
                            color = DGTextPrimary,
                            modifier = Modifier.weight(1f),
                            fontWeight = FontWeight.Bold
                        )
                        TextButton(onClick = onMerge) {
                            Text("Merge", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Black, color = DGVioletLight)
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
        modifier = Modifier.fillMaxSize().background(DGBackground).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(100.dp),
                strokeWidth = 8.dp,
                color = DGVioletLight,
                trackColor = DGIndigo.copy(alpha = 0.1f),
                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            Text("${(progress * 100).toInt()}%", fontWeight = FontWeight.Black, color = DGTextPrimary, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(40.dp))
        Text(
            text = "Building Dashboard",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            color = DGTextPrimary
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (total > 0) "Importing transaction $current of $total..." else "Creating account profiles...",
            style = MaterialTheme.typography.bodyMedium,
            color = DGTextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DoneStep(onDone: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(DGBackground).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(modifier = Modifier.size(120.dp).clip(CircleShape).background(Brush.radialGradient(listOf(DGGreen.copy(alpha = 0.2f), Color.Transparent))))
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = DGGreen
            )
        }
        Spacer(Modifier.height(32.dp))
        Text(
            text = "You're All Set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
            color = DGTextPrimary,
            letterSpacing = (-1).sp
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your financial profile is ready. Future bank alerts will be tracked and categorized automatically.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = DGTextSecondary,
            lineHeight = 24.sp
        )
        Spacer(Modifier.height(48.dp))
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
