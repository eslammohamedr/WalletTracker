package com.example.wallettrackers.screens

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

enum class StatisticsTab(val label: String) {
    BALANCE("Balance"),
    SPENDING("Spending"),
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
    onDismissStatement: (CreditStatement) -> Unit = {},
    onEditDueDate: (CreditStatement, Date) -> Unit = { _, _ -> },
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
        scope.launch {
            try {
                val usdResponse = exchangeRateApi.getLatestRates("USD")
                usdResponse.rates["EGP"]?.let { usdToEgpRate = it }
                val eurResponse = exchangeRateApi.getLatestRates("EUR")
                eurResponse.rates["EGP"]?.let { eurToEgpRate = it }
            } catch (e: Exception) {
                // Keep defaults if fetch fails
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
                statementToPay?.let { onPayClick(it, account) }
                showAccountPicker = false
                statementToPay = null
            }
        )
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Statistics") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
                SecondaryTabRow(selectedTabIndex = selectedTab.ordinal) {
                    StatisticsTab.entries.forEach { tab ->
                        Tab(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            text = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                StatisticsTab.BALANCE -> {
                    BalanceTabContent(
                        accounts = accounts,
                        usdRate = usdToEgpRate,
                        eurRate = eurToEgpRate,
                        goldPriceEgpPerGram = goldPriceEgpPerGram
                    )
                }
                StatisticsTab.SPENDING -> {
                    SpendingTabContent(records = records)
                }
                StatisticsTab.CREDIT -> {
                    CreditTabContent(
                        statements = statements,
                        onPayClick = {
                            statementToPay = it
                            showAccountPicker = true
                        },
                        onDismissClick = onDismissStatement,
                        onEditDueDate = onEditDueDate
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
    onAccountSelected: (Account) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Select Payment Account",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))
                if (accounts.isEmpty()) {
                    Text("No debit or cash accounts found.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(accounts) { account ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onAccountSelected(account) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(longToColor(account.color))
                                )
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(account.name, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${account.amount} ${account.currency}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
fun CreditTabContent(
    statements: List<CreditStatement>,
    onPayClick: (CreditStatement) -> Unit,
    onDismissClick: (CreditStatement) -> Unit = {},
    onEditDueDate: (CreditStatement, Date) -> Unit = { _, _ -> }
) {
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
            Text(
                "Credit Card Statements",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Auto-extracted from your SMS bank alerts",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (unpaidStatements.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CreditCard, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.height(16.dp))
                        Text("No pending credit card statements.", color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        } else {
            items(unpaidStatements) { statement ->
                CreditCardAlertItem(statement, onPayClick, onDismissClick, onEditDueDate)
            }
        }
    }
}

@Composable
fun CreditCardAlertItem(
    statement: CreditStatement,
    onPayClick: (CreditStatement) -> Unit,
    onDismissClick: (CreditStatement) -> Unit = {},
    onEditDueDate: (CreditStatement, Date) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    var showDatePicker by remember { mutableStateOf(false) }

    if (showDatePicker) {
        val cal = Calendar.getInstance().apply { time = statement.dueDate }
        DisposableEffect(Unit) {
            val dialog = DatePickerDialog(
                context,
                { _, year, month, day ->
                    val newDate = Calendar.getInstance().apply {
                        set(year, month, day, 0, 0, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.time
                    onEditDueDate(statement, newDate)
                    showDatePicker = false
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            )
            dialog.setOnCancelListener { showDatePicker = false }
            dialog.show()
            onDispose { dialog.dismiss() }
        }
    }
    val daysLeft = remember(statement.dueDate) {
        val diff = statement.dueDate.time - System.currentTimeMillis()
        TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS)
    }

    val statusColor = when {
        daysLeft < 0 -> MaterialTheme.colorScheme.error
        daysLeft <= 3 -> Color(0xFFFF9800) // Warning orange
        else -> Color(0xFF4CAF50) // Safe green
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CreditCard, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Card Ending ****${statement.cardLast4Digits}",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                if (!statement.isPaid) {
                    Surface(
                        color = statusColor.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = when {
                                daysLeft < 0 -> "Overdue"
                                daysLeft == 0L -> "Due Today"
                                else -> "In $daysLeft days"
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            color = statusColor,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Total Amount Due", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        String.format(Locale.getDefault(), "%,.2f EGP", statement.totalAmount),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Black
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Due Date", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(statement.dueDate),
                            fontWeight = FontWeight.Bold
                        )
                        IconButton(
                            onClick = { showDatePicker = true },
                            modifier = Modifier.size(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = "Edit due date",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, size = 16.dp, tint = MaterialTheme.colorScheme.secondary)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "Reminders active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                if (!statement.isPaid) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onDismissClick(statement) },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Already Paid", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = { onPayClick(statement) },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                            modifier = Modifier.height(32.dp),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Pay", style = MaterialTheme.typography.labelLarge)
                        }
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
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Month Dropdown
                Box(modifier = Modifier.weight(1f)) {
                    OutlinedButton(
                        onClick = { expandedMonth = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(monthNames[selectedMonth])
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expandedMonth, onDismissRequest = { expandedMonth = false }) {
                        monthNames.forEachIndexed { index, name ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    selectedMonth = index
                                    expandedMonth = false
                                }
                            )
                        }
                    }
                }

                // Year Dropdown
                Box(modifier = Modifier.weight(0.6f)) {
                    OutlinedButton(
                        onClick = { expandedYear = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(selectedYear.toString())
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(expanded = expandedYear, onDismissRequest = { expandedYear = false }) {
                        years.forEach { year ->
                            DropdownMenuItem(
                                text = { Text(year.toString()) },
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
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Monthly Summary (EGP)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    SummaryRow("Income", totalIncomeEGP, Color(0xFF4CAF50), Icons.AutoMirrored.Filled.TrendingUp)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                    SummaryRow("Expenses", totalExpenseEGP, Color(0xFFF44336), Icons.AutoMirrored.Filled.TrendingDown)
                    if (totalTransferEGP > 0) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )
                        SummaryRow("Transfers", totalTransferEGP, Color(0xFF6366F1), Icons.Default.SwapHoriz)
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Net Balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            text = String.format(Locale.getDefault(), "%,.2f EGP", netBalanceEGP),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (netBalanceEGP >= 0) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Spending by Category",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (categoryTotals.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("No transactions for this month", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                Text(
                    text = "Daily Transactions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }
            
            items(filteredRecords.sortedByDescending { it.timestamp }) { record ->
                val amount = parseAmount(record.amount)
                val isIncome = record.type == "Income"
                val isTransfer = record.category == "Transfer" || record.category == "Credit Payment"
                val isExcluded = !isIncome && isExcludedFromSpending(record)
                val color = when {
                    isIncome -> Color(0xFF4CAF50)
                    isTransfer -> Color(0xFF6366F1)
                    isExcluded -> MaterialTheme.colorScheme.secondary
                    else -> Color(0xFFF44336)
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(record.category, fontWeight = FontWeight.SemiBold)
                            Text(record.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            val sign = if (isIncome) "+" else if (isExcluded || isTransfer) "" else "-"
                            Text(
                                text = "$sign${String.format(Locale.getDefault(), "%,.2f", amount)} ${record.currency}",
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = SimpleDateFormat("dd MMM", Locale.getDefault()).format(record.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = String.format(Locale.getDefault(), "%,.2f EGP", amount),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
    }
}

@Composable
fun ReportCategoryRow(name: String, amount: Double, color: Color, currency: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = RoundedCornerShape(5.dp),
                color = color
            ) {}
            Spacer(modifier = Modifier.width(12.dp))
            Text(name, style = MaterialTheme.typography.bodyMedium)
        }
        Text(
            text = String.format(Locale.getDefault(), "%,.2f %s", amount, currency),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun BalanceTabContent(accounts: List<Account>, usdRate: Double, eurRate: Double, goldPriceEgpPerGram: Double? = null) {
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Net Worth (EGP equivalent)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = String.format(Locale.getDefault(), "%,.2f EGP", totalBalanceEGP),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "Debit · Cash · Gold  (credit cards excluded)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.5f)
                    )
                }
            }
        }

        item {
            Text(
                text = "Asset Distribution",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        val currencyLabels = listOf("EGP", "USD", "EUR", "Gold").filter { currencyData.containsKey(it) }
        items(currencyLabels) { label ->
            val (originalSum, amountEGP) = currencyData[label] ?: (0.0 to 0.0)
            val (displayValue, icon, color) = when (label) {
                "USD"  -> Triple(String.format(Locale.getDefault(), "%,.2f $", originalSum), Icons.Default.AttachMoney, Color(0xFF4CAF50))
                "EUR"  -> Triple(String.format(Locale.getDefault(), "%,.2f €", originalSum), Icons.Default.Euro, Color(0xFFFFC107))
                "Gold" -> Triple(String.format(Locale.getDefault(), "%.2f g", originalSum), Icons.Default.Payments, Color(0xFFFFB300))
                else   -> Triple(String.format(Locale.getDefault(), "%,.2f EGP", originalSum), Icons.Default.Payments, Color(0xFF2196F3))
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
            Text(
                text = "Accounts Breakdown (in EGP)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
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
    val progress = (Math.abs(amountEGP) / maxAbsEGP).toFloat().coerceIn(0f, 1f)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = barColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    text = displayValue,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (amountEGP >= 0) barColor else MaterialTheme.colorScheme.error
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(3.dp))
                        .background(if (amountEGP >= 0) barColor else MaterialTheme.colorScheme.error)
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
    val progress = (Math.abs(amountEGP) / maxAbsEGP).toFloat().coerceIn(0f, 1f)
    val isNegative = amountEGP < 0
    val displayColor = if (isNegative) MaterialTheme.colorScheme.error else accountColor

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                            .clip(RoundedCornerShape(2.dp))
                            .background(accountColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
                Text(
                    text = String.format(Locale.getDefault(), "%,.2f %s", originalAmount, originalCurrency),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 18.dp)
                )
            }
            Text(
                text = String.format(Locale.getDefault(), "%,.2f EGP", amountEGP),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = displayColor
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(displayColor)
            )
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
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Time range selector
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TimeRangeDropdown(selectedRange = timeRange, onRangeSelected = { timeRange = it })
            }
        }

        // Income / Expense / Net summary cards
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpendingSummaryCard(
                    label = "Income",
                    amountsPerCurrency = incomePerCurrency,
                    color = Color(0xFF22C55E),
                    icon = Icons.AutoMirrored.Filled.TrendingUp,
                    modifier = Modifier.weight(1f)
                )
                SpendingSummaryCard(
                    label = "Expense",
                    amountsPerCurrency = expensePerCurrency,
                    color = Color(0xFFEF4444),
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
                    color = Color(0xFF6366F1),
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
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (net >= 0) Color(0xFF22C55E).copy(alpha = 0.1f)
                                     else Color(0xFFEF4444).copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Net", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        text = if (singleCurrency)
                            "${if (net >= 0) "+" else ""}${String.format(Locale.getDefault(), "%,.2f", net)} $currencyLabel"
                        else
                            "${if (net >= 0) "+" else ""}${String.format(Locale.getDefault(), "%,.2f", net)} (mixed)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (net >= 0) Color(0xFF22C55E) else Color(0xFFEF4444)
                    )
                }
            }
        }

        // Income vs Expense bar chart
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Spending Over Time", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
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
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Expense by category
        if (expenseCategories.isNotEmpty()) {
            item {
                Text("Expenses by Category", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
            items(expenseCategories) { (category, byCurrency) ->
                val categoryInfo = Categories.list.flatMap { it.subCategories + it }
                    .find { it.name == category }
                CategoryCurrencyRow(
                    name = category,
                    amountsPerCurrency = byCurrency,
                    color = categoryInfo?.color ?: Color.Gray,
                    amountColor = Color(0xFFEF4444),
                    onClick = { selectedCategoryForDetail = category }
                )
            }
        }

        // Income by category
        if (incomeCategories.isNotEmpty()) {
            item {
                Text("Income by Source", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
            items(incomeCategories) { (category, byCurrency) ->
                val categoryInfo = Categories.list.flatMap { it.subCategories + it }
                    .find { it.name == category }
                CategoryCurrencyRow(
                    name = category,
                    amountsPerCurrency = byCurrency,
                    color = categoryInfo?.color ?: Color(0xFF22C55E),
                    amountColor = Color(0xFF22C55E),
                    onClick = { selectedCategoryForDetail = category }
                )
            }
        }

        // Transfers section
        if (transferRoutes.isNotEmpty()) {
            item {
                Text("Transfers", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
            }
            items(transferRoutes) { (accountName, byCurrency) ->
                CategoryCurrencyRow(
                    name = accountName,
                    amountsPerCurrency = byCurrency,
                    color = Color(0xFF6366F1),
                    amountColor = Color(0xFF6366F1),
                    onClick = { selectedTransferForDetail = accountName }
                )
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
            sheetState = smsDetailSheetState
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Sms, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(category, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Text(
                            if (smsRecords.isEmpty()) "No SMS-tracked records in this period"
                            else "${smsRecords.size} SMS-tracked transaction${if (smsRecords.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))

                if (smsRecords.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Sms, null,
                                modifier = Modifier.size(40.dp),
                                tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(8.dp))
                            Text("No SMS records for this category",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(smsRecords) { record ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (record.comment.isNotEmpty()) {
                                            Text(
                                                record.comment,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            record.accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                                .format(record.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "${if (record.type == "Income") "+" else "-"}${record.amount} ${record.currency}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (record.type == "Income") Color(0xFF22C55E) else Color(0xFFEF4444)
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
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.SwapHoriz, null,
                        tint = Color(0xFF6366F1),
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(route, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                        Text(
                            "${transferDetailRecords.size} transfer${if (transferDetailRecords.size != 1) "s" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                if (transferDetailRecords.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No records", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 440.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(transferDetailRecords) { record ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                        .fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        if (record.comment.isNotEmpty()) {
                                            Text(
                                                record.comment,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            record.accountName,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                                                .format(record.timestamp),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        text = "${record.amount} ${record.currency}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Color(0xFF6366F1)
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
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = color,
                    fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            if (amountsPerCurrency.isEmpty()) {
                Text("0.00", style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold, color = color)
            } else {
                amountsPerCurrency.forEach { (currency, amount) ->
                    Text(
                        text = "${String.format(Locale.getDefault(), "%,.2f", amount)} $currency",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = color
                    )
                }
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
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
                Spacer(Modifier.width(10.dp))
                Text(name, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                amountsPerCurrency.forEach { (currency, amount) ->
                    Text(
                        text = "${String.format(Locale.getDefault(), "%,.2f", amount)} $currency",
                        fontWeight = FontWeight.Bold,
                        color = amountColor,
                        style = MaterialTheme.typography.bodySmall
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
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 8.dp)
        ) {
            Text(selectedRange.label)
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            TimeRange.entries.forEach { range ->
                DropdownMenuItem(
                    text = { Text(range.label) },
                    onClick = {
                        onRangeSelected(range)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun CategorySpendingRow(name: String, amount: Double, color: Color, currency: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(12.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = color
            ) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(name)
        }
        Text(
            text = String.format(Locale.getDefault(), "%.2f %s", amount, currency),
            fontWeight = FontWeight.Bold
        )
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
