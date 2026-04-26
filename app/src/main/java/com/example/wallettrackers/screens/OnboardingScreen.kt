package com.example.wallettrackers.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.util.DeviceSms
import com.example.wallettrackers.viewmodel.DiscoveredAccount
import com.example.wallettrackers.viewmodel.OnboardingStep
import com.example.wallettrackers.viewmodel.OnboardingViewModel
import java.text.SimpleDateFormat
import java.util.Locale

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
            title = { Text("Error") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = viewModel::clearError) { Text("OK") }
            }
        )
    }

    if (smsSheetAccount != null) {
        SmsBottomSheet(
            account = smsSheetAccount!!,
            onDismiss = viewModel::closeSmsSheet
        )
    }

    Scaffold { padding ->
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
                    onConfirm = viewModel::startImport,
                    onSmsClick = viewModel::openSmsSheet,
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

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Message,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = account.confirmedName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${account.smsCount} messages",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            HorizontalDivider()
            if (account.smsList.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No messages to show", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 520.dp)
                ) {
                    items(account.smsList) { sms ->
                        SmsCard(sms = sms, dateFormat = dateFormat)
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SmsCard(sms: DeviceSms, dateFormat: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = sms.sender,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = dateFormat.format(sms.date),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = sms.body,
                style = MaterialTheme.typography.bodySmall
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
        Icon(
            imageVector = Icons.Default.Wallet,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Welcome to Wallet Trackers",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "We can read your saved SMS messages to automatically discover your bank accounts and import your transaction history — so you start with an accurate picture right away.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Your data stays on your device and in your private Firebase account. Nothing is shared.",
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Scan My SMS History")
        }
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onSkip) {
            Text("Skip — I'll add accounts manually")
        }
    }
}

@Composable
private fun ScanningStep() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Scanning SMS History",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Looking for bank messages and accounts...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AccountsFoundStep(
    accounts: List<DiscoveredAccount>,
    onToggle: (Int) -> Unit,
    onNameChange: (Int, String) -> Unit,
    onConfirm: () -> Unit,
    onSmsClick: (DiscoveredAccount) -> Unit,
    onSkip: () -> Unit
) {
    val selectedCount = accounts.count { it.selected }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
            Text(
                text = if (accounts.isEmpty()) "No Accounts Found" else "Accounts Found",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (accounts.isEmpty())
                    "We didn't find any bank SMS on this device. You can add your accounts manually."
                else
                    "We discovered ${accounts.size} account(s) from your SMS history. Select which ones to import and confirm their names.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(accounts) { index, account ->
                DiscoveredAccountCard(
                    account = account,
                    onToggle = { onToggle(index) },
                    onNameChange = { onNameChange(index, it) },
                    onSmsClick = { onSmsClick(account) }
                )
            }
        }

        Column(modifier = Modifier.padding(16.dp)) {
            if (accounts.isNotEmpty()) {
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = selectedCount > 0
                ) {
                    Text(
                        if (selectedCount == 0) "Select at least one account"
                        else "Import $selectedCount Account(s) & Transactions"
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            TextButton(
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Skip — I'll set up manually")
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
    onSmsClick: () -> Unit
) {
    var editingName by remember(account.confirmedName) { mutableStateOf(account.confirmedName) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (account.selected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = account.selected,
                onCheckedChange = { onToggle() }
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = when (account.inferredType) {
                    "Credit Card" -> Icons.Default.CreditCard
                    else -> Icons.Default.AccountBalance
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = editingName,
                    onValueChange = {
                        editingName = it
                        onNameChange(it)
                    },
                    label = { Text("Account Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onNameChange(editingName) }),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    )
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = {},
                        label = { Text(account.inferredType) }
                    )
                    AssistChip(
                        onClick = onSmsClick,
                        label = { Text("${account.smsCount} SMS") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Message,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                Text(
                    text = "Est. balance: %.2f EGP".format(account.estimatedBalance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ImportingStep(current: Int, total: Int) {
    val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.size(80.dp),
            strokeWidth = 6.dp
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "Importing Transactions",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (total > 0) "$current of $total" else "Creating accounts...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        if (total > 0) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DoneStep(onDone: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            text = "You're All Set!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Your accounts and transaction history have been imported. Going forward, new bank SMS will be tracked automatically.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Go to Dashboard")
        }
    }
}
