package com.example.wallettrackers.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.SmsMessage
import com.example.wallettrackers.viewmodel.SmsViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsScreen(
    viewModel: SmsViewModel,
    onBack: () -> Unit
) {
    val smsMessages = viewModel.smsMessages.value
    val bankRelatedMessages = remember(smsMessages) {
        smsMessages.filter { it.isBankRelated }
    }
    
    val accounts = viewModel.accounts.value
    val toastMessage by viewModel.toastMessage
    val isBatchProcessing by viewModel.isBatchProcessing
    val batchTotal by viewModel.batchTotal
    val batchCurrent by viewModel.batchCurrent
    val context = LocalContext.current
    
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Messages", "My Accounts")

    LaunchedEffect(Unit) {
        viewModel.fetchSms()
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.onToastShown()
        }
    }

    if (isBatchProcessing) {
        BatchProgressDialog(current = batchCurrent, total = batchTotal)
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("SMS Center") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (selectedTab == 0) {
                            val untrackedCount = bankRelatedMessages.count { !it.hasRecordAdded }
                            if (untrackedCount > 0) {
                                TextButton(onClick = { viewModel.trackAllBankSms() }) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Track All ($untrackedCount)")
                                }
                            }
                        }
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                if (bankRelatedMessages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No bank-related messages found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn {
                        items(bankRelatedMessages) { message ->
                            SmsItem(
                                message = message,
                                accounts = accounts,
                                onTrackManually = { viewModel.trackSmsManually(it) },
                                isLoading = viewModel.loadingSmsIds.contains(message.id)
                            )
                        }
                    }
                }
            } else {
                LazyColumn {
                    item {
                        Text(
                            text = "Manage your account digits to enable automatic tracking.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(accounts) { account ->
                        AccountLinkItem(account)
                    }
                }
            }
        }
    }
}

@Composable
fun BatchProgressDialog(current: Int, total: Int) {
    Dialog(onDismissRequest = { }) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Tracking Transactions...",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                LinearProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text(
                    text = "$current / $total processed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                if (current < total) {
                    Text(
                        text = "Processing messages...",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun AccountLinkItem(account: Account) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.CreditCard,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = account.name, fontWeight = FontWeight.Bold)
                Text(text = account.accountType, style = MaterialTheme.typography.bodySmall)
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(4.dp)
            ) {
                val digitsDisplay = account.last4Digits.filter { it.isDigit() }
                Text(
                    text = if (digitsDisplay.isEmpty()) "Not Set" else "**** $digitsDisplay",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
fun SmsItem(
    message: SmsMessage, 
    accounts: List<Account>,
    onTrackManually: (SmsMessage) -> Unit,
    isLoading: Boolean
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { isExpanded = !isExpanded }
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Sms,
                    contentDescription = "SMS",
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = message.sender,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = SimpleDateFormat("HH:mm, dd MMM", Locale.getDefault()).format(message.timestamp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.isBankRelated) {
                    SuggestionChip(
                        onClick = { isExpanded = !isExpanded },
                        label = { Text("Bank Related") },
                        icon = { Icon(Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFFE8F5E9))
                    )
                }

                if (message.hasRecordAdded) {
                    SuggestionChip(
                        onClick = { isExpanded = !isExpanded },
                        label = { Text("Tracked") },
                        icon = { Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFFBBDEFB))
                    )
                } else if (message.isBankRelated) {
                    SuggestionChip(
                        onClick = { isExpanded = !isExpanded },
                        label = { Text("Not Tracked") },
                        icon = { Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        colors = SuggestionChipDefaults.suggestionChipColors(containerColor = Color(0xFFFFEBEE))
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "Transaction Details", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                        if (message.isBankRelated && !message.hasRecordAdded) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Button(
                                    onClick = { onTrackManually(message) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Track Now", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    if (message.hasRecordAdded && message.linkedRecord != null) {
                        DetailRow("Status", "Successfully Added", Color(0xFF2E7D32))
                        DetailRow("Type", message.linkedRecord.type, if (message.linkedRecord.type == "Income") Color(0xFF2E7D32) else Color.Red)
                        DetailRow("Amount", "${message.linkedRecord.amount} ${message.linkedRecord.currency}")
                        DetailRow("Account", message.linkedRecord.accountName)
                        DetailRow("Category", message.linkedRecord.category)
                        DetailRow("Comment", message.linkedRecord.comment)
                    } else if (message.isBankRelated) {
                        val (statusTitle, statusColor) = when {
                            message.missingInfoReason == null && message.extractedAmount != null ->
                                "Ready to Track" to Color(0xFF2E7D32) // Green
                            message.extractedAmount != null && message.last4Digits != null -> 
                                "Account Not Found" to Color(0xFFF57C00) // Orange
                            else -> 
                                "Incomplete Data" to Color.Red
                        }

                        DetailRow("Status", statusTitle, statusColor)
                        DetailRow("Reason", message.missingInfoReason ?: "Account matched successfully")
                        
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        
                        Text(text = "Detected Info:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        DetailRow("Found Type", message.extractedType ?: "Expense", if (message.extractedType == "Income") Color(0xFF2E7D32) else Color.Red)
                        DetailRow("Found Amount", message.extractedAmount ?: "Not found")
                        DetailRow("Found Digits", message.last4Digits ?: "Not found")
                        DetailRow("Found Category", message.extractedCategory ?: "Others")
                        DetailRow("Found Comment", message.extractedComment ?: "Not found")
                        
                        if (statusTitle == "Account Not Found") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Registered Account Digits:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            accounts.forEach { account ->
                                val cleaned = account.last4Digits.filter { it.isDigit() }
                                Text(
                                    text = "• ${account.name}: ${if (cleaned.isEmpty()) "None" else cleaned}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Tip: Ensure one account matches '${message.last4Digits}' exactly (numbers only).",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else {
                        Text(text = "This message was scanned but no bank-related transaction was detected.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "$label:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, color = valueColor)
    }
}
