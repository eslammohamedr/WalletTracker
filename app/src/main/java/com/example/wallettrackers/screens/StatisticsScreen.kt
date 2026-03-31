package com.example.wallettrackers.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.converters.longToColor
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    accounts: List<Account>,
    records: List<Record>,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(StatisticsTab.SPENDING) }
    val exchangeRateApi = remember { ExchangeRateApi.create() }
    var usdToEgpRate by remember { mutableStateOf(50.0) }
    var eurToEgpRate by remember { mutableStateOf(53.0) }
    val scope = rememberCoroutineScope()

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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Credit Tab")
                    }
                }
                StatisticsTab.REPORTS -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Reports Tab")
                    }
                }
            }
        }
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
                    Icon(
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
            .filter { it.category != "Salary" && it.category != "Income" }
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
        .filter { it.category != "Salary" && it.category != "Income" }
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
