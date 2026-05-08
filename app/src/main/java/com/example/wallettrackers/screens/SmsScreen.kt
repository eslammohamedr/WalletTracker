package com.example.wallettrackers.screens

import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.wallettrackers.converters.longToColor
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.SmsMessage
import com.example.wallettrackers.viewmodel.SmsViewModel
import java.text.SimpleDateFormat
import java.util.*

import com.example.wallettrackers.ui.theme.*

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
            containerColor = DGSurface,
            icon = { Icon(Icons.Default.Replay, contentDescription = null, tint = DGVioletLight) },
            title = { Text("Retrack All?", fontWeight = FontWeight.Bold, color = DGTextPrimary) },
            text = { Text("This will delete and re-process all $trackedCount tracked records, updating amounts, categories and comments from scratch. Account balances will be recalculated.", color = DGTextSecondary) },
            confirmButton = {
                Button(
                    onClick = { showRetrackConfirmDialog = false; viewModel.retrackAllSms() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = DGIndigo)
                ) { Text("Retrack", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showRetrackConfirmDialog = false },
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, DGIndigo.copy(alpha = 0.5f))
                ) { Text("Cancel", color = DGTextPrimary) }
            }
        )
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(DGBackground)) {
                TopAppBar(
                    title = { Text("SMS Center", fontWeight = FontWeight.Bold, color = DGTextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DGTextPrimary)
                        }
                    },
                    actions = {
                        if (selectedTab == 0) {
                            IconButton(onClick = { viewModel.refresh() }, enabled = !isRefreshing) {
                                if (isRefreshing) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = DGVioletLight)
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = "Re-scan", tint = DGVioletLight)
                                }
                            }
                            IconButton(onClick = {
                                try {
                                    val text = viewModel.exportSmsAsText()
                                    val file = File(context.cacheDir, "sms_export.txt")
                                    file.writeText(text)
                                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
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
                                Icon(Icons.Default.Share, contentDescription = "Export SMS", tint = DGTextPrimary)
                            }
                            val trackedCount = bankRelatedMessages.count { it.hasRecordAdded }
                            if (trackedCount > 0) {
                                IconButton(onClick = { showRetrackConfirmDialog = true }) {
                                    Icon(Icons.Default.Replay, contentDescription = "Retrack All", tint = DGAmber)
                                }
                            }
                            val untrackedCount = bankRelatedMessages.count { !it.hasRecordAdded }
                            if (untrackedCount > 0) {
                                Button(
                                    onClick = { viewModel.trackAllBankSms() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(34.dp).padding(end = 8.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = DGIndigo)
                                ) {
                                    Icon(Icons.Default.PlaylistAdd, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Track All ($untrackedCount)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = DGBackground)
                )
                SecondaryTabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DGBackground,
                    contentColor = DGVioletLight,
                    indicator = {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(selectedTab),
                            color = DGVioletLight
                        )
                    }
                ) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { 
                                Text(
                                    title, 
                                    color = if (selectedTab == index) DGVioletLight else DGTextSecondary,
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                ) 
                            }
                        )
                    }
                }
            }
        },
        containerColor = DGBackground
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (selectedTab == 0) {
                if (bankRelatedMessages.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Sms, null, modifier = Modifier.size(64.dp), tint = DGIndigo.copy(alpha = 0.3f))
                            Spacer(Modifier.height(16.dp))
                            Text("No bank-related messages found", style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary)
                            Text("Auto-scan didn't find any financial alerts", style = MaterialTheme.typography.bodySmall, color = DGTextSecondary)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val geminiDebugMap by viewModel.geminiDebugMap
                        items(bankRelatedMessages) { message ->
                            SmsItem(
                                message = message,
                                accounts = accounts,
                                onTrackManually = { viewModel.trackSmsManually(it) },
                                onIgnore = { viewModel.ignoreSender(it) },
                                isLoading = viewModel.loadingSmsIds.contains(message.id),
                                geminiDebugResponse = geminiDebugMap[message.id],
                                isGeminiDebugLoading = viewModel.geminiDebugLoadingIds.contains(message.id),
                                onFetchGeminiDebug = { viewModel.fetchGeminiDebug(it) }
                            )
                        }
                    }
                }
            } else if (selectedTab == 1) {
                if (ignoredSenders.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Block, null, Modifier.size(64.dp), tint = DGIndigo.copy(alpha = 0.3f))
                            Spacer(Modifier.height(16.dp))
                            Text("No ignored senders", style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(ignoredSenders.toList()) { sender ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = DGSurface)
                            ) {
                                Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Block, null, tint = DGRed, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(16.dp))
                                    Text(sender, Modifier.weight(1f), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary)
                                    TextButton(onClick = { viewModel.unignoreSender(sender) }) {
                                        Text("Unignore", fontWeight = FontWeight.Bold, color = DGVioletLight)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (selectedTab == 2) {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = DGIndigo.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, DGIndigo.copy(alpha = 0.2f))
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Info, null, tint = DGVioletLight, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = "Ensure account digits match the last 4 digits in your bank SMS for auto-tracking.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = DGTextSecondary,
                                    fontWeight = FontWeight.Medium
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
                            Icon(Icons.Default.BookmarkBorder, null, Modifier.size(64.dp), tint = DGIndigo.copy(alpha = 0.3f))
                            Spacer(Modifier.height(16.dp))
                            Text("No rules saved yet", style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary)
                            Text("Track a message to save a merchant rule", style = MaterialTheme.typography.bodySmall, color = DGTextSecondary)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = DGIndigo.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, DGIndigo.copy(alpha = 0.2f))
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Info, null, tint = DGVioletLight, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "Merchant keywords are matched against SMS text to auto-assign categories.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = DGTextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        items(categoryRules, key = { it.id }) { rule ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = DGSurface)
                            ) {
                                Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(DGViolet.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(Icons.Default.Bookmark, null, tint = DGVioletLight, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(rule.merchantKeyword, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text("→ ${rule.category}", style = MaterialTheme.typography.bodySmall, color = DGTextSecondary, fontWeight = FontWeight.Medium)
                                    }
                                    IconButton(onClick = { viewModel.deleteRule(rule.id) }) {
                                        Icon(Icons.Default.Delete, null, tint = DGRed.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
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
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = DGSurface)
        ) {
            Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = "Tracking Transactions", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = DGTextPrimary)
                Spacer(Modifier.height(24.dp))
                val progress = if (total > 0) current.toFloat() / total.toFloat() else 0f
                Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(DGIndigo.copy(alpha = 0.2f))) {
                    Box(modifier = Modifier.fillMaxWidth(progress).fillMaxHeight().clip(CircleShape).background(AccentGradient))
                }
                Spacer(Modifier.height(16.dp))
                Text(text = "$current / $total", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = DGVioletLight)
                if (current < total) {
                    Text(text = "Processing messages…", style = MaterialTheme.typography.bodyMedium, color = DGTextSecondary, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

@Composable
fun AccountLinkItem(account: Account) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DGSurface)
    ) {
        Row(modifier = Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(longToColor(account.color).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.CreditCard, null, tint = longToColor(account.color), modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = account.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary)
                Text(text = account.accountType, style = MaterialTheme.typography.bodySmall, color = DGTextSecondary)
            }
            Surface(
                color = DGIndigo.copy(alpha = 0.15f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, DGIndigo.copy(alpha = 0.3f))
            ) {
                val digitsDisplay = account.last4Digits.filter { it.isDigit() }
                Text(
                    text = if (digitsDisplay.isEmpty()) "Not Set" else "•••• $digitsDisplay",
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Black,
                    color = DGVioletLight
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
    isLoading: Boolean,
    geminiDebugResponse: String? = null,
    isGeminiDebugLoading: Boolean = false,
    onFetchGeminiDebug: (SmsMessage) -> Unit = {}
) {
    var isExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth().clickable { isExpanded = !isExpanded },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DGSurface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(DGIndigo.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(imageVector = Icons.Default.Sms, contentDescription = null, tint = DGVioletLight, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = message.sender, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary)
                    Text(text = SimpleDateFormat("HH:mm, dd MMM", Locale.getDefault()).format(message.timestamp), style = MaterialTheme.typography.labelSmall, color = DGTextSecondary)
                }
                Box(
                    modifier = Modifier.size(10.dp).clip(CircleShape).background(
                        when {
                            message.hasRecordAdded -> DGGreen
                            message.isBankRelated -> DGAmber
                            else -> DGTextMuted
                        }
                    )
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(text = message.body, style = MaterialTheme.typography.bodySmall, color = DGTextSecondary, maxLines = if (isExpanded) Int.MAX_VALUE else 2, lineHeight = 18.sp)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.isBankRelated) {
                    Surface(color = DGIndigo.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountBalance, null, modifier = Modifier.size(12.dp), tint = DGVioletLight)
                            Spacer(Modifier.width(4.dp))
                            Text("Bank", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DGVioletLight)
                        }
                    }
                }
                if (message.hasRecordAdded) {
                    Surface(color = DGGreen.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(12.dp), tint = DGGreen)
                            Spacer(Modifier.width(4.dp))
                            Text("Tracked", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DGGreen)
                        }
                    }
                } else if (message.isBankRelated) {
                    Surface(color = DGAmber.copy(alpha = 0.15f), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, modifier = Modifier.size(12.dp), tint = DGAmber)
                            Spacer(Modifier.width(4.dp))
                            Text("Untracked", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DGAmber)
                        }
                    }
                }
            }

            AnimatedVisibility(visible = isExpanded) {
                Column(modifier = Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(DGBackground.copy(alpha = 0.5f)).padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "Transaction Details", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.labelLarge, color = DGTextPrimary)
                        if (message.isBankRelated && !message.hasRecordAdded) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = DGVioletLight)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { onTrackManually(message) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp), modifier = Modifier.height(34.dp), shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = DGIndigo)) {
                                        Text("Track Now", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(onClick = { onIgnore(message.sender) }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp), modifier = Modifier.height(34.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, DGIndigo.copy(alpha = 0.5f))) {
                                        Icon(Icons.Default.Block, null, Modifier.size(14.dp), tint = DGRed)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Ignore", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = DGTextPrimary)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    if (message.hasRecordAdded && message.linkedRecord != null) {
                        DetailRow("Status", "Added Successfully", DGGreen)
                        DetailRow("Type", message.linkedRecord.type, if (message.linkedRecord.type == "Income") DGGreen else DGRed)
                        DetailRow("Amount", "${message.linkedRecord.amount} ${message.linkedRecord.currency}", DGTextPrimary)
                        DetailRow("Account", message.linkedRecord.accountName, DGTextPrimary)
                        DetailRow("Category", message.linkedRecord.category, DGTextPrimary)
                        if (message.linkedRecord.comment.isNotEmpty()) DetailRow("Note", message.linkedRecord.comment, DGTextSecondary)
                    } else if (message.isBankRelated) {
                        val (statusTitle, statusColor) = when {
                            message.missingInfoReason == null && message.extractedAmount != null -> "Ready to Track" to DGGreen
                            message.extractedAmount != null && message.last4Digits != null -> "Account Not Found" to DGAmber
                            else -> "Incomplete Data" to DGRed
                        }
                        DetailRow("Status", statusTitle, statusColor)
                        DetailRow("Reason", message.missingInfoReason ?: "Account matched successfully", DGTextSecondary)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = DGIndigo.copy(alpha = 0.2f))
                        Text("Detected Info", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.ExtraBold, color = DGVioletLight)
                        Spacer(Modifier.height(8.dp))
                        DetailRow("Type", message.extractedType ?: "Expense", if (message.extractedType == "Income") DGGreen else DGRed)
                        DetailRow("Amount", message.extractedAmount ?: "Not found", DGTextPrimary)
                        DetailRow("Digits", message.last4Digits ?: "Not found", DGTextPrimary)
                        DetailRow("Category", message.extractedCategory ?: "Others", DGTextPrimary)
                        if (statusTitle == "Account Not Found") {
                            Spacer(Modifier.height(12.dp))
                            Text("Registered accounts:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = DGTextSecondary)
                            accounts.forEach { account ->
                                val cleaned = account.last4Digits.filter { it.isDigit() }
                                Text("• ${account.name}: ${if (cleaned.isEmpty()) "None" else cleaned}", style = MaterialTheme.typography.labelSmall, color = DGTextSecondary)
                            }
                            Spacer(Modifier.height(8.dp))
                            Surface(color = DGIndigo.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                                Text(text = "Tip: Add an account ending in '${message.last4Digits}' to enable auto-tracking.", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = DGVioletLight, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    // Gemini debug section
                    if (message.isBankRelated) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = DGIndigo.copy(alpha = 0.2f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "AI Debug",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF10B981)
                            )
                            if (isGeminiDebugLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF10B981)
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { onFetchGeminiDebug(message) },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(30.dp),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.6f))
                                ) {
                                    Text(
                                        if (geminiDebugResponse == null) "Ask AI" else "Refresh",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                        }
                        geminiDebugResponse?.let { response ->
                            Spacer(Modifier.height(8.dp))
                            val clipboard = LocalClipboardManager.current
                            var copied by remember { mutableStateOf(false) }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(response))
                                        copied = true
                                    },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                    modifier = Modifier.height(28.dp),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.5f))
                                ) {
                                    Icon(
                                        if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        modifier = Modifier.size(13.dp),
                                        tint = Color(0xFF10B981)
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        if (copied) "Copied!" else "Copy",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF10B981)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                color = Color(0xFF0A1628),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.25f))
                            ) {
                                Text(
                                    text = response,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 280.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF86EFAC),
                                    lineHeight = 18.sp,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DetailRow(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(text = "$label:", style = MaterialTheme.typography.bodySmall, color = DGTextSecondary, fontWeight = FontWeight.Medium)
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = valueColor)
    }
}
