package com.example.wallettrackers.screens

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

enum class FilterType {
    DAY, WEEK, MONTH, YEAR
}

private fun getDateLabel(date: Date): String {
    val cal = Calendar.getInstance().apply { time = date }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
        cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("d MMMM", Locale.getDefault()).format(date)
        else ->
            SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(date)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AllRecordsScreen(
    viewModel: HomeViewModel,
    onCategoryClick: () -> Unit,
    onBack: () -> Unit
) {
    val records by viewModel.records
    val accounts by viewModel.accounts

    var selectedFilter by remember { mutableStateOf<FilterType?>(null) }
    var selectedAccountFilter by remember { mutableStateOf<String?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }

    var showFilterDialog by remember { mutableStateOf(false) }
    var showRecordOptionsDialog by remember { mutableStateOf(false) }
    var showDeleteRecordDialog by remember { mutableStateOf(false) }

    val editingRecord by viewModel.editingRecord
    val showEditRecordDialog by viewModel.showEditDialog
    var optionSelectedRecord by remember { mutableStateOf<Record?>(null) }

    val filteredRecords = remember(selectedFilter, selectedAccountFilter, selectedCategoryFilter, records) {
        records.filter { record ->
            val timeMatch = if (selectedFilter == null) true else {
                val calendar = Calendar.getInstance()
                val now = calendar.time
                val recordDate = record.timestamp
                when (selectedFilter) {
                    FilterType.DAY -> { calendar.time = now; calendar.add(Calendar.DAY_OF_YEAR, -1); recordDate.after(calendar.time) }
                    FilterType.WEEK -> { calendar.time = now; calendar.add(Calendar.WEEK_OF_YEAR, -1); recordDate.after(calendar.time) }
                    FilterType.MONTH -> { calendar.time = now; calendar.add(Calendar.MONTH, -1); recordDate.after(calendar.time) }
                    FilterType.YEAR -> { calendar.time = now; calendar.add(Calendar.YEAR, -1); recordDate.after(calendar.time) }
                    else -> true
                }
            }
            val accountMatch = selectedAccountFilter == null || record.accountName == selectedAccountFilter
            val categoryMatch = selectedCategoryFilter == null || record.category == selectedCategoryFilter
            timeMatch && accountMatch && categoryMatch
        }
    }

    val groupedRecords = remember(filteredRecords) {
        filteredRecords.groupBy { getDateLabel(it.timestamp) }.entries.toList()
    }

    if (showFilterDialog) {
        FilterDialog(
            accounts = accounts,
            currentAccount = selectedAccountFilter,
            currentCategory = selectedCategoryFilter,
            onDismiss = { showFilterDialog = false },
            onApply = { acc, cat -> selectedAccountFilter = acc; selectedCategoryFilter = cat; showFilterDialog = false }
        )
    }

    if (showRecordOptionsDialog && optionSelectedRecord != null) {
        OptionsDialog(
            onDismiss = { showRecordOptionsDialog = false },
            onEdit = { showRecordOptionsDialog = false; viewModel.startEditing(optionSelectedRecord!!) },
            onDelete = { showRecordOptionsDialog = false; showDeleteRecordDialog = true }
        )
    }

    if (showDeleteRecordDialog && optionSelectedRecord != null) {
        DeleteConfirmationDialog(
            onDismiss = { showDeleteRecordDialog = false },
            onConfirm = { viewModel.deleteRecord(optionSelectedRecord!!.id); showDeleteRecordDialog = false },
            title = "Delete Record",
            text = "Are you sure you want to delete this record?"
        )
    }

    if (showEditRecordDialog && editingRecord != null) {
        RecordDialog(
            record = editingRecord,
            accounts = accounts,
            onDismiss = { viewModel.stopEditing() },
            onConfirm = { updatedRecord -> viewModel.updateRecord(updatedRecord); viewModel.stopEditing() },
            onCategoryClick = onCategoryClick,
            title = "Edit Record",
            confirmButtonText = "Update"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All Records", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedAccountFilter != null || selectedCategoryFilter != null) {
                        IconButton(onClick = { selectedAccountFilter = null; selectedCategoryFilter = null }) {
                            Icon(Icons.Default.FilterListOff, contentDescription = "Clear Filters",
                                tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = { showFilterDialog = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(
                    FilterType.DAY to "Day",
                    FilterType.WEEK to "Week",
                    FilterType.MONTH to "Month",
                    FilterType.YEAR to "Year"
                ).forEach { (filter, label) ->
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = if (selectedFilter == filter) null else filter }
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Active filter chips
            if (selectedAccountFilter != null || selectedCategoryFilter != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedAccountFilter?.let {
                        FilterChip(
                            selected = true,
                            onClick = { selectedAccountFilter = null },
                            label = { Text(it) },
                            trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                    selectedCategoryFilter?.let {
                        FilterChip(
                            selected = true,
                            onClick = { selectedCategoryFilter = null },
                            label = { Text(it) },
                            trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (filteredRecords.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "No records found",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Try adjusting your filters",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    groupedRecords.forEach { (dateLabel, dayRecords) ->
                        stickyHeader(key = dateLabel) {
                            Surface(
                                color = MaterialTheme.colorScheme.background,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = dateLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    val uniqueCurrencies = dayRecords.map { it.currency }.distinct()
                                    if (uniqueCurrencies.size == 1) {
                                        val dayNet = dayRecords.sumOf {
                                            if (it.type == "Income") it.amount.toDoubleOrNull() ?: 0.0
                                            else -(it.amount.toDoubleOrNull() ?: 0.0)
                                        }
                                        Text(
                                            text = "${if (dayNet >= 0) "+" else ""}${
                                                String.format(Locale.getDefault(), "%.2f", dayNet)
                                            } ${uniqueCurrencies.first()}",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (dayNet >= 0) Color(0xFF22C55E) else Color(0xFFEF4444)
                                        )
                                    }
                                }
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                        items(dayRecords, key = { it.id }) { record ->
                            RecordCard(
                                record = record,
                                onLongClick = {
                                    optionSelectedRecord = record
                                    showRecordOptionsDialog = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDialog(
    accounts: List<Account>,
    currentAccount: String?,
    currentCategory: String?,
    onDismiss: () -> Unit,
    onApply: (String?, String?) -> Unit
) {
    var selectedAccount by remember { mutableStateOf(currentAccount) }
    var selectedCategory by remember { mutableStateOf(currentCategory) }
    var accExpanded by remember { mutableStateOf(false) }
    var catExpanded by remember { mutableStateOf(false) }

    val allCategories = remember {
        Categories.list.flatMap { listOf(it.name) + it.subCategories.map { sub -> sub.name } }.distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter Records", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExposedDropdownMenuBox(expanded = accExpanded, onExpandedChange = { accExpanded = !accExpanded }) {
                    OutlinedTextField(
                        value = selectedAccount ?: "All Accounts",
                        onValueChange = {},
                        label = { Text("Account") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = accExpanded, onDismissRequest = { accExpanded = false }) {
                        DropdownMenuItem(text = { Text("All Accounts") }, onClick = { selectedAccount = null; accExpanded = false })
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = { selectedAccount = account.name; accExpanded = false }
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = !catExpanded }) {
                    OutlinedTextField(
                        value = selectedCategory ?: "All Categories",
                        onValueChange = {},
                        label = { Text("Category") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                        DropdownMenuItem(text = { Text("All Categories") }, onClick = { selectedCategory = null; catExpanded = false })
                        allCategories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = { selectedCategory = cat; catExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(selectedAccount, selectedCategory) }, shape = RoundedCornerShape(10.dp)) {
                Text("Apply")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Cancel")
            }
        }
    )
}
