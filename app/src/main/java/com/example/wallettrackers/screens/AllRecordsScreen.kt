package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.wallettrackers.model.Account
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
    
    var showRecordOptionsDialog by remember { mutableStateOf(false) }
    var showDeleteRecordDialog by remember { mutableStateOf(false) }
    var showEditRecordDialog by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<Record?>(null) }

    val filteredRecords = remember(selectedFilter, records) {
        if (selectedFilter == null) {
            records
        } else {
            val calendar = Calendar.getInstance()
            val now = calendar.time
            records.filter { record ->
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
        }
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
        LazyColumn(
            modifier = Modifier.padding(innerPadding)
        ) {
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