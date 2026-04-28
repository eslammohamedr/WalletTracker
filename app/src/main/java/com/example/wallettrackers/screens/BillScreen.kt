package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val bills by viewModel.bills
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var editingBill by remember { mutableStateOf<Bill?>(null) }
    var deleteBill by remember { mutableStateOf<Bill?>(null) }

    val totalMonthly = remember(bills) { bills.filter { it.isActive }.sumOf { it.amount } }
    val cal = Calendar.getInstance()
    val currentDay = cal.get(Calendar.DAY_OF_MONTH)

    if (showDialog || editingBill != null) {
        BillDialog(
            bill = editingBill,
            onDismiss = { showDialog = false; editingBill = null },
            onConfirm = { b ->
                if (editingBill != null) viewModel.updateBill(b, context) else viewModel.addBill(b, context)
                showDialog = false; editingBill = null
            }
        )
    }

    deleteBill?.let { b ->
        DeleteConfirmationDialog(
            onDismiss = { deleteBill = null },
            onConfirm = { viewModel.deleteBill(b.id, context); deleteBill = null },
            title = "Delete Bill", text = "Remove bill \"${b.name}\"?"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monthly Bills", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, null) } },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (bills.isNotEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Row(Modifier.padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("Monthly Total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
                                Text("${"%.2f".format(totalMonthly)} EGP", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Icon(Icons.Default.Receipt, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(0.7f))
                        }
                    }
                }
            }

            if (bills.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.ReceiptLong, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("No bills tracked", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tap + to add a recurring bill", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            } else {
                val sorted = bills.sortedBy { it.dayOfMonth }
                items(sorted, key = { it.id }) { bill ->
                    BillCard(
                        bill = bill,
                        currentDay = currentDay,
                        onEdit = { editingBill = bill },
                        onDelete = { deleteBill = bill },
                        onToggle = { viewModel.updateBill(bill.copy(isActive = !bill.isActive), context) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BillCard(bill: Bill, currentDay: Int, onEdit: () -> Unit, onDelete: () -> Unit, onToggle: () -> Unit) {
    val daysUntil = if (bill.dayOfMonth >= currentDay) bill.dayOfMonth - currentDay else (30 - currentDay + bill.dayOfMonth)
    val isDueSoon = daysUntil <= 3
    val catIcon = Categories.list.flatMap { it.subCategories + it }.find { it.name == bill.category }?.icon ?: Icons.Default.Receipt

    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = if (bill.isActive) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant.copy(0.5f)), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(catIcon, null, Modifier.size(28.dp), tint = if (bill.isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(bill.name, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                Text("Day ${bill.dayOfMonth} of each month", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (bill.isActive && isDueSoon) {
                    Text(if (daysUntil == 0) "Due today!" else "Due in $daysUntil days", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${"%.2f".format(bill.amount)} ${bill.currency}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Row {
                    Switch(checked = bill.isActive, onCheckedChange = { onToggle() }, modifier = Modifier.height(24.dp).width(40.dp))
                    IconButton(onClick = onEdit, Modifier.size(28.dp)) { Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = onDelete, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BillDialog(bill: Bill?, onDismiss: () -> Unit, onConfirm: (Bill) -> Unit) {
    var name by remember { mutableStateOf(bill?.name ?: "") }
    var amount by remember { mutableStateOf(bill?.amount?.toString() ?: "") }
    var dayOfMonth by remember { mutableStateOf(bill?.dayOfMonth?.toString() ?: "1") }
    var category by remember { mutableStateOf(bill?.category ?: "Housing") }
    var currency by remember { mutableStateOf(bill?.currency ?: "EGP") }
    var catExpanded by remember { mutableStateOf(false) }
    var curExpanded by remember { mutableStateOf(false) }
    val allCategories = remember { Categories.list.map { it.name } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (bill == null) "Add Bill" else "Edit Bill", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Bill Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = amount, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it }, label = { Text("Monthly Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = dayOfMonth, onValueChange = { val n = it.toIntOrNull(); if (it.isEmpty() || (n != null && n in 1..31)) dayOfMonth = it }, label = { Text("Due Day (1-31)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = !catExpanded }) {
                    OutlinedTextField(value = category, onValueChange = {}, label = { Text("Category") }, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                        allCategories.forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { category = c; catExpanded = false }) }
                    }
                }
                ExposedDropdownMenuBox(expanded = curExpanded, onExpandedChange = { curExpanded = !curExpanded }) {
                    OutlinedTextField(value = currency, onValueChange = {}, label = { Text("Currency") }, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(curExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    ExposedDropdownMenu(expanded = curExpanded, onDismissRequest = { curExpanded = false }) {
                        listOf("EGP", "Dollar", "Euro").forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { currency = c; curExpanded = false }) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm((bill ?: Bill()).copy(name = name, amount = amount.toDoubleOrNull() ?: 0.0, dayOfMonth = dayOfMonth.toIntOrNull() ?: 1, category = category, currency = currency)) }, enabled = name.isNotBlank() && amount.isNotBlank(), shape = RoundedCornerShape(10.dp)) { Text("Save") } },
        dismissButton = { OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) { Text("Cancel") } }
    )
}
