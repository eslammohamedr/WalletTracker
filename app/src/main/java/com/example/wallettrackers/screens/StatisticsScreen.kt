package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatisticsScreen(
    records: List<Record>,
    onBack: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(StatisticsTab.SPENDING) }

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
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Balance Tab")
                    }
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
