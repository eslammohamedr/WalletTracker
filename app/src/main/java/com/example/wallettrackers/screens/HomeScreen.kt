package com.example.wallettrackers.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.geometry.Offset
import coil.compose.AsyncImage
import com.example.wallettrackers.auth.UserData
import com.example.wallettrackers.converters.colorToLong
import com.example.wallettrackers.converters.longToColor
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.AppNotification
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.ui.theme.pickAutoColor
import com.example.wallettrackers.util.FinancialCalculator
import com.example.wallettrackers.remote.ExchangeRateApi
import com.example.wallettrackers.viewmodel.HomeViewModel
import com.example.wallettrackers.components.RecordCard
import com.example.wallettrackers.components.AnimatedCounter
import com.example.wallettrackers.components.HomeWeeklyChart
import com.example.wallettrackers.components.HomeCategoryDonutChart
import com.example.wallettrackers.ui.theme.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

// ─── Helpers ──────────────────────────────────────────────────────────────────

private fun accountTypeIcon(type: String) = when {
    type.contains("Cash",   ignoreCase = true) -> Icons.Default.Payments
    type.contains("Credit", ignoreCase = true) -> Icons.Default.CreditCard
    type.contains("Gold",   ignoreCase = true) -> Icons.Default.MonetizationOn
    else                                        -> Icons.Default.AccountBalance
}

private fun formatBalance(v: Double): String = String.format(Locale.getDefault(), "%.2f", v)

// ─── Section header ───────────────────────────────────────────────────────────
@Composable
private fun DGSectionHeader(
    title: String,
    action: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AccentGradient)
            )
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
        }
        if (action != null && onAction != null) {
            Text(
                text = action,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = AppPrimaryLight,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

// ─── Main HomeScreen ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    userData: UserData?,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    viewModel: HomeViewModel,
    onAddRecord: () -> Unit,
    onSeeAllRecords: () -> Unit,
    isDarkTheme: Boolean,
    themeMode: String = "dark",
    onThemeModeChange: (String) -> Unit = {},
    onCurrencyConverter: () -> Unit,
    onCategoriesClick: () -> Unit,
    onStatisticsClick: () -> Unit,
    onSmsClick: () -> Unit,
    onBudgetClick: () -> Unit,
    onTransferClick: () -> Unit,
    onGoalsClick: () -> Unit,
    onDebtsClick: () -> Unit,
    onBillsClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onAiChatClick: () -> Unit = {},
    onSplitReceiptClick: () -> Unit = {},
    onFeatureTourClick: () -> Unit = {},
    biometricEnabled: Boolean,
    onBiometricToggle: (Boolean) -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val accounts        by viewModel.accounts
    val records         by viewModel.records
    val budgets         by viewModel.budgets
    val installments    by viewModel.installments
    val monthlyInsight  by viewModel.monthlyInsight
    val categoryRules   by viewModel.categoryRules
    val unlinkedRecords by viewModel.unlinkedRecords
    val bills                by viewModel.bills
    val debts                by viewModel.debts
    val pendingBalanceUpdates by viewModel.pendingBalanceUpdates
    val spendingForecast      by viewModel.spendingForecast
    val unusualRecordIds      by viewModel.unusualRecordIds
    val spentToday            by viewModel.spentToday
    val remainingBudgetTotal  by viewModel.remainingBudgetTotal
    val upcomingBillsCount    by viewModel.upcomingBillsCount
    val spendingInsights      by viewModel.spendingInsights
    val cashFlowPrediction    by viewModel.aiCashFlowPrediction
    val isCashFlowLoading     by viewModel.isCashFlowLoading
    val budgetStreak          by viewModel.budgetStreak
    val dailySpendingLast30   by viewModel.dailySpendingLast30Days
    val context = LocalContext.current

    var showCsvPickerDialog by remember { mutableStateOf(false) }
    var csvFilesInDownloads by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }

    var showUnlinkedDialog          by remember { mutableStateOf(false) }
    var selectedUnlinkedRecord      by remember { mutableStateOf<Record?>(null) }
    var showAddAccountDialog       by remember { mutableStateOf(false) }
    var showAccountOptionsDialog   by remember { mutableStateOf(false) }
    var showDeleteAccountDialog    by remember { mutableStateOf(false) }
    var showEditAccountDialog      by remember { mutableStateOf(false) }
    var selectedAccount            by remember { mutableStateOf<Account?>(null) }
    var showRecordOptionsDialog    by remember { mutableStateOf(false) }
    var showDeleteRecordDialog     by remember { mutableStateOf(false) }
    val editingRecord              by viewModel.editingRecord
    val showEditRecordDialog       by viewModel.showEditDialog
    var optionSelectedRecord       by remember { mutableStateOf<Record?>(null) }
    var balanceVisible             by remember { mutableStateOf(true) }
    var showDeleteUserDialog       by remember { mutableStateOf(false) }
    var showNotificationsSheet     by remember { mutableStateOf(false) }
    var showProfileSheet           by remember { mutableStateOf(false) }
    var showExportDialog           by remember { mutableStateOf(false) }

    val appNotifications by viewModel.notifications

    val pendingBudgetAlert by viewModel.pendingBudgetAlert
    val fxRates by viewModel.fxRates
    val exchangeRateApi = remember { ExchangeRateApi.create() }
    var goldPriceEgpPerGram by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        try {
            val usdResponse = exchangeRateApi.getLatestRates("USD")
            val usdToEgp = usdResponse.rates["EGP"]
            if (usdToEgp != null) {
                val rates = mutableMapOf("USD" to usdToEgp)
                usdResponse.rates["EUR"]?.let { rates["EUR"] = usdToEgp / it }
                usdResponse.rates["GBP"]?.let { rates["GBP"] = usdToEgp / it }
                usdResponse.rates["SAR"]?.let { rates["SAR"] = usdToEgp / it }
                usdResponse.rates["AED"]?.let { rates["AED"] = usdToEgp / it }
                viewModel.fxRates.value = rates
                val goldUsdPerOz = exchangeRateApi.getGoldPriceUSD()
                if (goldUsdPerOz != null) goldPriceEgpPerGram = goldUsdPerOz * usdToEgp / 31.1035
            }
        } catch (_: Exception) {}
    }

    val isLoading by viewModel.isLoading
    val pullRefreshState = rememberPullToRefreshState()
    var isRefreshing by remember { mutableStateOf(false) }

    val sortedAccounts = remember(accounts) {
        val active = accounts.filter { !it.isArchived }
        if (active.any { it.sortOrder > 0 }) active.sortedBy { it.sortOrder }
        else active.sortedWith(compareBy {
            when (it.accountType.lowercase()) {
                "cash" -> 0; "debit" -> 1; "credit", "credit card" -> 2; "gold" -> 3; else -> 4
            }
        })
    }

    val debitCashAccounts = remember(accounts) {
        accounts.filter { !it.isArchived }
            .filter {
                it.accountType.equals("Debit", ignoreCase = true) ||
                it.accountType.equals("Cash", ignoreCase = true)
            }
    }

    val totalBalance = remember(debitCashAccounts) {
        debitCashAccounts
            .filter { it.currency.equals("EGP", ignoreCase = true) }
            .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
    }

    val currencyBreakdown = remember(debitCashAccounts) {
        debitCashAccounts
            .groupBy { FinancialCalculator.normaliseCurrency(it.currency) }
            .mapValues { (_, accs) -> accs.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
            .filter { it.value != 0.0 }
    }

    val toastMessage by viewModel.toastMessage
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            isRefreshing = false
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onToastShown()
        }
    }

    // ── Dialogs ──────────────────────────────────────────────────────────────
    if (showAddAccountDialog) {
        AccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onConfirm = { viewModel.addAccount(it) },
            existingColors = accounts.map { it.color },
            title = "Add Account", confirmButtonText = "Add"
        )
    }

    // ── Unlinked Records Dialog ────────────────────────────────────────────────
    if (showUnlinkedDialog) {
        AlertDialog(
            onDismissRequest = { showUnlinkedDialog = false; selectedUnlinkedRecord = null },
            containerColor = AppSurface,
            title = {
                Text(
                    "Unlinked Records",
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (selectedUnlinkedRecord == null) {
                        Text(
                            "Select a record to assign it to an account:",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary
                        )
                        Spacer(Modifier.height(4.dp))
                        unlinkedRecords.forEach { record ->
                            val categoryColor = Categories.list.flatMap { it.subCategories + it }
                                .find { it.name == record.category }?.color ?: AppVioletLight
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable { selectedUnlinkedRecord = record },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = AppBackground)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            record.category,
                                            fontWeight = FontWeight.SemiBold,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = categoryColor
                                        )
                                        if (record.comment.isNotEmpty()) {
                                            Text(
                                                record.comment,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = AppTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Text(
                                        "${if (record.type == "Income") "+" else "-"}${record.amount} ${record.currency}",
                                        fontWeight = FontWeight.Bold,
                                        color = if (record.type == "Income") AppGreen else AppRed,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    } else {
                        val rec = selectedUnlinkedRecord!!
                        Text(
                            "Assign to account:",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary
                        )
                        Text(
                            "${rec.category} · ${if (rec.type == "Income") "+" else "-"}${rec.amount} ${rec.currency}",
                            fontWeight = FontWeight.SemiBold,
                            color = AppTextPrimary,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        accounts.forEach { account ->
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable {
                                    viewModel.linkRecordToAccount(rec, account)
                                    selectedUnlinkedRecord = null
                                    if (unlinkedRecords.size <= 1) showUnlinkedDialog = false
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = AppBackground)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        accountTypeIcon(account.accountType),
                                        contentDescription = null,
                                        tint = longToColor(account.color),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            account.name,
                                            fontWeight = FontWeight.SemiBold,
                                            color = AppTextPrimary,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            "${account.amount} ${account.currency}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AppTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                if (selectedUnlinkedRecord != null) {
                    TextButton(onClick = { selectedUnlinkedRecord = null }) {
                        Text("Back", color = AppTextSecondary)
                    }
                } else {
                    TextButton(onClick = { showUnlinkedDialog = false }) {
                        Text("Close", color = AppTextSecondary)
                    }
                }
            }
        )
    }
    selectedAccount?.let { account ->
        if (showAccountOptionsDialog) AccountOptionsDialog(
            onDismiss = { showAccountOptionsDialog = false },
            onEdit = { showAccountOptionsDialog = false; showEditAccountDialog = true },
            onDelete = { showAccountOptionsDialog = false; showDeleteAccountDialog = true },
            onArchive = { showAccountOptionsDialog = false; viewModel.archiveAccount(account.id) }
        )
        if (showDeleteAccountDialog) DeleteConfirmationDialog(
            onDismiss = { showDeleteAccountDialog = false },
            onConfirm = { viewModel.deleteAccount(account.id); showDeleteAccountDialog = false },
            title = "Delete Account", text = "Delete \"${account.name}\"?"
        )
        if (showEditAccountDialog) AccountDialog(
            account = account,
            onDismiss = { showEditAccountDialog = false },
            onConfirm = { viewModel.updateAccount(it) },
            existingColors = accounts.filter { it.id != account.id }.map { it.color },
            title = "Edit Account", confirmButtonText = "Update"
        )
    }
    if (showRecordOptionsDialog && optionSelectedRecord != null) OptionsDialog(
        onDismiss = { showRecordOptionsDialog = false },
        onEdit = { showRecordOptionsDialog = false; viewModel.startEditing(optionSelectedRecord!!) },
        onSaveAsRule = { showRecordOptionsDialog = false; viewModel.startPendingRule(optionSelectedRecord!!); onCategoriesClick() },
        onDelete = { showRecordOptionsDialog = false; showDeleteRecordDialog = true }
    )
    if (showDeleteRecordDialog && optionSelectedRecord != null) DeleteConfirmationDialog(
        onDismiss = { showDeleteRecordDialog = false },
        onConfirm = { viewModel.deleteRecord(optionSelectedRecord!!.id); showDeleteRecordDialog = false },
        title = "Delete Record", text = "Delete this record?"
    )
    if (showEditRecordDialog && editingRecord != null) RecordDialog(
        record = editingRecord, accounts = accounts,
        onDismiss = { viewModel.stopEditing() },
        onConfirm = { viewModel.updateRecord(it); viewModel.stopEditing() },
        onCategoryClick = onCategoriesClick,
        title = "Edit Record", confirmButtonText = "Update"
    )
    if (showDeleteUserDialog) DeleteConfirmationDialog(
        onDismiss = { showDeleteUserDialog = false },
        onConfirm = { onDeleteAccount(); showDeleteUserDialog = false },
        title = "Delete Account",
        text = "This will permanently delete your account and all data. This cannot be undone."
    )

    if (showExportDialog) {
        val cal = Calendar.getInstance()
        var selectedMonth by remember { mutableStateOf(cal.get(Calendar.MONTH)) }
        var selectedYear by remember { mutableStateOf(cal.get(Calendar.YEAR)) }
        val monthNames = listOf("January","February","March","April","May","June","July","August","September","October","November","December")

        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            containerColor = AppSurface,
            title = { Text("Export PDF Report", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Select month and year to export:", color = AppTextSecondary, fontSize = 14.sp)
                    // Month selector
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        IconButton(onClick = { if (selectedMonth > 0) selectedMonth-- else { selectedMonth = 11; selectedYear-- } }) {
                            Icon(Icons.Default.ChevronLeft, null, tint = AppTextPrimary)
                        }
                        Text(
                            "${monthNames[selectedMonth]} $selectedYear",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AppTextPrimary
                        )
                        IconButton(onClick = { if (selectedMonth < 11) selectedMonth++ else { selectedMonth = 0; selectedYear++ } }) {
                            Icon(Icons.Default.ChevronRight, null, tint = AppTextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showExportDialog = false
                        viewModel.exportMonthlyReport(context, selectedMonth, selectedYear)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                ) { Text("Export PDF") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Cancel", color = AppTextSecondary)
                }
            }
        )
    }
    pendingBudgetAlert?.let { alert ->
        val isOver = alert.spent > alert.limit
        val pct = (alert.spent / alert.limit * 100).toInt()
        val progress = (alert.spent / alert.limit).toFloat().coerceIn(0f, 1f)
        val accentColor = if (isOver) AppRed else AppAmber
        val accentBg = if (isOver) AppRed.copy(alpha = 0.12f) else AppAmber.copy(alpha = 0.10f)

        Dialog(onDismissRequest = { viewModel.onBudgetAlertSent() }) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(28.dp))
                    .background(AppSurface)
                    .padding(24.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {

                    // Icon badge
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(accentBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isOver) Icons.Default.Warning else Icons.Default.Notifications,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = if (isOver) "Budget Exceeded!" else "Budget Warning",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = accentColor
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = alert.category,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppTextSecondary
                    )

                    Spacer(Modifier.height(20.dp))

                    // Progress bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(AppPrimary.copy(alpha = 0.15f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(progress)
                                .clip(RoundedCornerShape(50))
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(
                                            if (isOver) AppRed.copy(alpha = 0.7f) else AppAmber.copy(alpha = 0.7f),
                                            accentColor
                                        )
                                    )
                                )
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Spent / Limit row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("Spent", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text(
                                "${"%.2f".format(alert.spent)} ${alert.currency}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Limit", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                            Text(
                                "${"%.2f".format(alert.limit)} ${alert.currency}",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    Text(
                        text = if (isOver) "${pct - 100}% over limit" else "$pct% of budget used",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(24.dp))

                    Button(
                        onClick = { viewModel.onBudgetAlertSent() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor)
                    ) {
                        Text("Got it", fontWeight = FontWeight.Bold, color = AppBackground)
                    }
                }
            }
        }
    }

    if (pendingBalanceUpdates.isNotEmpty()) {
        var checkedIds by remember(pendingBalanceUpdates) {
            mutableStateOf(pendingBalanceUpdates.map { it.account.id }.toSet())
        }
        val allChecked = checkedIds.size == pendingBalanceUpdates.size

        AlertDialog(
            onDismissRequest = { viewModel.dismissBalanceUpdates() },
            containerColor = AppSurface,
            title = {
                Text(
                    "Update Balances from SMS",
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Select all row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                checkedIds = if (allChecked) emptySet()
                                else pendingBalanceUpdates.map { it.account.id }.toSet()
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Select all",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary
                        )
                        Checkbox(
                            checked = allChecked,
                            onCheckedChange = {
                                checkedIds = if (it) pendingBalanceUpdates.map { u -> u.account.id }.toSet()
                                else emptySet()
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = AppPrimary,
                                uncheckedColor = AppTextSecondary
                            )
                        )
                    }

                    HorizontalDivider(color = AppTextSecondary.copy(alpha = 0.2f))

                    pendingBalanceUpdates.forEach { update ->
                        val checked = update.account.id in checkedIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (checked) AppBackground else AppBackground.copy(alpha = 0.4f))
                                .clickable {
                                    checkedIds = if (checked) checkedIds - update.account.id
                                    else checkedIds + update.account.id
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = {
                                    checkedIds = if (it) checkedIds + update.account.id
                                    else checkedIds - update.account.id
                                },
                                colors = CheckboxDefaults.colors(
                                    checkedColor = AppPrimary,
                                    uncheckedColor = AppTextSecondary
                                )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    update.account.name,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (checked) AppTextPrimary else AppTextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "…${update.account.last4Digits}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppTextSecondary
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    String.format("%.2f", update.oldBalance),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTextSecondary,
                                    textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough
                                )
                                Text(
                                    String.format("%.2f %s", update.newBalance, update.account.currency),
                                    fontWeight = FontWeight.Bold,
                                    color = if (update.newBalance >= update.oldBalance) AppGreen else AppRed,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmBalanceUpdates(checkedIds) },
                    enabled = checkedIds.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                ) {
                    Text("Update (${checkedIds.size})", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissBalanceUpdates() }) {
                    Text("Cancel", color = AppTextSecondary)
                }
            }
        )
    }

    if (showCsvPickerDialog) {
        AlertDialog(
            onDismissRequest = { showCsvPickerDialog = false },
            containerColor = AppSurface,
            title = { Text("Select CSV File", color = AppTextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    csvFilesInDownloads.forEach { (name, uri) ->
                        TextButton(
                            onClick = {
                                showCsvPickerDialog = false
                                viewModel.importFromCsv(context, uri)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(name, color = AppPrimary, textAlign = TextAlign.Start,
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showCsvPickerDialog = false }) {
                    Text("Cancel", color = AppTextSecondary)
                }
            }
        )
    }

    // ── Notifications Bottom Sheet ───────────────────────────────────────────
    if (showNotificationsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showNotificationsSheet = false },
            containerColor = AppSurface,
            contentColor = AppTextPrimary
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Notifications", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                    if (appNotifications.isNotEmpty()) {
                        TextButton(onClick = { viewModel.clearNotifications() }) {
                            Text("Clear All", color = AppVioletLight, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (appNotifications.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.NotificationsNone, null, tint = AppTextMuted, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No notifications yet", color = AppTextMuted, fontSize = 14.sp)
                        }
                    }
                } else {
                    val dateFmt = remember { SimpleDateFormat("MMM dd, yyyy • hh:mm a", Locale.getDefault()) }
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        appNotifications.forEach { notif ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(
                                            when (notif.type) {
                                                "budget_alert" -> AppAmber.copy(alpha = 0.15f)
                                                "bill_reminder" -> AppPrimary.copy(alpha = 0.15f)
                                                else -> AppViolet.copy(alpha = 0.15f)
                                            }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        when (notif.type) {
                                            "budget_alert" -> Icons.Default.Warning
                                            "bill_reminder" -> Icons.Default.Receipt
                                            else -> Icons.Default.Info
                                        },
                                        null,
                                        tint = when (notif.type) {
                                            "budget_alert" -> AppAmber
                                            "bill_reminder" -> AppPrimary
                                            else -> AppViolet
                                        },
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(notif.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
                                    Text(notif.message, fontSize = 12.sp, color = AppTextSecondary)
                                    Text(dateFmt.format(notif.timestamp), fontSize = 11.sp, color = AppTextMuted)
                                }
                            }
                            HorizontalDivider(color = AppPrimary.copy(alpha = 0.1f))
                        }
                    }
                }
            }
        }
    }

    // ── Profile Bottom Sheet ─────────────────────────────────────────────────
    if (showProfileSheet) {
        ModalBottomSheet(
            onDismissRequest = { showProfileSheet = false },
            containerColor = AppSurface,
            contentColor = AppTextPrimary
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState())) {
                // Profile header
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (userData?.profilePictureUrl != null) {
                        AsyncImage(
                            model = userData.profilePictureUrl,
                            contentDescription = "Profile",
                            modifier = Modifier.size(56.dp).clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(56.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(AppViolet, AppPrimary))),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                userData?.username?.firstOrNull()?.uppercase() ?: "?",
                                fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White
                            )
                        }
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(userData?.username ?: "User", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        Text("Wallet Tracker", fontSize = 13.sp, color = AppTextSecondary)
                    }
                }
                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = AppPrimary.copy(alpha = 0.15f))
                Spacer(Modifier.height(16.dp))

                // Settings section
                Text("Settings", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppTextMuted, modifier = Modifier.padding(bottom = 8.dp))

                // Theme mode selector
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                        null, tint = AppVioletLight, modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Theme", color = AppTextPrimary, fontSize = 15.sp)
                }
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                ) {
                    listOf("light" to "Light", "dark" to "Dark", "system" to "System").forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { onThemeModeChange(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                            icon = {},
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = AppVioletLight.copy(alpha = 0.2f),
                                activeContentColor = AppVioletLight,
                                inactiveContainerColor = Color.Transparent,
                                inactiveContentColor = AppTextSecondary
                            )
                        ) {
                            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                // Biometric lock
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Fingerprint, null, tint = AppVioletLight, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Biometric Lock", modifier = Modifier.weight(1f), color = AppTextPrimary, fontSize = 15.sp)
                    Switch(checked = biometricEnabled, onCheckedChange = onBiometricToggle)
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AppPrimary.copy(alpha = 0.15f))
                Spacer(Modifier.height(12.dp))

                // Notification settings
                Text("Notifications", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppTextMuted, modifier = Modifier.padding(bottom = 8.dp))

                val notifPrefs = context.getSharedPreferences("notification_prefs", Context.MODE_PRIVATE)
                var budgetAlerts by remember { mutableStateOf(notifPrefs.getBoolean("budget_alerts", true)) }
                var billReminders by remember { mutableStateOf(notifPrefs.getBoolean("bill_reminders", true)) }
                var unusualTransactions by remember { mutableStateOf(notifPrefs.getBoolean("unusual_transactions", true)) }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, null, tint = AppAmber, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Budget Alerts", modifier = Modifier.weight(1f), color = AppTextPrimary, fontSize = 15.sp)
                    Switch(checked = budgetAlerts, onCheckedChange = {
                        budgetAlerts = it
                        notifPrefs.edit().putBoolean("budget_alerts", it).apply()
                    })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Receipt, null, tint = AppPrimary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Bill Reminders", modifier = Modifier.weight(1f), color = AppTextPrimary, fontSize = 15.sp)
                    Switch(checked = billReminders, onCheckedChange = {
                        billReminders = it
                        notifPrefs.edit().putBoolean("bill_reminders", it).apply()
                    })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.TrendingUp, null, tint = AppViolet, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Unusual Transactions", modifier = Modifier.weight(1f), color = AppTextPrimary, fontSize = 15.sp)
                    Switch(checked = unusualTransactions, onCheckedChange = {
                        unusualTransactions = it
                        notifPrefs.edit().putBoolean("unusual_transactions", it).apply()
                    })
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AppPrimary.copy(alpha = 0.15f))
                Spacer(Modifier.height(12.dp))

                // Category Rules
                Text("Category Rules", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = AppTextMuted, modifier = Modifier.padding(bottom = 8.dp))
                if (categoryRules.isEmpty()) {
                    Text(
                        "No rules saved yet",
                        fontSize = 13.sp,
                        color = AppTextSecondary,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    categoryRules.forEach { rule ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Bookmark, null, tint = AppVioletLight, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "${rule.merchantKeyword} → ${rule.category}",
                                fontSize = 14.sp,
                                color = AppTextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.deleteRule(rule.id) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, null, tint = AppRed, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AppPrimary.copy(alpha = 0.15f))
                Spacer(Modifier.height(12.dp))

                // Sign out & Delete account
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showProfileSheet = false; onSignOut() }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Logout, null, tint = AppTextSecondary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Sign Out", fontSize = 15.sp, color = AppTextPrimary)
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showProfileSheet = false; showDeleteUserDialog = true }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.DeleteForever, null, tint = AppRed, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text("Delete Account", fontSize = 15.sp, color = AppRed)
                }
            }
        }
    }

    // ── Drawer ────────────────────────────────────────────────────────────────
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = AppBackground) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    // Header
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(HeroGradient)
                            .padding(horizontal = 24.dp, vertical = 28.dp)
                    ) {
                        Column {
                            if (userData?.profilePictureUrl != null) {
                                AsyncImage(
                                    model = userData.profilePictureUrl,
                                    contentDescription = "Profile picture",
                                    modifier = Modifier.size(64.dp).clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier.size(64.dp).clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(AppViolet, AppPrimary))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = userData?.username?.firstOrNull()?.uppercase() ?: "?",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                userData?.username ?: "User",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary
                            )
                            Text(
                                "My Wallet",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppTextSecondary
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))

                    // Secondary screens only — primary nav is the bottom bar
                    val drawerItems = listOf(
                        "Categories"         to Icons.Default.Category,
                        "SMS Center"         to Icons.Default.Sms,
                        "Transfer"           to Icons.Default.SwapHoriz,
                        "Savings Goals"      to Icons.Default.Star,
                        "Debts & Loans"      to Icons.Default.People,
                        "Monthly Bills"      to Icons.Default.Receipt,
                        "Calendar"           to Icons.Default.DateRange,
                        "Currency Converter" to Icons.Default.CurrencyExchange,
                        "AI Assistant"       to Icons.Default.AutoAwesome,
                        "Split Receipt"      to Icons.Default.CallSplit,
                        "Feature Tour"       to Icons.Default.Explore,
                    )
                    val drawerActions: Map<String, () -> Unit> = mapOf(
                        "Categories"         to { scope.launch { drawerState.close() }; onCategoriesClick() },
                        "SMS Center"         to { scope.launch { drawerState.close() }; onSmsClick() },
                        "Transfer"           to { scope.launch { drawerState.close() }; onTransferClick() },
                        "Savings Goals"      to { scope.launch { drawerState.close() }; onGoalsClick() },
                        "Debts & Loans"      to { scope.launch { drawerState.close() }; onDebtsClick() },
                        "Monthly Bills"      to { scope.launch { drawerState.close() }; onBillsClick() },
                        "Calendar"           to { scope.launch { drawerState.close() }; onCalendarClick() },
                        "Currency Converter" to { scope.launch { drawerState.close() }; onCurrencyConverter() },
                        "AI Assistant"       to { scope.launch { drawerState.close() }; onAiChatClick() },
                        "Split Receipt"      to { scope.launch { drawerState.close() }; onSplitReceiptClick() },
                        "Feature Tour"       to { scope.launch { drawerState.close() }; onFeatureTourClick() },
                    )
                    drawerItems.forEach { (label, icon) ->
                        NavigationDrawerItem(
                            label = { Text(label, color = AppTextPrimary) },
                            selected = false,
                            icon = { Icon(icon, contentDescription = null, tint = AppVioletLight) },
                            onClick = { drawerActions[label]?.invoke() },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedContainerColor = Color.Transparent,
                                selectedContainerColor = AppPrimary.copy(alpha = 0.15f)
                            )
                        )
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = AppPrimary.copy(alpha = 0.25f)
                    )

                    // Theme selector
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                            null, tint = AppTextSecondary, modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("Theme", color = AppTextPrimary)
                    }
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        listOf("light" to "Light", "dark" to "Dark", "system" to "System").forEachIndexed { index, (mode, label) ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { onThemeModeChange(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                                icon = {},
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = AppVioletLight.copy(alpha = 0.2f),
                                    activeContentColor = AppVioletLight,
                                    inactiveContainerColor = Color.Transparent,
                                    inactiveContentColor = AppTextSecondary
                                )
                            ) {
                                Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Fingerprint, null, tint = AppTextSecondary, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Biometric Lock", modifier = Modifier.weight(1f), color = AppTextPrimary)
                        Switch(checked = biometricEnabled, onCheckedChange = onBiometricToggle)
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        color = AppPrimary.copy(alpha = 0.25f)
                    )

                    NavigationDrawerItem(
                        label = { Text("Recalculate Balances", color = AppTextPrimary) },
                        selected = false,
                        icon = { Icon(Icons.Default.Refresh, null, tint = AppTextSecondary) },
                        onClick = {
                            scope.launch {
                                drawerState.close()
                                viewModel.recalculateAllBalances()
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                    NavigationDrawerItem(
                        label = { Text("Export Monthly Report", color = AppTextPrimary) },
                        selected = false,
                        icon = { Icon(Icons.Default.PictureAsPdf, null, tint = AppTextSecondary) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            showExportDialog = true
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                    NavigationDrawerItem(
                        label = { Text("Import CSV", color = AppTextPrimary) },
                        selected = false,
                        icon = { Icon(Icons.Default.FileUpload, null, tint = AppTextSecondary) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            val resolver = context.contentResolver
                            val projection = arrayOf(
                                MediaStore.Downloads._ID,
                                MediaStore.Downloads.DISPLAY_NAME
                            )
                            val selection = "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
                            val selArgs = arrayOf("%.csv")
                            val found = mutableListOf<Pair<String, Uri>>()
                            resolver.query(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                projection, selection, selArgs,
                                "${MediaStore.Downloads.DATE_ADDED} DESC"
                            )?.use { cursor ->
                                val idCol   = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
                                while (cursor.moveToNext()) {
                                    val id   = cursor.getLong(idCol)
                                    val name = cursor.getString(nameCol)
                                    val uri  = android.content.ContentUris.withAppendedId(
                                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id
                                    )
                                    found.add(name to uri)
                                }
                            }
                            if (found.isEmpty()) {
                                Toast.makeText(context, "No CSV files found in Downloads", Toast.LENGTH_SHORT).show()
                            } else {
                                csvFilesInDownloads = found
                                showCsvPickerDialog = true
                            }
                        },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                    NavigationDrawerItem(
                        label = { Text("Sign Out", color = AppTextPrimary) },
                        selected = false,
                        icon = { Icon(Icons.Default.Logout, null, tint = AppTextSecondary) },
                        onClick = onSignOut,
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                    NavigationDrawerItem(
                        label = { Text("Delete Account", color = AppRed) },
                        selected = false,
                        icon = { Icon(Icons.Default.DeleteForever, null, tint = AppRed) },
                        onClick = { showDeleteUserDialog = true },
                        colors = NavigationDrawerItemDefaults.colors(
                            unselectedContainerColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) {
        // ── Scaffold ─────────────────────────────────────────────────────────
        Scaffold(
            containerColor = AppBackground,
            topBar = {
                // Dark Glass top bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppBackground)
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Hamburger + greeting
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x1F7C3AED))
                                    .clickable { scope.launch { drawerState.apply { if (isClosed) open() else close() } } },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Menu, "Menu", tint = AppVioletLight, modifier = Modifier.size(18.dp))
                            }
                            Column {
                                Text(
                                    text = "Good ${timeGreeting()}",
                                    fontSize = 11.sp,
                                    color = AppTextSecondary,
                                    letterSpacing = 0.3.sp
                                )
                                Text(
                                    text = "Hi, ${userData?.username?.split(" ")?.first() ?: "there"}",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTextPrimary,
                                    letterSpacing = (-0.4).sp
                                )
                            }
                        }
                        // Bell + Avatar
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Notification bell
                            BadgedBox(
                                badge = {
                                    if (appNotifications.isNotEmpty()) {
                                        Badge(
                                            containerColor = AppRed,
                                            contentColor = Color.White
                                        ) {
                                            Text(
                                                text = if (appNotifications.size > 9) "9+" else appNotifications.size.toString(),
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0x1F7C3AED))
                                        .clickable { showNotificationsSheet = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Notifications, "Notifications", tint = AppVioletLight, modifier = Modifier.size(20.dp))
                                }
                            }
                            // Avatar (clickable → profile)
                            if (userData?.profilePictureUrl != null) {
                                AsyncImage(
                                    model = userData.profilePictureUrl,
                                    contentDescription = "Avatar",
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .shadow(8.dp, CircleShape)
                                        .clickable { showProfileSheet = true },
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Brush.linearGradient(listOf(AppViolet, AppPrimary)))
                                        .clickable { showProfileSheet = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = userData?.username?.firstOrNull()?.uppercase() ?: "?",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    viewModel.syncBalancesFromSms(context)
                },
                state = pullRefreshState,
                modifier = Modifier.padding(innerPadding)
            ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppBackground),
                contentPadding = PaddingValues(bottom = 100.dp)
            ) {

                // ── Balance Hero Card ────────────────────────────────────────
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(26.dp))
                            .background(HeroGradient)
                    ) {
                        // Decorative orbs
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .offset(x = 100.dp, y = (-60).dp)
                                .clip(CircleShape)
                                .background(Color(0x1AA78BFA))
                        )
                        Box(
                            modifier = Modifier
                                .size(130.dp)
                                .offset(x = (-20).dp, y = 60.dp)
                                .clip(CircleShape)
                                .background(Color(0x0F6366F1))
                        )
                        // Shimmer top line
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(
                                    Brush.horizontalGradient(
                                        listOf(Color.Transparent, Color(0x26FFFFFF), Color.Transparent)
                                    )
                                )
                        )

                        Column(modifier = Modifier.padding(22.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "TOTAL BALANCE",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xB3C4B5FD),
                                    letterSpacing = 2.sp
                                )
                                IconButton(
                                    onClick = { balanceVisible = !balanceVisible },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = if (balanceVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                        contentDescription = if (balanceVisible) "Hide balance" else "Show balance",
                                        tint = Color(0x80C4B5FD),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                if (balanceVisible) {
                                    AnimatedCounter(
                                        targetValue = totalBalance,
                                        durationMs = 1400,
                                        format = { String.format(Locale.getDefault(), "%,.2f", it) },
                                        style = androidx.compose.ui.text.TextStyle(
                                            fontSize = 38.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            letterSpacing = (-1.5).sp,
                                            lineHeight = 38.sp
                                        ),
                                        color = Color.White
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = " EGP",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xBFC4B5FD),
                                        modifier = Modifier.padding(bottom = 4.dp)
                                    )
                                } else {
                                    Text(
                                        text = "••••••••",
                                        fontSize = 38.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White.copy(alpha = 0.45f),
                                        letterSpacing = 4.sp
                                    )
                                }
                            }

                            // Per-currency breakdown
                            if (balanceVisible && currencyBreakdown.size > 1) {
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    currencyBreakdown
                                        .filter { it.key != "EGP" }
                                        .forEach { (currency, total) ->
                                            Text(
                                                text = "$currency ${String.format(Locale.getDefault(), "%,.2f", total)}",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = Color(0x99C4B5FD)
                                            )
                                        }
                                }
                            }

                            Spacer(Modifier.height(18.dp))

                            // Stats row
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Spent this month
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0x1AEF4444))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.TrendingDown, null, tint = AppRed, modifier = Modifier.size(11.dp))
                                            Text("SPENT", fontSize = 9.sp, color = Color(0x99C4B5FD), letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = String.format(Locale.getDefault(), "%,.0f EGP", monthlyInsight.totalExpense),
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = AppRed,
                                            letterSpacing = (-0.3).sp
                                        )
                                    }
                                }
                                // Top category
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color(0x14FDE68A))
                                        .padding(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Icon(Icons.Default.Category, null, tint = AppAmber, modifier = Modifier.size(11.dp))
                                            Text("TOP CATEGORY", fontSize = 9.sp, color = Color(0x99C4B5FD), letterSpacing = 0.5.sp, fontWeight = FontWeight.Medium)
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = monthlyInsight.topCategory.ifEmpty { "—" },
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = AppAmber,
                                            letterSpacing = (-0.3).sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Summary Cards ───────────���────────────────────────────────
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Spent Today
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(AppSurface)
                                .padding(14.dp)
                        ) {
                            Column {
                                Icon(Icons.Default.Today, null, tint = AppRed, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Spent Today", fontSize = 11.sp, color = AppTextMuted)
                                Text(
                                    String.format(Locale.getDefault(), "%,.0f", spentToday),
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary
                                )
                            }
                        }
                        // Remaining Budget
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(AppSurface)
                                .padding(14.dp)
                        ) {
                            Column {
                                Icon(Icons.Default.AccountBalance, null, tint = AppGreen, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Budget Left", fontSize = 11.sp, color = AppTextMuted)
                                Text(
                                    String.format(Locale.getDefault(), "%,.0f", remainingBudgetTotal),
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary
                                )
                            }
                        }
                        // Upcoming Bills
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(AppSurface)
                                .padding(14.dp)
                        ) {
                            Column {
                                Icon(Icons.Default.Notifications, null, tint = AppAmber, modifier = Modifier.size(20.dp))
                                Spacer(Modifier.height(6.dp))
                                Text("Bills Soon", fontSize = 11.sp, color = AppTextMuted)
                                Text(
                                    "$upcomingBillsCount",
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = AppTextPrimary
                                )
                            }
                        }
                    }
                }

                // ─��� Accounts ─────────��───────────────────────────────────────
                // ── Streak Badge ─────────────────────────────────────────────
                if (budgetStreak >= 3) {
                    item {
                        com.example.wallettrackers.components.StreakBadge(streakDays = budgetStreak)
                    }
                }

                item {
                    var draggingId by remember { mutableStateOf<String?>(null) }
                    var dragOffsetX by remember { mutableStateOf(0f) }

                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        DGSectionHeader(
                            title = "Accounts",
                            action = if (isLoading) "Loading…" else "${sortedAccounts.size} active"
                        )
                        Spacer(Modifier.height(12.dp))

                        if (isLoading) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(3) { ShimmerAccountCard() }
                            }
                        } else LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 0.dp),
                            userScrollEnabled = draggingId == null
                        ) {
                            items(sortedAccounts, key = { it.id }) { account ->
                                val isDragging = draggingId == account.id
                                Box(
                                    modifier = Modifier
                                        .offset { IntOffset(if (isDragging) dragOffsetX.roundToInt() else 0, 0) }
                                        .graphicsLayer {
                                            scaleX = if (isDragging) 1.05f else 1f
                                            scaleY = if (isDragging) 1.05f else 1f
                                            alpha  = if (isDragging) 0.85f else 1f
                                        }
                                ) {
                                    DGAccountCard(
                                        account = account,
                                        goldPriceEgpPerGram = goldPriceEgpPerGram,
                                        onClick = {},
                                        onLongClick = {
                                            selectedAccount = account
                                            showAccountOptionsDialog = true
                                        }
                                    )
                                    // ── Drag handle overlay (top strip) ──────
                                    if (sortedAccounts.size > 1) {
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopCenter)
                                                .fillMaxWidth()
                                                .height(22.dp)
                                                .pointerInput(account.id) {
                                                    val swapThreshold = 50.dp.toPx()
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            draggingId = account.id
                                                            dragOffsetX = 0f
                                                        },
                                                        onDragEnd = {
                                                            when {
                                                                dragOffsetX > swapThreshold ->
                                                                    viewModel.reorderAccount(account.id, 1)
                                                                dragOffsetX < -swapThreshold ->
                                                                    viewModel.reorderAccount(account.id, -1)
                                                            }
                                                            draggingId = null
                                                            dragOffsetX = 0f
                                                        },
                                                        onDragCancel = {
                                                            draggingId = null
                                                            dragOffsetX = 0f
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            dragOffsetX += dragAmount.x
                                                        }
                                                    )
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Default.DragHandle,
                                                contentDescription = "Drag to reorder",
                                                tint = AppTextMuted.copy(alpha = if (isDragging) 0.8f else 0.35f),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            item {
                                // Add account card
                                Box(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .background(Color(0x0F7C3AED))
                                        .clickable { showAddAccountDialog = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Brush.linearGradient(listOf(AppViolet, AppPrimary))),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(Icons.Default.Add, "Add Account", tint = Color.White, modifier = Modifier.size(18.dp))
                                        }
                                        Text("Add", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = AppVioletLight)
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Unlinked Records Banner ───────────────────────────────────
                if (unlinkedRecords.isNotEmpty()) {
                    item {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                                .clickable { showUnlinkedDialog = true },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = AppAmber.copy(alpha = 0.12f)),
                            border = BorderStroke(1.dp, AppAmber.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    Icons.Default.LinkOff,
                                    contentDescription = null,
                                    tint = AppAmber,
                                    modifier = Modifier.size(22.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${unlinkedRecords.size} record${if (unlinkedRecords.size > 1) "s" else ""} need account assignment",
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AppAmber
                                    )
                                    Text(
                                        text = "Tap to link them to the correct account",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = AppTextSecondary
                                    )
                                }
                                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AppAmber)
                            }
                        }
                    }
                }

                // ── Budgets ──────────────────────────────────────────────────
                if (budgets.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            DGSectionHeader(title = "Budgets", action = "See All", onAction = onBudgetClick)
                            Spacer(Modifier.height(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                budgets.take(3).forEach { budget ->
                                    DGBudgetRow(budget = budget, viewModel = viewModel)
                                }
                            }
                        }
                    }
                }

                // ── Active Installments ───────────────────────────────────────
                if (installments.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            DGSectionHeader(title = "Active Installments", action = "", onAction = {})
                            Spacer(Modifier.height(12.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                installments.take(5).forEach { inst ->
                                    InstallmentRow(installment = inst)
                                }
                            }
                        }
                    }
                }

                // ── Bills & Debts Summary ────────────────────────────────────
                run {
                    val todayDom = Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
                    val daysInCurrentMonth = Calendar.getInstance().getActualMaximum(Calendar.DAY_OF_MONTH)
                    val upcomingBills = bills.filter { it.isActive }.filter { bill ->
                        val daysUntil = if (bill.dayOfMonth >= todayDom) bill.dayOfMonth - todayDom
                                        else (daysInCurrentMonth - todayDom + bill.dayOfMonth)
                        daysUntil in 0..14
                    }.sortedBy { bill ->
                        if (bill.dayOfMonth >= todayDom) bill.dayOfMonth - todayDom
                        else (daysInCurrentMonth - todayDom + bill.dayOfMonth)
                    }.take(3)
                    val activeDebts = debts.filter { !it.isSettled }.take(3)
                    if (upcomingBills.isNotEmpty() || activeDebts.isNotEmpty()) {
                        item {
                            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                                DGSectionHeader(title = "Upcoming & Owed", action = "Bills", onAction = onBillsClick)
                                Spacer(Modifier.height(10.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    upcomingBills.forEach { bill ->
                                        val daysUntil = if (bill.dayOfMonth >= todayDom) bill.dayOfMonth - todayDom
                                                        else (daysInCurrentMonth - todayDom + bill.dayOfMonth)
                                        val urgencyColor = when {
                                            daysUntil <= 2 -> AppRed
                                            daysUntil <= 5 -> AppAmber
                                            else -> AppGreen
                                        }
                                        val dueText = when (daysUntil) {
                                            0 -> "Due today"
                                            1 -> "Due tomorrow"
                                            else -> "Due in $daysUntil days"
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(AppSurface)
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Icon(Icons.Default.Receipt, null, tint = urgencyColor, modifier = Modifier.size(16.dp))
                                                Column {
                                                    Text(bill.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
                                                    Text(dueText, style = MaterialTheme.typography.labelSmall, color = urgencyColor)
                                                }
                                            }
                                            Text("${String.format("%.0f", bill.amount)} ${bill.currency}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = urgencyColor)
                                        }
                                    }
                                    activeDebts.forEach { debt ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(AppSurface)
                                                .padding(horizontal = 14.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                Icon(Icons.Default.People, null, tint = if (debt.isOwedToMe) AppGreen else AppRed, modifier = Modifier.size(16.dp))
                                                Column {
                                                    Text(debt.personName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = AppTextPrimary)
                                                    Text(if (debt.isOwedToMe) "Owes you" else "You owe", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                                                }
                                            }
                                            Text("${String.format("%.0f", debt.amount)} ${debt.currency}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (debt.isOwedToMe) AppGreen else AppRed)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Spending Forecast ─────────────────────────────────────────
                if (spendingForecast.spentSoFar > 0) {
                    item {
                        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                            DGSectionHeader(title = "Spending Forecast")
                            Spacer(Modifier.height(10.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(AppSurface)
                                    .padding(18.dp)
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column {
                                            Text("Spent so far", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                                            Text(
                                                String.format("%.0f %s", spendingForecast.spentSoFar, spendingForecast.currency),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = AppTextPrimary
                                            )
                                        }
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text("Projected total", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                                            Text(
                                                String.format("%.0f %s", spendingForecast.projectedTotal, spendingForecast.currency),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = AppAmber
                                            )
                                        }
                                    }
                                    val progress = (spendingForecast.spentSoFar / spendingForecast.projectedTotal.coerceAtLeast(1.0)).toFloat().coerceIn(0f, 1f)
                                    LinearProgressIndicator(
                                        progress = { progress },
                                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                                        color = AppAmber,
                                        trackColor = AppBackground
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            String.format("%.0f %s/day", spendingForecast.dailyBurnRate, spendingForecast.currency),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppTextSecondary
                                        )
                                        Text(
                                            "${spendingForecast.daysRemaining} days remaining",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Spending Insights ─────────────────────────────────────────
                if (spendingInsights.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                "Insights",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextMuted,
                                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                contentPadding = PaddingValues(horizontal = 20.dp)
                            ) {
                                items(spendingInsights.size) { index ->
                                    com.example.wallettrackers.components.InsightCard(spendingInsights[index])
                                }
                            }
                        }
                    }
                }

                // ── Cash Flow Forecast ────────────────────────────────────────
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = AppSurface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = AppVioletLight,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Text(
                                        text = "Cash Flow Forecast",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.generateCashFlowPrediction() },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Refresh forecast",
                                        tint = AppVioletLight,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            when {
                                isCashFlowLoading -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(28.dp),
                                            color = AppPrimary,
                                            strokeWidth = 2.5.dp
                                        )
                                    }
                                }
                                cashFlowPrediction != null -> {
                                    Text(
                                        text = cashFlowPrediction!!,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = AppTextSecondary,
                                        lineHeight = 20.sp
                                    )
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        TextButton(
                                            onClick = { viewModel.generateCashFlowPrediction() }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoAwesome,
                                                contentDescription = null,
                                                tint = AppPrimary,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "Generate Forecast",
                                                color = AppPrimary,
                                                fontWeight = FontWeight.Medium
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Spending Charts ───────────────────────────────────────────
                item {
                    var chartTab by remember { mutableStateOf(0) }
                    Column(
                        modifier = Modifier
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Toggle tabs
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppSurface)
                                .padding(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf("Weekly", "Monthly").forEachIndexed { index, label ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (chartTab == index) AppPrimary.copy(alpha = 0.2f) else Color.Transparent)
                                        .clickable { chartTab = index }
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        fontSize = 13.sp,
                                        fontWeight = if (chartTab == index) FontWeight.Bold else FontWeight.Normal,
                                        color = if (chartTab == index) AppPrimary else AppTextSecondary
                                    )
                                }
                            }
                        }

                        // Chart content
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(AppSurface)
                                .padding(20.dp)
                        ) {
                            if (chartTab == 0) {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(16.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(AccentGradient)
                                        )
                                        Text(
                                            "7-Day Spending Trend",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTextPrimary
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "Today highlighted",
                                            fontSize = 10.sp,
                                            color = AppVioletLight
                                        )
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    HomeWeeklyChart(
                                        records = records,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            } else {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .width(3.dp)
                                                .height(16.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(AccentGradient)
                                        )
                                        Text(
                                            "30-Day Spending Trend",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTextPrimary
                                        )
                                    }
                                    Spacer(Modifier.height(16.dp))
                                    com.example.wallettrackers.components.MonthlyTrendChart(
                                        dailyData = dailySpendingLast30,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // ── Category donut chart ─────────────────────────────
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(20.dp))
                                .background(AppSurface)
                                .padding(20.dp)
                        ) {
                            Column {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .width(3.dp)
                                            .height(16.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(AccentGradient)
                                    )
                                    Text(
                                        "Spending by Category",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = AppTextPrimary
                                    )
                                    Spacer(Modifier.weight(1f))
                                    Text(
                                        "This month",
                                        fontSize = 10.sp,
                                        color = AppTextSecondary
                                    )
                                }
                                Spacer(Modifier.height(16.dp))
                                HomeCategoryDonutChart(
                                    records = records,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }

                // ── Recent Records ────────────────────────────────────────────
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        DGSectionHeader(title = "Recent Records", action = "See All", onAction = onSeeAllRecords)
                        Spacer(Modifier.height(12.dp))

                        if (records.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(AppSurface)
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No records yet. Tap + to add one.", fontSize = 13.sp, color = AppTextSecondary, textAlign = TextAlign.Center)
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(AppSurface)
                            ) {
                                Column {
                                    records.take(5).forEachIndexed { idx, record ->
                                        DGRecordRow(
                                            record = record,
                                            isLast = idx == minOf(4, records.size - 1),
                                            onLongClick = {
                                                optionSelectedRecord = record
                                                showRecordOptionsDialog = true
                                            },
                                            isUnusual = record.id in unusualRecordIds
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── Quick action buttons ───────────────────────────────────────
                item {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        DGSectionHeader(title = "Quick Actions")
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            listOf(
                                Triple("Transfer", Icons.Default.SwapHoriz, onTransferClick),
                                Triple("Statistics", Icons.Default.BarChart, onStatisticsClick),
                                Triple("Goals", Icons.Default.Star, onGoalsClick),
                                Triple("Bills", Icons.Default.Receipt, onBillsClick),
                            ).forEachIndexed { idx, (label, icon, action) ->
                                SpringQuickActionButton(
                                    label = label,
                                    icon = icon,
                                    onClick = action,
                                    modifier = Modifier.weight(1f),
                                    delayMs = idx * 80
                                )
                            }
                        }
                    }
                }
            }
            } // end PullToRefreshBox
        }
    }
}

// ─── Shimmer Card ─────────────────────────────────────────────────────────────
@Composable
private fun ShimmerAccountCard() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shimmerX by transition.animateFloat(
        initialValue = -300f, targetValue = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerX"
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(AppSurface, Color(0xFF2E2E45), AppSurface),
        start = Offset(shimmerX, 0f),
        end = Offset(shimmerX + 300f, 0f)
    )
    Box(
        modifier = Modifier
            .width(110.dp)
            .height(130.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(shimmerBrush)
    )
}

// ─── Account Card ─────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DGAccountCard(
    account: Account,
    goldPriceEgpPerGram: Double?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val accentColor = longToColor(account.color)
    val isCredit = account.accountType.contains("Credit", ignoreCase = true)
    val isGold   = account.accountType.equals("Gold", ignoreCase = true)

    val currencyLabel = when {
        account.currency.contains("Dollar", ignoreCase = true) -> "USD"
        account.currency.contains("Euro",   ignoreCase = true) -> "EUR"
        account.currency.contains("Pound",  ignoreCase = true) || account.currency.equals("GBP", ignoreCase = true) -> "GBP"
        else -> account.currency
    }

    val displayAmount = account.amount

    val utilizationPct: Float
    val utilizationColor: Color
    if (isCredit && account.creditLimit != null && account.creditLimit > 0) {
        val avail = account.amount.toDoubleOrNull() ?: 0.0
        val used = (account.creditLimit - avail).coerceAtLeast(0.0)
        utilizationPct = (used / account.creditLimit).coerceIn(0.0, 1.0).toFloat()
        utilizationColor = when {
            utilizationPct >= 0.9f -> Color(0xFFEF4444)
            utilizationPct >= 0.7f -> Color(0xFFF59E0B)
            else -> accentColor
        }
    } else {
        utilizationPct = 0f
        utilizationColor = accentColor
    }
    val animatedUtil by animateFloatAsState(
        targetValue = utilizationPct,
        animationSpec = tween(durationMillis = 700),
        label = "util_${account.id}"
    )

    Box(
        modifier = Modifier
            .width(110.dp)
            .height(if (isCredit && account.creditLimit != null) 145.dp else 130.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.radialGradient(
                    colors = listOf(accentColor.copy(alpha = 0.13f), AppSurface),
                    radius = 200f
                )
            )
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        // Colored top bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(accentColor)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = accountTypeIcon(account.accountType),
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(15.dp)
                )
            }

            // Name + balance
            Column {
                Text(account.name, fontSize = 10.sp, color = AppTextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = displayAmount,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = AppTextPrimary,
                    letterSpacing = (-0.3).sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isGold) {
                    val grams = account.amount.toDoubleOrNull() ?: 0.0
                    Text(
                        text = if (goldPriceEgpPerGram != null)
                            "≈${String.format(Locale.getDefault(), "%,.0f", grams * goldPriceEgpPerGram)}"
                        else "عيار 24",
                        fontSize = 9.sp, color = AppTextMuted
                    )
                } else if (isCredit) {
                    Text("Avail. $currencyLabel", fontSize = 9.sp, color = AppTextMuted)
                } else {
                    Text(currencyLabel, fontSize = 9.sp, color = AppTextMuted)
                }
                if (isCredit && account.creditLimit != null && account.creditLimit > 0) {
                    Spacer(Modifier.height(5.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(utilizationColor.copy(alpha = 0.18f))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedUtil)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(utilizationColor)
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "${(utilizationPct * 100).toInt()}% used",
                        fontSize = 8.sp,
                        color = utilizationColor
                    )
                }
            }
        }
    }
}

// ─── Budget Row ───────────────────────────────────────────────────────────────
@Composable
private fun DGBudgetRow(budget: Budget, viewModel: HomeViewModel) {
    val spent = viewModel.currentMonthSpendForCategory(budget.category)
    val pct   = if (budget.monthlyLimit > 0) (spent / budget.monthlyLimit).coerceIn(0.0, 1.0).toFloat() else 0f
    val over  = spent > budget.monthlyLimit

    val barColor = if (over) Color(0xFFEF4444) else Color(0xFF6366F1)
    val glowColor = if (over) Color(0x40EF4444) else Color(0x406366F1)

    val animPct by animateFloatAsState(
        targetValue = pct,
        animationSpec = tween(durationMillis = 600),
        label = "budget_${budget.category}"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = budget.category,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${String.format(Locale.getDefault(), "%,.0f", spent)} / ${String.format(Locale.getDefault(), "%,.0f", budget.monthlyLimit)}",
                        fontSize = 10.sp,
                        color = if (over) AppRed else AppTextSecondary
                    )
                    Text(
                        text = "${(pct * 100).toInt()}%",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (over) AppRed else AppVioletLight
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(99.dp))
                    .background(Color(0x40334155))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animPct)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(99.dp))
                        .background(barColor)
                        .shadow(4.dp, RoundedCornerShape(99.dp), spotColor = glowColor, ambientColor = glowColor)
                )
            }
        }
    }
}

// ─── Installment Row ──────────────────────────────────────────────────────────
@Composable
private fun InstallmentRow(installment: com.example.wallettrackers.util.FinancialCalculator.InstallmentSeries) {
    val animPct by animateFloatAsState(
        targetValue = installment.progressFraction,
        animationSpec = tween(700), label = "inst_${installment.label}"
    )
    val barColor = when {
        installment.progressFraction >= 0.8f -> AppGreen
        installment.progressFraction >= 0.5f -> Color(0xFFF59E0B)
        else -> AppPrimary
    }
    Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AppSurface).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(installment.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = AppTextPrimary,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${installment.paid}/${installment.total}",
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, color = barColor)
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(barColor.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(animPct).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(barColor))
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${installment.remaining} instalment${if (installment.remaining != 1) "s" else ""} remaining · " +
                "${String.format(java.util.Locale.getDefault(), "%,.0f", installment.monthlyAmount)} ${installment.currency}/mo",
                fontSize = 11.sp, color = AppTextSecondary
            )
        }
    }
}

// ─── Record Row ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DGRecordRow(
    record: Record,
    isLast: Boolean,
    onLongClick: () -> Unit,
    isUnusual: Boolean = false
) {
    val isIncome = record.type == "Income"
    val iconColor = if (isIncome) AppGreen else AppRed
    val iconBg    = if (isIncome) Color(0x1A34D399) else Color(0x1AF87171)
    val amountColor = if (isIncome) AppGreen else AppTextPrimary
    val categoryColor = Categories.list.flatMap { it.subCategories + it }
        .find { it.name == record.category }?.color ?: AppVioletLight

    val sdf = remember { SimpleDateFormat("dd MMM", Locale.getDefault()) }
    val dateStr = try { sdf.format(record.timestamp) } catch (_: Exception) { "" }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Icon circle
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(iconBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isIncome) Icons.Default.TrendingUp else Icons.Default.TrendingDown,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(17.dp)
            )
        }

        // Name + subtitle
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = record.accountName.ifEmpty { record.category },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = AppTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isUnusual) Icon(Icons.Default.Warning, null, tint = Color(0xFFF59E0B), modifier = Modifier.size(12.dp))
            }
            Text(
                text = "${record.category.ifEmpty { "—" }} · $dateStr",
                fontSize = 11.sp,
                color = categoryColor
            )
        }

        // Amount
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "${if (isIncome) "+" else "-"}${record.amount}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = amountColor
            )
            Text(
                text = record.currency,
                fontSize = 9.sp,
                color = AppTextMuted
            )
        }
    }

    if (!isLast) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .height(1.dp)
                .background(Color(0xFF1E1B4B))
        )
    }
}

// ─── Spring Quick Action Button ───────────────────────────────────────────────
@Composable
private fun SpringQuickActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    delayMs: Int = 0
) {
    var visible by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(delayMs.toLong())
        visible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "qa_scale_$label"
    )

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 2 },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(RoundedCornerShape(16.dp))
                .background(AppSurface)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                            onClick()
                        }
                    )
                }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppViolet.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, null, tint = AppVioletLight, modifier = Modifier.size(18.dp))
                }
                Text(label, fontSize = 10.sp, color = AppTextSecondary, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ─── Time greeting helper ─────────────────────────────────────────────────────
private fun timeGreeting(): String {
    return when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..11  -> "morning"
        in 12..16 -> "afternoon"
        else      -> "evening"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Dialog composables — preserved from original implementation
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun AccountOptionsDialog(
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onArchive: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AppSurface)
                .padding(8.dp)
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("Edit Account", color = AppTextPrimary) },
                    leadingContent = { Icon(Icons.Default.Edit, null, tint = AppPrimary) },
                    modifier = Modifier.clickable { onEdit() }.clip(RoundedCornerShape(8.dp)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ListItem(
                    headlineContent = { Text("Archive Account", color = AppTextPrimary) },
                    leadingContent = { Icon(Icons.Default.Archive, null, tint = AppTextSecondary) },
                    modifier = Modifier.clickable { onArchive() }.clip(RoundedCornerShape(8.dp)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                HorizontalDivider(color = AppPrimary.copy(alpha = 0.15f), modifier = Modifier.padding(horizontal = 8.dp))
                ListItem(
                    headlineContent = { Text("Delete Account", color = AppRed) },
                    leadingContent = { Icon(Icons.Default.Delete, null, tint = AppRed) },
                    modifier = Modifier.clickable { onDelete() }.clip(RoundedCornerShape(8.dp)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    text: String
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(AppSurface)
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {

                // Icon badge
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(AppRed.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        tint = AppRed,
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(28.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.5f))
                    ) {
                        Text("Cancel", color = AppTextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppRed)
                    ) {
                        Text("Delete", color = AppBackground, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDialog(
    account: Account? = null,
    existingColors: List<Long> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (Account) -> Unit,
    title: String,
    confirmButtonText: String
) {
    var name              by rememberSaveable { mutableStateOf(account?.name ?: "") }
    var accountType       by rememberSaveable { mutableStateOf(account?.accountType ?: "Debit") }
    var last4Digits       by rememberSaveable { mutableStateOf(account?.last4Digits ?: "") }
    var amount            by rememberSaveable { mutableStateOf(account?.amount ?: "") }
    var creditLimit       by rememberSaveable { mutableStateOf(account?.creditLimit?.toString() ?: "") }
    var billingDay        by rememberSaveable { mutableStateOf(account?.billingDay?.toString() ?: "") }
    var currency          by rememberSaveable { mutableStateOf(account?.currency ?: "EGP") }
    var expandedType      by remember { mutableStateOf(false) }
    var expandedCurrency  by remember { mutableStateOf(false) }

    val assignedColor = remember(account, existingColors) {
        if (account != null) longToColor(account.color) else pickAutoColor(existingColors)
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AppPrimary,
        unfocusedBorderColor = AppPrimary.copy(alpha = 0.4f),
        focusedLabelColor = AppPrimary,
        unfocusedLabelColor = AppTextSecondary,
        focusedTextColor = AppTextPrimary,
        unfocusedTextColor = AppTextPrimary,
        focusedContainerColor = AppBackground,
        unfocusedContainerColor = AppBackground,
        focusedTrailingIconColor = AppPrimary,
        unfocusedTrailingIconColor = AppTextSecondary,
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(AppSurface)
        ) {
            LazyColumn(modifier = Modifier.padding(24.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(16.dp).clip(CircleShape).background(assignedColor))
                        Spacer(Modifier.width(10.dp))
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(value = name, onValueChange = { name = it },
                        label = { Text("Account Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                    Spacer(Modifier.height(10.dp))
                    ExposedDropdownMenuBox(expanded = expandedType, onExpandedChange = { expandedType = !expandedType }) {
                        OutlinedTextField(value = accountType, onValueChange = {}, label = { Text("Account Type") },
                            readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedType) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                        ExposedDropdownMenu(expanded = expandedType, onDismissRequest = { expandedType = false },
                            containerColor = AppSurface) {
                            listOf("Debit", "Credit Card", "Cash", "Gold").forEach { type ->
                                DropdownMenuItem(text = { Text(type, color = AppTextPrimary) }, onClick = { accountType = type; expandedType = false })
                            }
                        }
                    }
                    if (accountType != "Cash" && accountType != "Gold") {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = last4Digits, onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) last4Digits = it },
                            label = { Text("Last 4 Digits") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                    }
                    Spacer(Modifier.height(10.dp))
                    if (accountType == "Credit Card") {
                        OutlinedTextField(value = creditLimit, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) creditLimit = it },
                            label = { Text("Credit Limit") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = amount, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                            label = { Text("Available Credit") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(value = billingDay, onValueChange = { val n = it.toIntOrNull(); if (it.isEmpty() || (n != null && n in 1..31)) billingDay = it },
                            label = { Text("Statement Day (1–31)") }, placeholder = { Text("e.g. 15", color = AppTextSecondary) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                    } else {
                        OutlinedTextField(value = amount, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                            label = { Text(if (accountType == "Gold") "Weight in Grams" else "Current Balance") },
                            placeholder = { if (accountType == "Gold") Text("e.g. 50.5", color = AppTextSecondary) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                    }
                    if (accountType != "Gold") {
                        Spacer(Modifier.height(10.dp))
                        ExposedDropdownMenuBox(expanded = expandedCurrency, onExpandedChange = { expandedCurrency = !expandedCurrency }) {
                            OutlinedTextField(value = currency, onValueChange = {}, label = { Text("Currency") },
                                readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expandedCurrency) },
                                modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                            ExposedDropdownMenu(expanded = expandedCurrency, onDismissRequest = { expandedCurrency = false },
                                containerColor = AppSurface) {
                                listOf("EGP", "USD", "EUR", "GBP", "SAR", "AED").forEach { cur ->
                                    DropdownMenuItem(text = { Text(cur, color = AppTextPrimary) }, onClick = { currency = cur; expandedCurrency = false })
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.5f))
                        ) { Text("Cancel", color = AppTextPrimary) }
                        Button(
                            onClick = {
                                val colorLong = colorToLong(assignedColor)
                                val digits = if (accountType == "Cash" || accountType == "Gold") "" else last4Digits
                                val finalCurrency = if (accountType == "Gold") "XAU" else currency
                                val parsedBillingDay = billingDay.toIntOrNull()
                                val updatedAccount = account?.copy(
                                    name = name, accountType = accountType, last4Digits = digits, amount = amount,
                                    creditLimit = if (accountType == "Credit Card") creditLimit.toDoubleOrNull() else null,
                                    billingDay = if (accountType == "Credit Card") parsedBillingDay else null,
                                    currency = finalCurrency, color = colorLong
                                ) ?: Account(
                                    name = name, accountType = accountType, last4Digits = digits, amount = amount,
                                    creditLimit = if (accountType == "Credit Card") creditLimit.toDoubleOrNull() else null,
                                    billingDay = if (accountType == "Credit Card") parsedBillingDay else null,
                                    currency = finalCurrency, color = colorLong
                                )
                                onConfirm(updatedAccount)
                                onDismiss()
                            },
                            enabled = name.isNotBlank() && amount.isNotBlank() &&
                                    (accountType == "Cash" || accountType == "Gold" || last4Digits.length == 4) &&
                                    (accountType != "Credit Card" || creditLimit.isNotBlank()),
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                        ) { Text(confirmButtonText, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDialog(
    record: Record? = null,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (Record) -> Unit,
    onCategoryClick: () -> Unit = {},
    title: String,
    confirmButtonText: String
) {
    var selectedAccount by remember(record) { mutableStateOf(accounts.find { it.id == record?.accountId }) }
    var category        by remember(record?.category) { mutableStateOf(record?.category ?: "") }
    var amount          by remember(record?.amount) { mutableStateOf(record?.amount ?: "") }
    var comment         by remember(record?.comment) { mutableStateOf(record?.comment ?: "") }
    var expanded        by remember { mutableStateOf(false) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AppPrimary,
        unfocusedBorderColor = AppPrimary.copy(alpha = 0.4f),
        focusedLabelColor = AppPrimary,
        unfocusedLabelColor = AppTextSecondary,
        focusedTextColor = AppTextPrimary,
        unfocusedTextColor = AppTextPrimary,
        focusedContainerColor = AppBackground,
        unfocusedContainerColor = AppBackground,
        focusedTrailingIconColor = AppPrimary,
        unfocusedTrailingIconColor = AppTextSecondary,
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(AppSurface)
                .padding(24.dp)
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                Spacer(Modifier.height(16.dp))
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(value = selectedAccount?.name ?: "", onValueChange = {}, label = { Text("Account") },
                        readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                        containerColor = AppSurface) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(text = { Text(acc.name, color = AppTextPrimary) }, onClick = { selectedAccount = acc; expanded = false })
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Surface(
                    onClick = onCategoryClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = AppBackground,
                    border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.4f))
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            if (category.isNotEmpty()) category else "Select Category",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (category.isNotEmpty()) AppTextPrimary else AppTextSecondary
                        )
                        Icon(Icons.Default.Category, null, tint = AppPrimary)
                    }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = amount, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                    label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = fieldColors)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = comment, onValueChange = { comment = it },
                    label = { Text("Comment (optional)") }, modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp), colors = fieldColors)
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.5f))
                    ) { Text("Cancel", color = AppTextPrimary) }
                    Button(
                        onClick = {
                            selectedAccount?.let {
                                val updated = record?.copy(accountId = it.id, accountName = it.name,
                                    category = category, amount = amount, currency = it.currency, comment = comment)
                                    ?: Record(accountId = it.id, accountName = it.name,
                                        category = category, amount = amount, currency = it.currency, comment = comment)
                                onConfirm(updated); onDismiss()
                            }
                        },
                        enabled = selectedAccount != null && category.isNotBlank() && amount.isNotBlank(),
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                    ) { Text(confirmButtonText, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
fun OptionsDialog(
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onSaveAsRule: () -> Unit,
    onDelete: () -> Unit,
    onAttachReceipt: (() -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(AppSurface)
                .padding(8.dp)
        ) {
            Column {
                ListItem(
                    headlineContent = { Text("Edit", color = AppTextPrimary) },
                    leadingContent = { Icon(Icons.Default.Edit, null, tint = AppPrimary) },
                    modifier = Modifier.clickable { onEdit() }.clip(RoundedCornerShape(8.dp)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                ListItem(
                    headlineContent = { Text("Save as Category Rule", color = AppTextPrimary) },
                    leadingContent = { Icon(Icons.Default.Bookmark, null, tint = AppVioletLight) },
                    modifier = Modifier.clickable { onSaveAsRule() }.clip(RoundedCornerShape(8.dp)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
                if (onAttachReceipt != null) {
                    ListItem(
                        headlineContent = { Text("Attach Receipt", color = AppTextPrimary) },
                        leadingContent = { Icon(Icons.Default.Receipt, null, tint = AppGreen) },
                        modifier = Modifier.clickable { onAttachReceipt() }.clip(RoundedCornerShape(8.dp)),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                    )
                }
                HorizontalDivider(color = AppPrimary.copy(alpha = 0.15f), modifier = Modifier.padding(horizontal = 8.dp))
                ListItem(
                    headlineContent = { Text("Delete", color = AppRed) },
                    leadingContent = { Icon(Icons.Default.Delete, null, tint = AppRed) },
                    modifier = Modifier.clickable { onDelete() }.clip(RoundedCornerShape(8.dp)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}
