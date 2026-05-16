package com.example.wallettrackers.screens

import android.widget.Toast
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.wallettrackers.converters.longToColor
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.remote.ExchangeRateApi
import com.example.wallettrackers.util.FinancialCalculator
import com.example.wallettrackers.ui.theme.*
import com.example.wallettrackers.components.CategoryDonutChart
import com.example.wallettrackers.components.ChartPalette
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

enum class StatisticsTab(val label: String) {
    BALANCE("Balance"),
    SPENDING("Spending"),
    NET_WORTH("Net Worth"),
    CREDIT("Credit"),
    REPORTS("Reports")
}

enum class TimeRange(val label: String) {
    LAST_DAY("Last Day"),
    LAST_WEEK("Last Week"),
    LAST_MONTH("Last Month"),
    LAST_YEAR("Last Year"),
    ALL_TIME("All Time")
}

private fun parseAmount(amountStr: String)                          = FinancialCalculator.parseAmount(amountStr)
private fun getCurrencyType(currency: String, accountName: String) = FinancialCalculator.getCurrencyType(currency, accountName)
private fun convertToEGP(amount: Double, currency: String, accountName: String, usdRate: Double, eurRate: Double) =
    FinancialCalculator.convertToEGP(amount, currency, accountName, usdRate, eurRate)
private fun isExcludedFromSpending(record: Record)                 = FinancialCalculator.isExcludedFromSpending(record)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    accounts: List<Account>,
    records: List<Record>,
    statements: List<CreditStatement> = emptyList(),
    toastMessage: String? = null,
    onToastShown: () -> Unit = {},
    onPayClick: (CreditStatement, Account) -> Unit = { _, _ -> },
    onMarkPaidNoAccount: (CreditStatement) -> Unit = {},
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(StatisticsTab.SPENDING) }
    val exchangeRateApi = remember { ExchangeRateApi.create() }
    var usdToEgpRate by remember { mutableStateOf(50.0) }
    var eurToEgpRate by remember { mutableStateOf(53.0) }
    var goldPriceEgpPerGram by remember { mutableStateOf<Double?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var statementToPay by remember { mutableStateOf<CreditStatement?>(null) }
    var showAccountPicker by remember { mutableStateOf(false) }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            onToastShown()
        }
    }

    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("wallet_prefs", android.content.Context.MODE_PRIVATE)
        prefs.getFloat("usdToEgpRate", 0f).takeIf { it > 0f }?.let { usdToEgpRate = it.toDouble() }
        prefs.getFloat("eurToEgpRate", 0f).takeIf { it > 0f }?.let { eurToEgpRate = it.toDouble() }
        scope.launch {
            try {
                val usdResponse = exchangeRateApi.getLatestRates("USD")
                usdResponse.rates["EGP"]?.let {
                    usdToEgpRate = it
                    prefs.edit().putFloat("usdToEgpRate", it.toFloat()).apply()
                }
                val eurResponse = exchangeRateApi.getLatestRates("EUR")
                eurResponse.rates["EGP"]?.let {
                    eurToEgpRate = it
                    prefs.edit().putFloat("eurToEgpRate", it.toFloat()).apply()
                }
            } catch (e: Exception) {
                // Keep cached/default rates if fetch fails
            }
            try {
                val goldUsdPerOz = exchangeRateApi.getGoldPriceUSD()
                if (goldUsdPerOz != null) {
                    goldPriceEgpPerGram = goldUsdPerOz * usdToEgpRate / 31.1035
                }
            } catch (_: Exception) {}
        }
    }

    if (showAccountPicker && statementToPay != null) {
        val debitAccounts = accounts.filter { it.accountType.lowercase() == "debit" || it.accountType.lowercase() == "cash" }
        AccountSelectionDialog(
            accounts = debitAccounts,
            onDismiss = {
                showAccountPicker = false
                statementToPay = null
            },
            onAccountSelected = { account ->
                val s = statementToPay
                if (s != null) {
                    if (account != null) onPayClick(s, account)
                    else onMarkPaidNoAccount(s)
                }
                showAccountPicker = false
                statementToPay = null
            }
        )
    }

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(AppBackground)) {
                TopAppBar(
                    title = { Text("Statistics", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
                )
                SecondaryTabRow(
                    selectedTabIndex = selectedTab.ordinal,
                    containerColor = AppBackground,
                    contentColor = AppVioletLight,
                    indicator = {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(selectedTab.ordinal),
                            color = AppVioletLight
                        )
                    }
                ) {
                    StatisticsTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { 
                                Text(
                                    tab.label, 
                                    color = if (selectedTab == tab) AppVioletLight else AppTextSecondary,
                                    fontWeight = if (selectedTab == tab) FontWeight.Bold else FontWeight.Normal
                                ) 
                            }
                        )
                    }
                }
            }
        },
        containerColor = AppBackground
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize().background(AppBackground)) {
            when (selectedTab) {
                StatisticsTab.BALANCE -> {
                    BalanceTabContent(
                        accounts = accounts,
                        usdRate = usdToEgpRate,
                        eurRate = eurToEgpRate,
                        goldPriceEgpPerGram = goldPriceEgpPerGram,
                        records = records
                    )
                }
                StatisticsTab.SPENDING -> {
                    SpendingTabContent(records = records)
                }
                StatisticsTab.NET_WORTH -> {
                    NetWorthTabContent(
                        accounts = accounts,
                        records = records,
                        usdRate = usdToEgpRate,
                        eurRate = eurToEgpRate,
                        goldPriceEgpPerGram = goldPriceEgpPerGram
                    )
                }
                StatisticsTab.CREDIT -> {
                    CreditTabContent(
                        statements = statements, 
                        onPayClick = { 
                            statementToPay = it
                            showAccountPicker = true
                        }
                    )
                }
                StatisticsTab.REPORTS -> {
                    ReportsTabContent(records = records, usdRate = usdToEgpRate, eurRate = eurToEgpRate)
                }
            }
        }
    }
}

@Composable
fun AccountSelectionDialog(
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onAccountSelected: (Account?) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            colors = CardDefaults.cardColors(containerColor = AppSurface)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Select Payment Account",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
                Spacer(Modifier.height(16.dp))
                if (accounts.isEmpty()) {
                    Text("No debit or cash accounts found.", style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(accounts) { account ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AppBackground.copy(alpha = 0.5f))
                                    .clickable { onAccountSelected(account) }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(longToColor(account.color).copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Payments,
                                        contentDescription = null,
                                        tint = longToColor(account.color),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                Spacer(Modifier.width(14.dp))
                                Column {
                                    Text(account.name, fontWeight = FontWeight.Bold, color = AppTextPrimary)
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
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = AppTextSecondary.copy(alpha = 0.12f))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppBackground.copy(alpha = 0.5f))
                        .clickable { onAccountSelected(null) }
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppGreen.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = AppGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Mark as Paid", fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        Text("No account deduction", style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                    }
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel", color = AppVioletLight)
                }
            }
        }
    }
}

@Composable
fun CreditTabContent(statements: List<CreditStatement>, onPayClick: (CreditStatement) -> Unit) {
    val unpaidStatements = remember(statements) {
        statements.filter { !it.isPaid }.sortedBy { it.dueDate }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.width(3.dp).height(20.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                Column {
                    Text(
                        "Credit Card Statements",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                    Text(
                        "Auto-extracted from your SMS bank alerts",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary
                    )
                }
            }
        }

        if (unpaidStatements.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CreditCard, null, modifier = Modifier.size(64.dp), tint = AppPrimary.copy(alpha = 0.3f))
                        Spacer(Modifier.height(16.dp))
                        Text("No pending credit card statements.", color = AppTextSecondary)
                    }
                }
            }
        } else {
            items(unpaidStatements) { statement ->
                CreditCardAlertItem(statement, onPayClick)
            }
        }
    }
}

@Composable
fun CreditCardAlertItem(statement: CreditStatement, onPayClick: (CreditStatement) -> Unit) {
    val daysLeft = remember(statement.dueDate) {
        val diff = statement.dueDate.time - System.currentTimeMillis()
        TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS)
    }

    val statusColor = when {
        daysLeft < 0 -> AppRed
        daysLeft <= 3 -> AppAmber
        else -> AppGreen
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppViolet.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.CreditCard, null, tint = AppVioletLight, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Card Ending ****${statement.cardLast4Digits}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppTextPrimary
                    )
                }
                if (!statement.isPaid) {
                    Surface(
                        color = statusColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when {
                                daysLeft < 0 -> "Overdue"
                                daysLeft == 0L -> "Due Today"
                                else -> "In $daysLeft days"
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Total Amount Due", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                    Text(
                        String.format(Locale.getDefault(), "%,.2f EGP", statement.totalAmount),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = AppTextPrimary,
                        letterSpacing = (-0.5).sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Due Date", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                    Text(
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(statement.dueDate),
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, size = 16.dp, tint = AppPrimaryLight)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Reminders active",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppPrimaryLight
                    )
                }
                
                if (!statement.isPaid) {
                    Button(
                        onClick = { onPayClick(statement) },
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                    ) {
                        Text("Pay Now", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun Icon(icon: ImageVector, contentDescription: String?, size: androidx.compose.ui.unit.Dp, tint: Color) {
    androidx.compose.material3.Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = Modifier.size(size),
        tint = tint
    )
}

@Composable
fun ReportsTabContent(records: List<Record>, usdRate: Double, eurRate: Double) {
    val calendar = Calendar.getInstance()
    val currentMonth = calendar.get(Calendar.MONTH)
    val currentYear = calendar.get(Calendar.YEAR)

    var selectedMonth by remember { mutableStateOf(currentMonth) }
    var selectedYear by remember { mutableStateOf(currentYear) }
    var expandedMonth by remember { mutableStateOf(false) }
    var expandedYear by remember { mutableStateOf(false) }

    val monthNames = SimpleDateFormat("MMMM", Locale.getDefault()).let { df ->
        (0..11).map {
            calendar.set(Calendar.MONTH, it)
            df.format(calendar.time)
        }
    }

    val years = (currentYear - 5..currentYear).toList().reversed()

    val filteredRecords = remember(records, selectedMonth, selectedYear) {
        records.filter {
            val recCal = Calendar.getInstance()
            recCal.time = it.timestamp
            recCal.get(Calendar.MONTH) == selectedMonth && recCal.get(Calendar.YEAR) == selectedYear
        }
    }

    val incomeRecords = filteredRecords.filter { it.type == "Income" }
    val expenseRecords = filteredRecords.filter { it.type == "Expense" && !isExcludedFromSpending(it) }
    val transferRecords = filteredRecords.filter { it.category == "Transfer" || it.category == "Credit Payment" }

    val totalIncomeEGP = incomeRecords.sumOf { convertToEGP(parseAmount(it.amount), it.currency, it.accountName, usdRate, eurRate) }
    val totalExpenseEGP = expenseRecords.sumOf { convertToEGP(parseAmount(it.amount), it.currency, it.accountName, usdRate, eurRate) }
    val totalTransferEGP = transferRecords.sumOf { convertToEGP(parseAmount(it.amount), it.currency, it.accountName, usdRate, eurRate) }
    val netBalanceEGP = totalIncomeEGP - totalExpenseEGP

    val categoryTotals = remember(expenseRecords, usdRate, eurRate) {
        expenseRecords
            .groupBy { it.category }
            .mapValues { entry ->
                entry.value.sumOf { convertToEGP(parseAmount(it.amount), it.currency, it.accountName, usdRate, eurRate) }
            }
            .toList()
            .sortedByDescending { it.second }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Month Dropdown
                Box(modifier = Modifier.weight(1f)) {
                    Button(
                        onClick = { expandedMonth = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppSurface)
                    ) {
                        Text(monthNames[selectedMonth], color = AppTextPrimary)
                        Icon(Icons.Default.ArrowDropDown, null, tint = AppTextPrimary)
                    }
                    DropdownMenu(
                        expanded = expandedMonth, 
                        onDismissRequest = { expandedMonth = false },
                        modifier = Modifier.background(AppSurface)
                    ) {
                        monthNames.forEachIndexed { index, name ->
                            DropdownMenuItem(
                                text = { Text(name, color = AppTextPrimary) },
                                onClick = {
                                    selectedMonth = index
                                    expandedMonth = false
                                }
                            )
                        }
                    }
                }

                // Year Dropdown
                Box(modifier = Modifier.weight(0.7f)) {
                    Button(
                        onClick = { expandedYear = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppSurface)
                    ) {
                        Text(selectedYear.toString(), color = AppTextPrimary)
                        Icon(Icons.Default.ArrowDropDown, null, tint = AppTextPrimary)
                    }
                    DropdownMenu(
                        expanded = expandedYear, 
                        onDismissRequest = { expandedYear = false },
                        modifier = Modifier.background(AppSurface)
                    ) {
                        years.forEach { year ->
                            DropdownMenuItem(
                                text = { Text(year.toString(), color = AppTextPrimary) },
                                onClick = {
                                    selectedYear = year
                                    expandedYear = false
                                }
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                        Text("Monthly Summary (EGP)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                    }
                    Spacer(modifier = Modifier.height(18.dp))
                    
                    SummaryRow("Income", totalIncomeEGP, AppGreen, Icons.AutoMirrored.Filled.TrendingUp)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        thickness = 0.5.dp,
                        color = AppPrimary.copy(alpha = 0.2f)
                    )
                    SummaryRow("Expenses", totalExpenseEGP, AppRed, Icons.AutoMirrored.Filled.TrendingDown)
                    if (totalTransferEGP > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            thickness = 0.5.dp,
                            color = AppPrimary.copy(alpha = 0.2f)
                        )
                        SummaryRow("Transfers", totalTransferEGP, AppPrimaryLight, Icons.Default.SwapHoriz)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 10.dp),
                        thickness = 1.dp,
                        color = AppPrimary.copy(alpha = 0.3f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Net Balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        Text(
                            text = String.format(Locale.getDefault(), "%,.2f EGP", netBalanceEGP),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (netBalanceEGP >= 0) AppGreen else AppRed,
                            letterSpacing = (-0.5).sp
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                Text(
                    text = "Spending by Category",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
            }
        }

        if (categoryTotals.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No transactions for this month", style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                }
            }
        } else {
            items(categoryTotals) { (category, amountEGP) ->
                val categoryInfo = Categories.list.flatMap { it.subCategories + it }
                    .find { it.name == category }
                
                ReportCategoryRow(
                    name = category,
                    amount = amountEGP,
                    color = categoryInfo?.color ?: Color.Gray,
                    currency = "EGP"
                )
            }
        }
        
        if (filteredRecords.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                    Text(
                        text = "Daily Transactions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                }
            }
            
            items(filteredRecords.sortedByDescending { it.timestamp }) { record ->
                val amount = parseAmount(record.amount)
                val isIncome = record.type == "Income"
                val isTransfer = record.category == "Transfer" || record.category == "Credit Payment"
                val isExcluded = !isIncome && isExcludedFromSpending(record)
                val color = when {
                    isIncome -> AppGreen
                    isTransfer -> AppPrimaryLight
                    isExcluded -> AppTextSecondary
                    else -> AppRed
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(color.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isIncome) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                                contentDescription = null,
                                tint = color,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(record.category, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                            Text(record.accountName, style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val sign = if (isIncome) "+" else if (isExcluded || isTransfer) "" else "-"
                            Text(
                                text = "$sign${String.format(Locale.getDefault(), "%,.2f", amount)} ${record.currency}",
                                color = color,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = SimpleDateFormat("dd MMM", Locale.getDefault()).format(record.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextSecondary
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SummaryRow(label: String, amount: Double, color: Color, icon: ImageVector) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary)
        }
        Text(
            text = String.format(Locale.getDefault(), "%,.2f EGP", amount),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun ReportCategoryRow(name: String, amount: Double, color: Color, currency: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(10.dp),
                    shape = RoundedCornerShape(3.dp),
                    color = color
                ) {}
                Spacer(modifier = Modifier.width(12.dp))
                Text(name, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary, fontWeight = FontWeight.Medium)
            }
            Text(
                text = String.format(Locale.getDefault(), "%,.2f %s", amount, currency),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
        }
    }
}

@Composable
fun BalanceTabContent(accounts: List<Account>, usdRate: Double, eurRate: Double, goldPriceEgpPerGram: Double? = null, records: List<Record> = emptyList()) {
    // Credit cards are debt, not assets — exclude them from net worth
    val assetAccounts = remember(accounts) {
        accounts.filter { it.accountType.lowercase() != "credit card" }
    }

    fun accountEgpValue(account: Account): Double {
        val amount = parseAmount(account.amount)
        return if (account.accountType.equals("Gold", ignoreCase = true)) {
            amount * (goldPriceEgpPerGram ?: 0.0)
        } else {
            convertToEGP(amount, account.currency, account.name, usdRate, eurRate)
        }
    }

    val totalBalanceEGP = remember(assetAccounts, usdRate, eurRate, goldPriceEgpPerGram) {
        assetAccounts.sumOf { accountEgpValue(it) }
    }

    val currencyData = remember(assetAccounts, usdRate, eurRate, goldPriceEgpPerGram) {
        val nonGold = assetAccounts.filter { !it.accountType.equals("Gold", ignoreCase = true) }
        val result = nonGold.groupBy { getCurrencyType(it.currency, it.name) }
            .mapValues { entry ->
                val originalSum = entry.value.sumOf { parseAmount(it.amount) }
                val egpSum = entry.value.sumOf { convertToEGP(parseAmount(it.amount), it.currency, it.name, usdRate, eurRate) }
                originalSum to egpSum
            }.toMutableMap()
        val goldAccounts = assetAccounts.filter { it.accountType.equals("Gold", ignoreCase = true) }
        if (goldAccounts.isNotEmpty()) {
            val totalGrams = goldAccounts.sumOf { parseAmount(it.amount) }
            val goldEGP = totalGrams * (goldPriceEgpPerGram ?: 0.0)
            result["Gold"] = totalGrams to goldEGP
        }
        result.toMap()
    }

    val maxAbsEGP = remember(assetAccounts, usdRate, eurRate, goldPriceEgpPerGram) {
        val max = assetAccounts.maxOfOrNull { Math.abs(accountEgpValue(it)) } ?: 1.0
        if (max == 0.0) 1.0 else max
    }

    val maxCurrencyEGP = remember(currencyData) {
        val max = currencyData.values.maxOfOrNull { Math.abs(it.second) } ?: 1.0
        if (max == 0.0) 1.0 else max
    }

    val defaultAccountId = remember(accounts) {
        accounts.firstOrNull { !it.accountType.contains("Credit", ignoreCase = true) && !it.isArchived }?.id
            ?: accounts.firstOrNull()?.id ?: ""
    }
    var selectedChartAccountId by remember(defaultAccountId) { mutableStateOf(defaultAccountId) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(26.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().background(HeroGradient).padding(24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "NET WORTH (EGP)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xB3C4B5FD),
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = String.format(Locale.getDefault(), "%,.2f", totalBalanceEGP),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = (-1.5).sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Debit · Cash · Gold",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0x99C4B5FD)
                        )
                    }
                }
            }
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                Text(
                    text = "Asset Distribution",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
            }
        }

        val currencyLabels = listOf("EGP", "USD", "EUR", "Gold").filter { currencyData.containsKey(it) }
        items(currencyLabels) { label ->
            val (originalSum, amountEGP) = currencyData[label] ?: (0.0 to 0.0)
            val (displayValue, icon, color) = when (label) {
                "USD"  -> Triple(String.format(Locale.getDefault(), "%,.2f $", originalSum), Icons.Default.AttachMoney, Color(0xFF4CAF50))
                "EUR"  -> Triple(String.format(Locale.getDefault(), "%,.2f €", originalSum), Icons.Default.Euro, Color(0xFFFFC107))
                "Gold" -> Triple(String.format(Locale.getDefault(), "%.2f g", originalSum), Icons.Default.Payments, Color(0xFFFFB300))
                else   -> Triple(String.format(Locale.getDefault(), "%,.2f EGP", originalSum), Icons.Default.Payments, AppPrimaryLight)
            }
            SimpleBalanceBar(
                label = label,
                amountEGP = amountEGP,
                maxAbsEGP = maxCurrencyEGP,
                displayValue = displayValue,
                icon = icon,
                barColor = color
            )
        }

        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                Text(
                    text = "Accounts Breakdown (in EGP)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
            }
        }

        val sortedAccounts = assetAccounts.sortedByDescending { accountEgpValue(it) }

        items(sortedAccounts) { account ->
            val isGold = account.accountType.equals("Gold", ignoreCase = true)
            val amount = parseAmount(account.amount)
            val balanceEGP = accountEgpValue(account)

            AccountBalanceRow(
                name = account.name,
                amountEGP = balanceEGP,
                originalAmount = amount,
                originalCurrency = if (isGold) "g" else account.currency,
                maxAbsEGP = maxAbsEGP,
                accountColor = longToColor(account.color)
            )
        }

        // Balance Over Time chart
        if (records.isNotEmpty() && accounts.isNotEmpty()) {
            item {
                val chartRecords = remember(records, selectedChartAccountId) {
                    records.filter { it.accountId == selectedChartAccountId && it.balanceAfter.isNotBlank() && !it.accountName.contains("->") }
                        .sortedBy { it.timestamp }
                        .takeLast(30)
                }
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                            Text("Balance Over Time", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, color = AppTextPrimary, modifier = Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        val eligibleAccounts = remember(accounts) { accounts.filter { !it.isArchived } }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(eligibleAccounts) { acc ->
                                val selected = acc.id == selectedChartAccountId
                                FilterChip(
                                    selected = selected,
                                    onClick = { selectedChartAccountId = acc.id },
                                    label = { Text(acc.name, style = MaterialTheme.typography.labelSmall) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = longToColor(acc.color).copy(alpha = 0.25f),
                                        selectedLabelColor = longToColor(acc.color)
                                    ),
                                    border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected,
                                        selectedBorderColor = longToColor(acc.color))
                                )
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        if (chartRecords.size >= 2) {
                            val selectedAccount = accounts.find { it.id == selectedChartAccountId }
                            BalanceLineChart(
                                dataPoints = chartRecords.map { it.timestamp to (it.balanceAfter.toDoubleOrNull() ?: 0.0) },
                                lineColor = longToColor(selectedAccount?.color ?: 0L),
                                modifier = Modifier.fillMaxWidth().height(180.dp)
                            )
                        } else {
                            Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                                Text("Not enough history yet", color = AppTextSecondary, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        // ── Net Worth Trend (monthly, last 6 months) ──────────────────────────
        item {
            val monthlyNetWorth = remember(records, accounts, usdRate, eurRate, goldPriceEgpPerGram) {
                val result = mutableListOf<Pair<Date, Double>>()
                for (monthsBack in 5 downTo 0) {
                    val snapCal = Calendar.getInstance().apply {
                        add(Calendar.MONTH, -monthsBack)
                        set(Calendar.DAY_OF_MONTH, getActualMaximum(Calendar.DAY_OF_MONTH))
                        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                    }
                    val snapDate = snapCal.time
                    val netWorth = accounts
                        .filter { !it.accountType.contains("Credit", ignoreCase = true) && !it.isArchived }
                        .sumOf { acc ->
                            val lastRec = records
                                .filter { it.accountId == acc.id && !it.accountName.contains("->") &&
                                          it.balanceAfter.isNotBlank() && it.timestamp <= snapDate }
                                .maxByOrNull { it.timestamp }
                            val balance = lastRec?.balanceAfter?.toDoubleOrNull() ?: 0.0
                            if (acc.accountType.equals("Gold", ignoreCase = true))
                                balance * (goldPriceEgpPerGram ?: 0.0)
                            else
                                convertToEGP(balance, acc.currency, acc.name, usdRate, eurRate)
                        }
                    result.add(snapDate to netWorth)
                }
                result
            }
            if (monthlyNetWorth.any { it.second > 0 }) {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                            Text("Net Worth Trend", style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold, color = AppTextPrimary, modifier = Modifier.weight(1f))
                            Text("6 months", fontSize = 10.sp, color = AppTextSecondary)
                        }
                        Spacer(Modifier.height(16.dp))
                        MonthlyNetWorthChart(
                            dataPoints = monthlyNetWorth,
                            lineColor = AppPrimaryLight,
                            modifier = Modifier.fillMaxWidth().height(180.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthlyNetWorthChart(
    dataPoints: List<Pair<Date, Double>>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    if (dataPoints.size < 2 || dataPoints.none { it.second > 0 }) return
    val minY = dataPoints.minOf { it.second }
    val maxY = dataPoints.maxOf { it.second }
    val range = if (maxY - minY < 1.0) 1.0 else maxY - minY
    val monthFmt = SimpleDateFormat("MMM", Locale.getDefault())

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width; val h = size.height
            val padT = 8f; val padB = 8f; val usableH = h - padT - padB
            fun xOf(i: Int) = i.toFloat() / (dataPoints.size - 1) * w
            fun yOf(v: Double) = (padT + (1.0 - (v - minY) / range) * usableH).toFloat()

            val fillPath = Path()
            dataPoints.forEachIndexed { i, (_, v) ->
                if (i == 0) fillPath.moveTo(xOf(i), yOf(v)) else fillPath.lineTo(xOf(i), yOf(v))
            }
            fillPath.lineTo(xOf(dataPoints.size - 1), h)
            fillPath.lineTo(xOf(0), h)
            fillPath.close()
            drawPath(fillPath, brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent), startY = padT, endY = h
            ))

            val linePath = Path()
            dataPoints.forEachIndexed { i, (_, v) ->
                if (i == 0) linePath.moveTo(xOf(i), yOf(v)) else linePath.lineTo(xOf(i), yOf(v))
            }
            drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f))

            dataPoints.forEachIndexed { i, (_, v) ->
                drawCircle(color = lineColor, radius = 4f, center = Offset(xOf(i), yOf(v)))
                drawCircle(color = Color.White, radius = 2f, center = Offset(xOf(i), yOf(v)))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            dataPoints.forEach { (date, _) ->
                Text(monthFmt.format(date), style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
            }
        }
    }
}

@Composable
private fun BalanceLineChart(
    dataPoints: List<Pair<java.util.Date, Double>>,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    if (dataPoints.size < 2) return
    val minY = dataPoints.minOf { it.second }
    val maxY = dataPoints.maxOf { it.second }
    val range = if (maxY - minY < 1.0) 1.0 else maxY - minY
    val dateFmt = SimpleDateFormat("dd/MM", Locale.getDefault())

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val w = size.width
            val h = size.height
            val padL = 0f; val padR = 0f; val padT = 8f; val padB = 8f
            val usableW = w - padL - padR
            val usableH = h - padT - padB

            fun xOf(i: Int) = padL + i.toFloat() / (dataPoints.size - 1) * usableW
            fun yOf(v: Double) = (padT + (1.0 - (v - minY) / range) * usableH).toFloat()

            // Fill gradient under the line
            val fillPath = Path()
            dataPoints.forEachIndexed { i, (_, v) ->
                if (i == 0) fillPath.moveTo(xOf(i), yOf(v)) else fillPath.lineTo(xOf(i), yOf(v))
            }
            fillPath.lineTo(xOf(dataPoints.size - 1), h)
            fillPath.lineTo(xOf(0), h)
            fillPath.close()
            drawPath(fillPath, brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.3f), lineColor.copy(alpha = 0.0f)),
                startY = padT, endY = h
            ))

            // Line
            val linePath = Path()
            dataPoints.forEachIndexed { i, (_, v) ->
                if (i == 0) linePath.moveTo(xOf(i), yOf(v)) else linePath.lineTo(xOf(i), yOf(v))
            }
            drawPath(linePath, color = lineColor, style = Stroke(width = 2.5f))

            // Dots at first, last, min, max
            val special = setOf(0, dataPoints.size - 1,
                dataPoints.indexOfFirst { it.second == minY },
                dataPoints.indexOfFirst { it.second == maxY })
            special.forEach { i ->
                drawCircle(color = lineColor, radius = 5f, center = Offset(xOf(i), yOf(dataPoints[i].second)))
                drawCircle(color = Color.White, radius = 2.5f, center = Offset(xOf(i), yOf(dataPoints[i].second)))
            }
        }
        // X-axis labels: first and last
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dateFmt.format(dataPoints.first().first), style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
            Text(dateFmt.format(dataPoints.last().first), style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
        }
    }
}

@Composable
fun SimpleBalanceBar(
    label: String, 
    amountEGP: Double, 
    maxAbsEGP: Double, 
    displayValue: String,
    icon: ImageVector,
    barColor: Color
) {
    val rawProgress = (Math.abs(amountEGP) / maxAbsEGP).toFloat().coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "balance_bar_$label"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(barColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = barColor,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (amountEGP >= 0) barColor else AppRed
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(AppPrimary.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedProgress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    (if (amountEGP >= 0) barColor else AppRed).copy(alpha = 0.7f),
                                    if (amountEGP >= 0) barColor else AppRed
                                )
                            )
                        )
                )
            }
        }
    }
}

@Composable
fun AccountBalanceRow(
    name: String,
    amountEGP: Double,
    originalAmount: Double,
    originalCurrency: String,
    maxAbsEGP: Double,
    accountColor: Color
) {
    val rawProgress = (Math.abs(amountEGP) / maxAbsEGP).toFloat().coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = rawProgress,
        animationSpec = tween(durationMillis = 1000, easing = FastOutSlowInEasing),
        label = "account_bar_$name"
    )
    val isNegative = amountEGP < 0
    val displayColor = if (isNegative) AppRed else accountColor

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(accountColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                }
                Text(
                    text = String.format(Locale.getDefault(), "%,.2f %s", originalAmount, originalCurrency),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppTextSecondary,
                    modifier = Modifier.padding(start = 20.dp)
                )
            }
            Text(
                text = String.format(Locale.getDefault(), "%,.2f EGP", amountEGP),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.ExtraBold,
                color = AppTextPrimary
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(AppPrimary.copy(alpha = 0.2f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(displayColor.copy(alpha = 0.6f), displayColor)
                        )
                    )
            )
        }
    }
}

@Composable
fun NetWorthTabContent(
    accounts: List<Account>,
    records: List<Record>,
    usdRate: Double,
    eurRate: Double,
    goldPriceEgpPerGram: Double?
) {
    val modelProducer = remember { CartesianChartModelProducer() }

    val currentNetWorthEGP = remember(accounts, usdRate, eurRate, goldPriceEgpPerGram) {
        accounts
            .filter { !it.isArchived && !it.accountType.contains("Credit", ignoreCase = true) }
            .sumOf { account ->
                val amount = parseAmount(account.amount)
                if (account.accountType.equals("Gold", ignoreCase = true))
                    amount * (goldPriceEgpPerGram ?: 0.0)
                else
                    convertToEGP(amount, account.currency, account.name, usdRate, eurRate)
            }
    }

    // Reconstruct monthly net worth by walking backwards from current balance.
    // netWorth(M) = currentNW − income(months after M) + expenses(months after M)
    val monthlyNetWorth = remember(records, currentNetWorthEGP, usdRate, eurRate) {
        val fmt = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val relevant = records.filter { rec ->
            (rec.type == "Income" || rec.type == "Expense") &&
                rec.category != "Transfer" &&
                rec.category != "Credit Payment" &&
                !rec.accountName.contains("Credit", ignoreCase = true)
        }
        val byMonth = relevant.groupBy { fmt.format(it.timestamp) }
        var runningNW = currentNetWorthEGP
        val result = mutableListOf<Pair<String, Double>>()
        for (monthKey in byMonth.keys.sortedDescending()) {
            result.add(0, monthKey to runningNW)
            for (rec in byMonth[monthKey]!!) {
                val amt = convertToEGP(parseAmount(rec.amount), rec.currency, rec.accountName, usdRate, eurRate)
                if (rec.type == "Income") runningNW -= amt else runningNW += amt
            }
        }
        result.takeLast(12)
    }

    LaunchedEffect(monthlyNetWorth) {
        if (monthlyNetWorth.size >= 2) {
            modelProducer.runTransaction {
                lineSeries { series(monthlyNetWorth.map { it.second }) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                shape = RoundedCornerShape(26.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth().background(HeroGradient).padding(24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "CURRENT NET WORTH",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xB3C4B5FD),
                            letterSpacing = 2.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            String.format(Locale.getDefault(), "%,.0f EGP", currentNetWorthEGP),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            letterSpacing = (-1.5).sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Cash · Debit · Gold",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0x99C4B5FD)
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = AppSurface),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                        Text("Net Worth Over Time", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                    }
                    Spacer(Modifier.height(20.dp))
                    if (monthlyNetWorth.size >= 2) {
                        CartesianChartHost(
                            chart = rememberCartesianChart(
                                rememberLineCartesianLayer(),
                                startAxis = VerticalAxis.rememberStart(),
                                bottomAxis = HorizontalAxis.rememberBottom(
                                    valueFormatter = CartesianValueFormatter { _, value, _ ->
                                        monthlyNetWorth.getOrNull(value.toInt())?.first?.takeLast(5) ?: ""
                                    }
                                )
                            ),
                            modelProducer = modelProducer,
                            modifier = Modifier.fillMaxWidth().height(220.dp)
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            Text(
                                if (monthlyNetWorth.isEmpty()) "No transaction history yet"
                                else "Need data from at least 2 months",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary
                            )
                        }
                    }
                }
            }
        }

        if (monthlyNetWorth.size >= 2) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                    Text("Monthly Snapshot", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
            }
            items(monthlyNetWorth.reversed()) { (monthKey, nw) ->
                val idx = monthlyNetWorth.indexOfFirst { it.first == monthKey }
                val change = if (idx > 0) nw - monthlyNetWorth[idx - 1].second else null
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(monthKey, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary, fontWeight = FontWeight.Medium)
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                String.format(Locale.getDefault(), "%,.0f EGP", nw),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = AppTextPrimary
                            )
                            if (change != null) {
                                Text(
                                    "${if (change >= 0) "+" else ""}${String.format(Locale.getDefault(), "%,.0f", change)} EGP",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (change >= 0) AppGreen else AppRed,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpendingTabContent(records: List<Record>) {
    var timeRange by remember { mutableStateOf(TimeRange.LAST_MONTH) }
    val modelProducer = remember { CartesianChartModelProducer() }
    var selectedCategoryForDetail by remember { mutableStateOf<String?>(null) }
    var selectedTransferForDetail by remember { mutableStateOf<String?>(null) }
    val smsDetailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val filtered = remember(records, timeRange) { filterRecordsByRange(records, timeRange) }

    val periodFormat = remember(timeRange) {
        when (timeRange) {
            TimeRange.LAST_DAY   -> SimpleDateFormat("HH:00", Locale.getDefault())
            TimeRange.LAST_WEEK,
            TimeRange.LAST_MONTH -> SimpleDateFormat("dd/MM", Locale.getDefault())
            else                 -> SimpleDateFormat("MMM yy", Locale.getDefault())
        }
    }

    // Group by period — expenses only for the spending chart
    val periodData = remember(filtered, periodFormat) {
        filtered
            .groupBy { periodFormat.format(it.timestamp) }
            .entries
            .map { (label, recs) ->
                val exp = recs.filter { it.type == "Expense" && !isExcludedFromSpending(it) }
                    .sumOf { parseAmount(it.amount) }
                label to exp
            }
            .takeLast(10)
    }

    // Per-currency totals so we never mix currencies into one wrong label
    val incomePerCurrency  = remember(filtered) {
        filtered.filter { it.type == "Income" }
            .groupBy { it.currency }
            .mapValues { e -> e.value.sumOf { parseAmount(it.amount) } }
    }
    val expensePerCurrency = remember(filtered) {
        filtered.filter { it.type == "Expense" && !isExcludedFromSpending(it) }
            .groupBy { it.currency }
            .mapValues { e -> e.value.sumOf { parseAmount(it.amount) } }
    }

    val totalIncome  = remember(incomePerCurrency)  { incomePerCurrency.values.sum() }
    val totalExpense = remember(expensePerCurrency) { expensePerCurrency.values.sum() }
    val net = totalIncome - totalExpense

    // Income and expense breakdown by category (using each record's own currency)
    val expenseCategories = remember(filtered) {
        filtered.filter { it.type == "Expense" && !isExcludedFromSpending(it) }
            .groupBy { it.category }
            .mapValues { e ->
                e.value.groupBy { it.currency }
                    .mapValues { c -> c.value.sumOf { parseAmount(it.amount) } }
            }
            .toList().sortedByDescending { (_, byCurrency) -> byCurrency.values.sum() }
    }

    val incomeCategories = remember(filtered) {
        filtered.filter { it.type == "Income" }
            .groupBy { it.category }
            .mapValues { e ->
                e.value.groupBy { it.currency }
                    .mapValues { c -> c.value.sumOf { parseAmount(it.amount) } }
            }
            .toList().sortedByDescending { (_, byCurrency) -> byCurrency.values.sum() }
    }

    val transferPerCurrency = remember(filtered) {
        filtered.filter { it.category == "Transfer" || it.category == "Credit Payment" }
            .groupBy { it.currency }
            .mapValues { e -> e.value.sumOf { parseAmount(it.amount) } }
    }
    val transferRoutes = remember(filtered) {
        filtered.filter { it.category == "Transfer" || it.category == "Credit Payment" }
            .groupBy { it.accountName }
            .mapValues { e ->
                e.value.groupBy { it.currency }
                    .mapValues { c -> c.value.sumOf { parseAmount(it.amount) } }
            }
            .toList().sortedByDescending { (_, byCurrency) -> byCurrency.values.sum() }
    }

    // Previous period for trend comparison
    val previousFiltered = remember(records, timeRange) { filterRecordsByPreviousPeriod(records, timeRange) }
    val previousExpense = remember(previousFiltered) {
        previousFiltered.filter { it.type == "Expense" && !isExcludedFromSpending(it) }.sumOf { parseAmount(it.amount) }
    }
    val previousIncome = remember(previousFiltered) {
        previousFiltered.filter { it.type == "Income" }.sumOf { parseAmount(it.amount) }
    }

    // Regular/recurring expenses: categories appearing in ≥2 of the last 3 calendar months
    val regularExpenses = remember(records) {
        val now = Calendar.getInstance()
        val monthKeys = (0..2).map { offset ->
            Calendar.getInstance().apply { add(Calendar.MONTH, -offset) }.let {
                it.get(Calendar.YEAR) * 12 + it.get(Calendar.MONTH)
            }
        }.toSet()
        records.filter { it.type == "Expense" && !isExcludedFromSpending(it) }
            .groupBy { it.category to it.currency }
            .mapNotNull { (key, recs) ->
                val months = recs.map {
                    val cal = Calendar.getInstance().apply { time = it.timestamp }
                    cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
                }.filter { it in monthKeys }.distinct()
                if (months.size >= 2) {
                    val avg = recs.filter {
                        val cal = Calendar.getInstance().apply { time = it.timestamp }
                        (cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)) in monthKeys
                    }.sumOf { parseAmount(it.amount) } / months.size
                    Triple(key.first, avg, key.second)
                } else null
            }
            .sortedByDescending { it.second }
    }

    LaunchedEffect(periodData) {
        if (periodData.isNotEmpty()) {
            modelProducer.runTransaction {
                columnSeries {
                    series(periodData.map { it.second })
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Time range selector
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TimeRangeDropdown(selectedRange = timeRange, onRangeSelected = { timeRange = it })
            }
        }

        // Income / Expense / Net summary cards
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpendingSummaryCard(
                    label = "Income",
                    amountsPerCurrency = incomePerCurrency,
                    color = AppGreen,
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    modifier = Modifier.weight(1f)
                )
                SpendingSummaryCard(
                    label = "Expense",
                    amountsPerCurrency = expensePerCurrency,
                    color = AppRed,
                    icon = Icons.AutoMirrored.Filled.TrendingDown,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (transferPerCurrency.isNotEmpty()) {
            item {
                SpendingSummaryCard(
                    label = "Transfers",
                    amountsPerCurrency = transferPerCurrency,
                    color = AppPrimaryLight,
                    icon = Icons.Default.SwapHoriz,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        item {
            // Net row — only meaningful when single currency; multi-currency shows a note
            val singleCurrency = (incomePerCurrency.keys + expensePerCurrency.keys).toSet().size == 1
            val currencyLabel = (incomePerCurrency.keys + expensePerCurrency.keys).firstOrNull() ?: "EGP"
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (net >= 0) AppGreen.copy(alpha = 0.15f)
                                     else AppRed.copy(alpha = 0.15f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Net Balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                    Text(
                        text = if (singleCurrency)
                            "${if (net >= 0) "+" else ""}${String.format(Locale.getDefault(), "%,.2f", net)} $currencyLabel"
                        else
                            "${if (net >= 0) "+" else ""}${String.format(Locale.getDefault(), "%,.2f", net)} (mixed)",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (net >= 0) AppGreen else AppRed,
                        letterSpacing = (-0.5).sp
                    )
                }
            }
        }

        // Trends vs previous period
        if (timeRange != TimeRange.ALL_TIME && (previousExpense > 0 || previousIncome > 0)) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                            Text("vs Previous Period", style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (previousIncome > 0) {
                                val incomeDiff = if (previousIncome > 0) (totalIncome - previousIncome) / previousIncome * 100 else 0.0
                                TrendChip("Income", incomeDiff, AppGreen, Modifier.weight(1f))
                            }
                            if (previousExpense > 0) {
                                val expenseDiff = if (previousExpense > 0) (totalExpense - previousExpense) / previousExpense * 100 else 0.0
                                TrendChip("Spending", expenseDiff, AppRed, Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // Income vs Expense bar chart
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = AppSurface)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                        Text("Spending Over Time", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold, color = AppTextPrimary)
                    }
                    Spacer(Modifier.height(20.dp))
                    if (periodData.isNotEmpty()) {
                        CartesianChartHost(
                            chart = rememberCartesianChart(
                                rememberColumnCartesianLayer(),
                                startAxis = VerticalAxis.rememberStart(),
                                bottomAxis = HorizontalAxis.rememberBottom(
                                    valueFormatter = CartesianValueFormatter { _, value, _ ->
                                        periodData.getOrNull(value.toInt())?.first ?: ""
                                    }
                                )
                            ),
                            modelProducer = modelProducer,
                            modifier = Modifier.fillMaxWidth().height(220.dp)
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                            Text("No data for this period", style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary)
                        }
                    }
                }
            }
        }

        // Expense by category
        if (expenseCategories.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                    Text("Expenses by Category", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
            }
            item {
                val donutSegments = remember(expenseCategories) {
                    expenseCategories.take(6).mapIndexed { i, (cat, byCurrency) ->
                        Triple(cat, byCurrency.values.sum(), ChartPalette.getOrElse(i) { Color.Gray })
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        CategoryDonutChart(
                            segments = donutSegments,
                            centerLabel = "Expenses"
                        )
                    }
                }
            }
            items(expenseCategories) { (category, byCurrency) ->
                val categoryInfo = Categories.list.flatMap { it.subCategories + it }
                    .find { it.name == category }
                CategoryCurrencyRow(
                    name = category,
                    amountsPerCurrency = byCurrency,
                    color = categoryInfo?.color ?: Color.Gray,
                    amountColor = AppRed,
                    onClick = { selectedCategoryForDetail = category }
                )
            }
        }

        // Income by category
        if (incomeCategories.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                    Text("Income by Source", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
            }
            items(incomeCategories) { (category, byCurrency) ->
                val categoryInfo = Categories.list.flatMap { it.subCategories + it }
                    .find { it.name == category }
                CategoryCurrencyRow(
                    name = category,
                    amountsPerCurrency = byCurrency,
                    color = categoryInfo?.color ?: AppGreen,
                    amountColor = AppGreen,
                    onClick = { selectedCategoryForDetail = category }
                )
            }
        }

        // Transfers section
        if (transferRoutes.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                    Text("Transfers", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
            }
            items(transferRoutes) { (accountName, byCurrency) ->
                CategoryCurrencyRow(
                    name = accountName,
                    amountsPerCurrency = byCurrency,
                    color = AppPrimaryLight,
                    amountColor = AppPrimaryLight,
                    onClick = { selectedTransferForDetail = accountName }
                )
            }
        }

        // Regular / recurring expenses — shown last as a summary insight
        if (regularExpenses.isNotEmpty()) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                    Text("Regular Monthly Expenses", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = AppTextPrimary)
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = AppSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        regularExpenses.take(8).forEach { (category, avgAmount, currency) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(AppPrimaryLight))
                                    Text(category, style = MaterialTheme.typography.bodyMedium, color = AppTextPrimary)
                                }
                                Text(
                                    text = "~${String.format(Locale.getDefault(), "%,.0f", avgAmount)} $currency/mo",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTextSecondary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedCategoryForDetail != null) {
        val category = selectedCategoryForDetail!!
        val smsRecords = remember(filtered, category) {
            filtered.filter { it.category == category && it.smsId != null }
                .sortedByDescending { it.timestamp }
        }
        ModalBottomSheet(
            onDismissRequest = { selectedCategoryForDetail = null },
            sheetState = smsDetailSheetState,
            containerColor = AppSurface,
            scrimColor = Color.Black.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppViolet.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Sms, null, tint = AppVioletLight, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(category, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        Text(
                            if (smsRecords.isEmpty()) "No SMS-tracked records in this period"
                            else "${smsRecords.size} SMS-tracked transaction${if (smsRecords.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))

                if (smsRecords.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Sms, null,
                                modifier = Modifier.size(40.dp),
                                tint = AppPrimary.copy(alpha = 0.3f))
                            Spacer(Modifier.height(12.dp))
                            Text("No SMS records for this category",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppTextSecondary)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(smsRecords) { record ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = AppBackground.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (record.comment.isNotEmpty()) {
                                            Text(
                                                record.comment,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = AppTextPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            record.accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AppTextSecondary
                                        )
                                        Text(
                                            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                                .format(record.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppTextMuted
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        text = "${if (record.type == "Income") "+" else "-"}${record.amount} ${record.currency}",
                                        fontWeight = FontWeight.ExtraBold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (record.type == "Income") AppGreen else AppRed
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (selectedTransferForDetail != null) {
        val route = selectedTransferForDetail!!
        val transferDetailRecords = remember(filtered, route) {
            filtered.filter { (it.category == "Transfer" || it.category == "Credit Payment") && it.accountName == route }
                .sortedByDescending { it.timestamp }
        }
        ModalBottomSheet(
            onDismissRequest = { selectedTransferForDetail = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = AppSurface,
            scrimColor = Color.Black.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SwapHoriz, null, tint = AppPrimaryLight, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(route, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = AppTextPrimary)
                        Text(
                            "${transferDetailRecords.size} transfer${if (transferDetailRecords.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (transferDetailRecords.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No records", style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(transferDetailRecords) { record ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = AppBackground.copy(alpha = 0.5f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(16.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (record.comment.isNotEmpty()) {
                                            Text(
                                                record.comment,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = AppTextPrimary,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            record.accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = AppTextSecondary
                                        )
                                        Text(
                                            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                                .format(record.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppTextMuted
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        text = "${record.amount} ${record.currency}",
                                        fontWeight = FontWeight.ExtraBold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = AppPrimaryLight
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Shows an amount card that lists each currency on its own line
@Composable
private fun SpendingSummaryCard(
    label: String,
    amountsPerCurrency: Map<String, Double>,
    color: Color,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = color,
                    fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
            Spacer(Modifier.height(10.dp))
            if (amountsPerCurrency.isEmpty()) {
                Text("0.00", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold, color = color)
            } else {
                amountsPerCurrency.forEach { (currency, amount) ->
                    Text(
                        text = "${String.format(Locale.getDefault(), "%,.2f", amount)} $currency",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = color,
                        letterSpacing = (-0.3).sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TrendChip(label: String, changePct: Double, baseColor: Color, modifier: Modifier = Modifier) {
    val isIncrease = changePct > 0
    val isExpense = label == "Spending"
    val trendColor = when {
        isExpense && isIncrease  -> AppRed
        isExpense && !isIncrease -> AppGreen
        !isExpense && isIncrease -> AppGreen
        else                     -> AppRed
    }
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = trendColor.copy(alpha = 0.1f))) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = if (isIncrease) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingDown,
                    contentDescription = null, tint = trendColor, modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "${if (isIncrease) "+" else ""}${String.format(Locale.getDefault(), "%.1f", changePct)}%",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = trendColor
                )
            }
        }
    }
}

// Category row that shows each currency amount on its own line
@Composable
private fun CategoryCurrencyRow(
    name: String,
    amountsPerCurrency: Map<String, Double>,
    color: Color,
    amountColor: Color,
    onClick: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
                Spacer(Modifier.width(14.dp))
                Text(name, fontWeight = FontWeight.Bold, color = AppTextPrimary,
                    style = MaterialTheme.typography.bodyLarge)
            }
            Column(horizontalAlignment = Alignment.End) {
                amountsPerCurrency.forEach { (currency, amount) ->
                    Text(
                        text = "${String.format(Locale.getDefault(), "%,.2f", amount)} $currency",
                        fontWeight = FontWeight.ExtraBold,
                        color = amountColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun TimeRangeDropdown(
    selectedRange: TimeRange,
    onRangeSelected: (TimeRange) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Button(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.height(32.dp),
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppSurface)
        ) {
            Text(selectedRange.label, color = AppTextPrimary, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AppTextPrimary, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(AppSurface)
        ) {
            TimeRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(range.label, color = AppTextPrimary) },
                    onClick = {
                        onRangeSelected(range)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun filterRecordsByRange(records: List<Record>, range: TimeRange): List<Record> {
    if (range == TimeRange.ALL_TIME) return records
    val calendar = Calendar.getInstance()
    when (range) {
        TimeRange.LAST_DAY -> calendar.add(Calendar.DAY_OF_YEAR, -1)
        TimeRange.LAST_WEEK -> calendar.add(Calendar.DAY_OF_YEAR, -7)
        TimeRange.LAST_MONTH -> calendar.add(Calendar.MONTH, -1)
        TimeRange.LAST_YEAR -> calendar.add(Calendar.YEAR, -1)
        else -> {}
    }
    val limitDate = calendar.time
    return records.filter { it.timestamp.after(limitDate) }
}

private fun filterRecordsByPreviousPeriod(records: List<Record>, range: TimeRange): List<Record> {
    if (range == TimeRange.ALL_TIME) return emptyList()
    val end = Calendar.getInstance()
    val start = Calendar.getInstance()
    when (range) {
        TimeRange.LAST_DAY   -> { end.add(Calendar.DAY_OF_YEAR, -1); start.add(Calendar.DAY_OF_YEAR, -2) }
        TimeRange.LAST_WEEK  -> { end.add(Calendar.DAY_OF_YEAR, -7); start.add(Calendar.DAY_OF_YEAR, -14) }
        TimeRange.LAST_MONTH -> { end.add(Calendar.MONTH, -1); start.add(Calendar.MONTH, -2) }
        TimeRange.LAST_YEAR  -> { end.add(Calendar.YEAR, -1); start.add(Calendar.YEAR, -2) }
        else -> return emptyList()
    }
    return records.filter { it.timestamp.after(start.time) && it.timestamp.before(end.time) }
}
