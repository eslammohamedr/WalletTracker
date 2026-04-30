package com.example.wallettrackers.screens

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.wallettrackers.converters.longToColor
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
    val bankRelatedMessages = remember(smsMessages) { smsMessages.filter { it.isBankRelated } }
    val accounts = viewModel.accounts.value
    val toastMessage by viewModel.toastMessage
    val isBatchProcessing by viewModel.isBatchProcessing
    val batchTotal by viewModel.batchTotal
    val batchCurrent by viewModel.batchCurrent
    val isRefreshing by viewModel.isRefreshing
    val ignoredSenders by viewModel.ignoredSenders
    val categoryRules by viewModel.categoryRules
    val context = LocalContext.current

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Messages", "Ignored", "Accounts", "Rules")
    var showRetrackConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.fetchSms() }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.onToastShown()
        }
    }

    if (isBatchProcessing) {
        BatchProgressDialog(current = batchCurrent, total = batchTotal)
    }

    if (showRetrackConfirmDialog) {
        val trackedCount = bankRelatedMessages.count { it.hasRecordAdded }
        AlertDialog(
            onDismissRequest = { showRetrackConfirmDialog = false },
            icon = { Icon(Icons.Default.Replay, contentDescription = null) },
            title = { Text("Retrack All?") },
            text = { Text("This will delete and re-process all $trackedCount tracked records, updating amounts, categories and comments from scratch. Account balances will be recalculated.") },
            confirmButton = {
                Button(onClick = { showRetrackConfirmDialog = false; viewModel.retrackAllSms() },
                    shape = RoundedCornerShape(10.dp)) { Text("Retrack") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showRetrackConfirmDialog = false },
                    shape = RoundedCornerShape(10.dp)) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("SMS Center", fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(onClick = { viewModel.refresh() }, enabled = !isRefreshing) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Re-scan")
                                }
                            }
                            // Export all bank SMS to a file so you can share for analysis
                            IconButton(onClick = {
                                try {
                                    val text = viewModel.exportSmsAsText()
                                    val file = File(context.cacheDir, "sms_export.txt")
                                    file.writeText(text)
                                    val uri = FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        file
                                    )
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_SUBJECT, "WalletTrackers Bank SMS Export")
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share SMS Export"))
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }) {
                                Icon(Icons.Default.Share, contentDescription = "Export SMS")
                            }
                            val trackedCount = bankRelatedMessages.count { it.hasRecordAdded }
                            if (trackedCount > 0) {
                                IconButton(onClick = { showRetrackConfirmDialog = true }) {
                                    Icon(Icons.Default.Replay, contentDescription = "Retrack All")
                                }
                            }
                            val untrackedCount = bankRelatedMessages.count { !it.hasRecordAdded }
                            if (untrackedCount > 0) {
                                TextButton(onClick = { viewModel.trackAllBankSms() }) {
                                    Icon(Icons.Default.PlaylistAdd, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Track All ($untrackedCount)")
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
                PrimaryTabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (selectedTab == 0) {
                if (bankRelatedMessages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Sms,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No bank-related messages found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(bankRelatedMessages) { message ->
                            SmsItem(
                                message = message,
                                accounts = accounts,
                                onTrackManually = { viewModel.trackSmsManually(it) },
                                onIgnore = { viewModel.ignoreSender(it) },
                                isLoading = viewModel.loadingSmsIds.contains(message.id)
                            )
                        }
                    }
                }
            } else if (selectedTab == 1) {
                if (ignoredSenders.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Block, null, Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("No ignored senders", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(ignoredSenders.toList()) { sender ->
                            Card(shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                elevation = CardDefaults.cardElevation(1.dp)) {
                                Row(Modifier.padding(14.dp).fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Block, null, tint = MaterialTheme.colorScheme.error)
                                    Spacer(Modifier.width(12.dp))
                                    Text(sender, Modifier.weight(1f), fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium)
                                    TextButton(onClick = { viewModel.unignoreSender(sender) }) {
                                        Text("Unignore")
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (selectedTab == 2) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "Ensure account digits match the last 4 digits in your bank SMS for auto-tracking.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                    items(accounts) { account ->
                        AccountLinkItem(account)
                    }
                }
            } else {
                if (categoryRules.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.BookmarkBorder, null, Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("No rules saved yet", style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text("Track a message to be prompted to save a rule",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Merchant keywords are matched against SMS text to auto-assign categories.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                        items(categoryRules, key = { it.id }) { rule ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                elevation = CardDefaults.cardElevation(1.dp)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp, vertical = 12.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Default.Bookmark, null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(rule.merchantKeyword,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis)
                                        Text("→ ${rule.category}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                                        Icon(Icons.Default.Delete, null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
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
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Tracking Transactions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(20.dp))

                val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "$current / $total",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                if (current < total) {
                    Text(
                        text = "Processing messages…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun AccountLinkItem(account: Account) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(longToColor(account.color).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CreditCard,
                    contentDescription = null,
                    tint = longToColor(account.color),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = account.name, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
                Text(text = account.accountType, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                val digitsDisplay = account.last4Digits.filter { it.isDigit() }
                Text(
                    text = if (digitsDisplay.isEmpty()) "Not Set" else "•••• $digitsDisplay",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
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
    onIgnore: (String) -> Unit = {},
    isLoading: Boolean
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Sms,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = message.sender,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = SimpleDateFormat("HH:mm, dd MMM", Locale.getDefault()).format(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Status indicator dot
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                message.hasRecordAdded -> Color(0xFF22C55E)
                                message.isBankRelated -> Color(0xFFF59E0B)
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                )
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = message.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (isExpanded) Int.MAX_VALUE else 2
            )

            Spacer(Modifier.height(10.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (message.isBankRelated) {
                    SuggestionChip(
                        onClick = { isExpanded = !isExpanded },
                        label = { Text("Bank") },
                        icon = {
                            Icon(Icons.Default.AccountBalance, contentDescription = null,
                                modifier = Modifier.size(14.dp))
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                            labelColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                if (message.hasRecordAdded) {
                    SuggestionChip(
                        onClick = { isExpanded = !isExpanded },
                        label = { Text("Tracked") },
                        icon = {
                            Icon(Icons.Default.CheckCircle, contentDescription = null,
                                modifier = Modifier.size(14.dp))
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFF22C55E).copy(alpha = 0.12f),
                            labelColor = Color(0xFF15803D)
                        )
                    )
                } else if (message.isBankRelated) {
                    SuggestionChip(
                        onClick = { isExpanded = !isExpanded },
                        label = { Text("Untracked") },
                        icon = {
                            Icon(Icons.Default.Warning, contentDescription = null,
                                modifier = Modifier.size(14.dp))
                        },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFFF59E0B).copy(alpha = 0.12f),
                            labelColor = Color(0xFFB45309)
                        )
                    )
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Transaction Details",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                        if (message.isBankRelated && !message.hasRecordAdded) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Button(
                                        onClick = { onTrackManually(message) },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Text("Track Now", style = MaterialTheme.typography.labelMedium)
                                    }
                                    OutlinedButton(
                                        onClick = { onIgnore(message.sender) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                        modifier = Modifier.height(34.dp),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Icon(Icons.Default.Block, null, Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Ignore", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))

                    if (message.hasRecordAdded && message.linkedRecord != null) {
                        DetailRow("Status", "Added Successfully", Color(0xFF15803D))
                        DetailRow("Type", message.linkedRecord.type, if (message.linkedRecord.type == "Income") Color(0xFF15803D) else Color(0xFFDC2626))
                        DetailRow("Amount", "${message.linkedRecord.amount} ${message.linkedRecord.currency}")
                        DetailRow("Account", message.linkedRecord.accountName)
                        DetailRow("Category", message.linkedRecord.category)
                        if (message.linkedRecord.comment.isNotEmpty()) DetailRow("Note", message.linkedRecord.comment)
                    } else if (message.isBankRelated) {
                        val (statusTitle, statusColor) = when {
                            message.missingInfoReason == null && message.extractedAmount != null -> "Ready to Track" to Color(0xFF15803D)
                            message.extractedAmount != null && message.last4Digits != null -> "Account Not Found" to Color(0xFFF59E0B)
                            else -> "Incomplete Data" to Color(0xFFDC2626)
                        }
                        DetailRow("Status", statusTitle, statusColor)
                        DetailRow("Reason", message.missingInfoReason ?: "Account matched successfully")
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                        Text("Detected Info", style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        DetailRow("Type", message.extractedType ?: "Expense", if (message.extractedType == "Income") Color(0xFF15803D) else Color(0xFFDC2626))
                        DetailRow("Amount", message.extractedAmount ?: "Not found")
                        DetailRow("Digits", message.last4Digits ?: "Not found")
                        DetailRow("Category", message.extractedCategory ?: "Others")
                        if (statusTitle == "Account Not Found") {
                            Spacer(Modifier.height(8.dp))
                            Text("Registered accounts:", style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            accounts.forEach { account ->
                                val cleaned = account.last4Digits.filter { it.isDigit() }
                                Text("• ${account.name}: ${if (cleaned.isEmpty()) "None" else cleaned}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "Tip: Add an account ending in '${message.last4Digits}' to enable auto-tracking.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
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
        Text(text = "$label:", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium, color = valueColor)
    }
}
