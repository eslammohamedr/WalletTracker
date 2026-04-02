package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import java.util.*

enum class FilterType {
    DAY, WEEK, MONTH, YEAR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AllRecordsScreen(
    records: List<Record>,
    accounts: List<Account>,
    onUpdateRecord: (Record) -> Unit,
    onDeleteRecord: (String) -> Unit,
    onBack: () -> Unit
) {
    var selectedFilter by remember { mutableStateOf<FilterType?>(null) }
    var selectedAccountFilter by remember { mutableStateOf<String?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }
    
    var showFilterDialog by remember { mutableStateOf(false) }
    var showRecordOptionsDialog by remember { mutableStateOf(false) }
    var showDeleteRecordDialog by remember { mutableStateOf(false) }
    var showEditRecordDialog by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<Record?>(null) }

    val filteredRecords = remember(selectedFilter, selectedAccountFilter, selectedCategoryFilter, records) {
        records.filter { record ->
            // Time Filter
            val timeMatch = if (selectedFilter == null) {
                true
            } else {
                val calendar = Calendar.getInstance()
                val now = calendar.time
                val recordDate = record.timestamp
                when (selectedFilter) {
                    FilterType.DAY -> {
                        calendar.time = now
                        calendar.add(Calendar.DAY_OF_YEAR, -1)
                        recordDate.after(calendar.time)
                    }
                    FilterType.WEEK -> {
                        calendar.time = now
                        calendar.add(Calendar.WEEK_OF_YEAR, -1)
                        recordDate.after(calendar.time)
                    }
                    FilterType.MONTH -> {
                        calendar.time = now
                        calendar.add(Calendar.MONTH, -1)
                        recordDate.after(calendar.time)
                    }
                    FilterType.YEAR -> {
                        calendar.time = now
                        calendar.add(Calendar.YEAR, -1)
                        recordDate.after(calendar.time)
                    }
                    else -> true
                }
            }
            
            // Account Filter
            val accountMatch = selectedAccountFilter == null || record.accountName == selectedAccountFilter
            
            // Category Filter
            val categoryMatch = selectedCategoryFilter == null || record.category == selectedCategoryFilter
            
            timeMatch && accountMatch && categoryMatch
        }
    }

    if (showFilterDialog) {
        FilterDialog(
            accounts = accounts,
            currentAccount = selectedAccountFilter,
            currentCategory = selectedCategoryFilter,
            onDismiss = { showFilterDialog = false },
            onApply = { acc, cat ->
                selectedAccountFilter = acc
                selectedCategoryFilter = cat
                showFilterDialog = false
            }
        )
    }

    if (showRecordOptionsDialog && selectedRecord != null) {
        OptionsDialog(
            onDismiss = { showRecordOptionsDialog = false },
            onEdit = {
                showRecordOptionsDialog = false
                showEditRecordDialog = true
            },
            onDelete = {
                showRecordOptionsDialog = false
                showDeleteRecordDialog = true
            }
        )
    }

    if (showDeleteRecordDialog && selectedRecord != null) {
        DeleteConfirmationDialog(
            onDismiss = { showDeleteRecordDialog = false },
            onConfirm = {
                onDeleteRecord(selectedRecord!!.id)
                showDeleteRecordDialog = false
            },
            title = "Delete Record",
            text = "Are you sure you want to delete this record?"
        )
    }

    if (showEditRecordDialog && selectedRecord != null) {
        RecordDialog(
            record = selectedRecord,
            accounts = accounts,
            onDismiss = { showEditRecordDialog = false },
            onConfirm = { updatedRecord ->
                onUpdateRecord(updatedRecord)
            },
            title = "Edit Record",
            confirmButtonText = "Update"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("All Records") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedAccountFilter != null || selectedCategoryFilter != null) {
                        IconButton(onClick = {
                            selectedAccountFilter = null
                            selectedCategoryFilter = null
                        }) {
                            Icon(Icons.Default.FilterListOff, contentDescription = "Clear Filters")
                        }
                    }
                    IconButton(onClick = { showFilterDialog = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.Today, contentDescription = "Day") },
                    label = { Text("Day") },
                    selected = selectedFilter == FilterType.DAY,
                    onClick = { selectedFilter = if (selectedFilter == FilterType.DAY) null else FilterType.DAY }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = "Week") },
                    label = { Text("Week") },
                    selected = selectedFilter == FilterType.WEEK,
                    onClick = { selectedFilter = if (selectedFilter == FilterType.WEEK) null else FilterType.WEEK }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = "Month") },
                    label = { Text("Month") },
                    selected = selectedFilter == FilterType.MONTH,
                    onClick = { selectedFilter = if (selectedFilter == FilterType.MONTH) null else FilterType.MONTH }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = "Year") },
                    label = { Text("Year") },
                    selected = selectedFilter == FilterType.YEAR,
                    onClick = { selectedFilter = if (selectedFilter == FilterType.YEAR) null else FilterType.YEAR }
                )
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Filter chips display
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
                            Text("No records found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    items(filteredRecords) { record ->
                        RecordCard(
                            record = record,
                            onLongClick = {
                                selectedRecord = record
                                showRecordOptionsDialog = true
                            }
                        ) 
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
        title = { Text("Filter Records") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Account Dropdown
                ExposedDropdownMenuBox(
                    expanded = accExpanded,
                    onExpandedChange = { accExpanded = !accExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedAccount ?: "All Accounts",
                        onValueChange = {},
                        label = { Text("Account") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
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

                // Category Dropdown
                ExposedDropdownMenuBox(
                    expanded = catExpanded,
                    onExpandedChange = { catExpanded = !catExpanded }
                ) {
                    OutlinedTextField(
                        value = selectedCategory ?: "All Categories",
                        onValueChange = {},
                        label = { Text("Category") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                        DropdownMenuItem(text = { Text("All Categories") }, onClick = { selectedCategory = null; catExpanded = false })
                        allCategories.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category) },
                                onClick = { selectedCategory = category; catExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onApply(selectedAccount, selectedCategory) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
