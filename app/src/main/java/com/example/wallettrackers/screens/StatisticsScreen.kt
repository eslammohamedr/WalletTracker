package com.example.wallettrackers.screens

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
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.wallettrackers.converters.longToColor
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.remote.ExchangeRateApi
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

private fun parseAmount(amountStr: String): Double {
    val cleanStr = amountStr.replace(Regex("[^0-9.\\-]"), "")
    return cleanStr.toDoubleOrNull() ?: 0.0
}

private fun getCurrencyType(currency: String, accountName: String): String {
    val c = currency.uppercase()
    val n = accountName.uppercase()
    return when {
        c.contains("USD") || c.contains("DOLLAR") || c.contains("$") || 
        n.contains("USD") || n.contains("DOLLAR") -> "USD"
        c.contains("EUR") || c.contains("EURO") || c.contains("€") || 
        n.contains("EUR") || n.contains("EURO") -> "EUR"
        else -> "EGP"
    }
}

private fun convertToEGP(amount: Double, currency: String, accountName: String, usdRate: Double, eurRate: Double): Double {
    return when (getCurrencyType(currency, accountName)) {
        "USD" -> amount * usdRate
        "EUR" -> amount * eurRate
        else -> amount
    }
}

private fun isExcludedFromSpending(record: Record): Boolean {
    // Exclude ATM withdrawals and Credit Card payments from "Spending/Expenses" total
    // as they are internal transfers and do not reduce net worth.
    val category = record.category.lowercase()
    val comment = (record.comment ?: "").lowercase()
    val accountName = record.accountName.lowercase()
    
    return category == "salary" || 
           category == "income" || 
           category == "credit" ||
           category == "credit payment" ||
           comment.contains("atm withdrawal") ||
           accountName.contains("->") // Matches "Account A -> Account B" transfer format
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    accounts: List<Account>,
    records: List<Record>,
    statements: List<CreditStatement> = emptyList(),
    toastMessage: String? = null,
    onToastShown: () -> Unit = {},
    onPayClick: (CreditStatement, Account) -> Unit = { _, _ -> },
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(StatisticsTab.SPENDING) }
    val exchangeRateApi = remember { ExchangeRateApi.create() }
    var usdToEgpRate by remember { mutableStateOf(50.0) }
    var eurToEgpRate by remember { mutableStateOf(53.0) }
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
                        eurRate = eurToEgpRate
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
                    Text(
                        SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(statement.dueDate),
                        fontWeight = FontWeight.Bold
                    )
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

    val incomeRecords = filteredRecords.filter { it.category == "Salary" || it.category == "Income" }
    val expenseRecords = filteredRecords.filter { !isExcludedFromSpending(it) }

    val totalIncomeEGP = incomeRecords.sumOf { convertToEGP(parseAmount(it.amount), it.currency, it.accountName, usdRate, eurRate) }
    val totalExpenseEGP = expenseRecords.sumOf { convertToEGP(parseAmount(it.amount), it.currency, it.accountName, usdRate, eurRate) }
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
                    
                    SummaryRow("Income", totalIncomeEGP, Color(0xFF4CAF50), Icons.Default.TrendingUp)
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                    )
                    SummaryRow("Expenses", totalExpenseEGP, Color(0xFFF44336), Icons.Default.TrendingDown)
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
                val isExcluded = isExcludedFromSpending(record)
                val isIncome = record.category == "Salary" || record.category == "Income"
                val color = when {
                    isIncome -> Color(0xFF4CAF50)
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
                            val sign = if (isIncome) "+" else if (isExcluded) "" else "-"
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
fun BalanceTabContent(accounts: List<Account>, usdRate: Double, eurRate: Double) {
    val totalBalanceEGP = remember(accounts, usdRate, eurRate) {
        accounts.sumOf {
            val amount = parseAmount(it.amount)
            convertToEGP(amount, it.currency, it.name, usdRate, eurRate)
        }
    }

    val currencyData = remember(accounts, usdRate, eurRate) {
        accounts.groupBy { 
            getCurrencyType(it.currency, it.name)
        }.mapValues { entry ->
            val originalSum = entry.value.sumOf { parseAmount(it.amount) }
            val egpSum = entry.value.sumOf { 
                val amount = parseAmount(it.amount)
                convertToEGP(amount, it.currency, it.name, usdRate, eurRate)
            }
            originalSum to egpSum
        }
    }

    val maxAbsEGP = remember(accounts, usdRate, eurRate) {
        val max = accounts.maxOfOrNull {
            val amount = parseAmount(it.amount)
            Math.abs(convertToEGP(amount, it.currency, it.name, usdRate, eurRate))
        } ?: 1.0
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
                        text = "Total Assets (Converted to EGP)",
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
                }
            }
        }

        item {
            Text(
                text = "Currency Distribution",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        val currencyLabels = listOf("EGP", "USD", "EUR")
        items(currencyLabels) { label ->
            val (originalSum, amountEGP) = currencyData[label] ?: (0.0 to 0.0)
            val (symbol, icon, color) = when(label) {
                "USD" -> Triple("$", Icons.Default.AttachMoney, Color(0xFF4CAF50))
                "EUR" -> Triple("€", Icons.Default.Euro, Color(0xFFFFC107))
                else -> Triple("EGP", Icons.Default.Payments, Color(0xFF2196F3))
            }
            SimpleBalanceBar(
                label = label,
                amountEGP = amountEGP,
                maxAbsEGP = maxCurrencyEGP,
                displayValue = String.format(Locale.getDefault(), "%,.2f %s", originalSum, symbol),
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

        val sortedAccounts = accounts.sortedByDescending { 
            val amount = parseAmount(it.amount)
            convertToEGP(amount, it.currency, it.name, usdRate, eurRate) 
        }

        items(sortedAccounts) { account ->
            val amount = parseAmount(account.amount)
            val balanceEGP = convertToEGP(amount, account.currency, account.name, usdRate, eurRate)
            
            AccountBalanceRow(
                name = account.name,
                amountEGP = balanceEGP,
                originalAmount = amount,
                originalCurrency = account.currency,
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

@Composable
fun SpendingTabContent(records: List<Record>) {
    var chartTimeRange by remember { mutableStateOf(TimeRange.LAST_WEEK) }
    var categoryTimeRange by remember { mutableStateOf(TimeRange.LAST_WEEK) }

    val modelProducer = remember { CartesianChartModelProducer() }

    // Filter logic for Chart
    val chartRecords = remember(records, chartTimeRange) {
        filterRecordsByRange(records, chartTimeRange)
    }

    // Filter logic for Category Spending
    val categoryRecords = remember(records, categoryTimeRange) {
        filterRecordsByRange(records, categoryTimeRange)
    }

    // Prepare data for Daily Spending Chart
    val dailyTotals = remember(chartRecords) {
        chartRecords
            .filter { !isExcludedFromSpending(it) }
            .groupBy {
                SimpleDateFormat("dd/MM", Locale.getDefault()).format(it.timestamp)
            }
            .mapValues { entry -> entry.value.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
            .toList()
            .takeLast(7)
    }

    LaunchedEffect(dailyTotals) {
        if (dailyTotals.isNotEmpty()) {
            modelProducer.runTransaction {
                columnSeries {
                    series(dailyTotals.map { it.second })
                }
            }
        }
    }

    // Category distribution
    val categoryTotals = categoryRecords
        .filter { !isExcludedFromSpending(it) }
        .groupBy { it.category }
        .mapValues { it.value.sumOf { rec -> rec.amount.toDoubleOrNull() ?: 0.0 } }
        .toList()
        .sortedByDescending { it.second }

    val totalSpending = remember(categoryTotals) { categoryTotals.sumOf { it.second } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Expenses Chart",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TimeRangeDropdown(
                    selectedRange = chartTimeRange,
                    onRangeSelected = { chartTimeRange = it }
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            if (dailyTotals.isNotEmpty()) {
                CartesianChartHost(
                    chart = rememberCartesianChart(
                        rememberColumnCartesianLayer(),
                        startAxis = VerticalAxis.rememberStart(),
                        bottomAxis = HorizontalAxis.rememberBottom(
                            valueFormatter = CartesianValueFormatter { _, value, _ ->
                                dailyTotals.getOrNull(value.toInt())?.first ?: ""
                            }
                        ),
                    ),
                    modelProducer = modelProducer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                )
            } else {
                Text("No expense data available for this period", style = MaterialTheme.typography.bodyMedium)
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Spending by Category",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                TimeRangeDropdown(
                    selectedRange = categoryTimeRange,
                    onRangeSelected = { categoryTimeRange = it }
                )
            }
            
            if (categoryTotals.isNotEmpty()) {
                val currency = records.firstOrNull()?.currency ?: ""
                Text(
                    text = String.format(Locale.getDefault(), "Total: %.2f %s", totalSpending, currency),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (categoryTotals.isEmpty()) {
                Text("No expenses found", style = MaterialTheme.typography.bodySmall)
            }
            categoryTotals.forEach { (categoryName, total) ->
                val categoryInfo = Categories.list.flatMap { it.subCategories + it }
                    .find { it.name == categoryName }

                CategorySpendingRow(
                    name = categoryName,
                    amount = total,
                    color = categoryInfo?.color ?: Color.Gray,
                    currency = records.firstOrNull()?.currency ?: ""
                )
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
