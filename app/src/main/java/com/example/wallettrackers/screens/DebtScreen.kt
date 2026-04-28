package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val debts by viewModel.debts
    val activeDebts = remember(debts) { debts.filter { !it.isSettled } }
    val settledDebts = remember(debts) { debts.filter { it.isSettled } }

    var showDialog by remember { mutableStateOf(false) }
    var editingDebt by remember { mutableStateOf<Debt?>(null) }
    var deleteDebt by remember { mutableStateOf<Debt?>(null) }

    val totalOwedToMe = remember(activeDebts) { activeDebts.filter { it.isOwedToMe }.sumOf { it.amount } }
    val totalIOwe = remember(activeDebts) { activeDebts.filter { !it.isOwedToMe }.sumOf { it.amount } }

    if (showDialog || editingDebt != null) {
        DebtDialog(
            debt = editingDebt,
            onDismiss = { showDialog = false; editingDebt = null },
            onConfirm = { d ->
                if (editingDebt != null) viewModel.updateDebt(d) else viewModel.addDebt(d)
                showDialog = false; editingDebt = null
            }
        )
    }

    deleteDebt?.let { d ->
        DeleteConfirmationDialog(
            onDismiss = { deleteDebt = null },
            onConfirm = { viewModel.deleteDebt(d.id); deleteDebt = null },
            title = "Delete Debt", text = "Remove debt with ${d.personName}?"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debt Tracker", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, null) } },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Summary cards
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DebtSummaryCard("Owed to Me", totalOwedToMe, "EGP", Color(0xFF4CAF50), Modifier.weight(1f))
                    DebtSummaryCard("I Owe", totalIOwe, "EGP", Color(0xFFEF4444), Modifier.weight(1f))
                }
            }

            if (activeDebts.isNotEmpty()) {
                item { Text("Active", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) }
                items(activeDebts, key = { it.id }) { debt ->
                    DebtCard(debt = debt, onEdit = { editingDebt = debt }, onDelete = { deleteDebt = debt }, onSettle = { viewModel.updateDebt(debt.copy(isSettled = true)) })
                }
            }

            if (settledDebts.isNotEmpty()) {
                item { Text("Settled", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.outline) }
                items(settledDebts, key = { it.id }) { debt ->
                    DebtCard(debt = debt, onEdit = { editingDebt = debt }, onDelete = { deleteDebt = debt }, onSettle = null)
                }
            }

            if (debts.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.People, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.height(12.dp))
                            Text("No debts recorded", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtSummaryCard(label: String, amount: Double, currency: String, color: Color, modifier: Modifier) {
    Card(modifier = modifier, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
            Text("${"%.2f".format(amount)} $currency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun DebtCard(debt: Debt, onEdit: () -> Unit, onDelete: () -> Unit, onSettle: (() -> Unit)?) {
    val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Surface(color = if (debt.isOwedToMe) Color(0xFF4CAF50).copy(0.15f) else Color(0xFFEF4444).copy(0.15f), shape = CircleShape, modifier = Modifier.fillMaxSize()) {}
                Text(debt.personName.firstOrNull()?.uppercase() ?: "?", fontWeight = FontWeight.Bold,
                    color = if (debt.isOwedToMe) Color(0xFF4CAF50) else Color(0xFFEF4444))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(debt.personName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                if (debt.description.isNotEmpty()) Text(debt.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                debt.dueDate?.let { Text("Due: ${fmt.format(it)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "${if (debt.isOwedToMe) "+" else "-"}${"%.2f".format(debt.amount)} EGP",
                    fontWeight = FontWeight.Bold,
                    color = if (debt.isOwedToMe) Color(0xFF4CAF50) else Color(0xFFEF4444),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (debt.isSettled) {
                    Text("Settled", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    Row {
                        onSettle?.let { settle ->
                            TextButton(onClick = settle, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("Settle", style = MaterialTheme.typography.labelSmall) }
                        }
                        IconButton(onClick = onEdit, Modifier.size(28.dp)) { Icon(Icons.Default.Edit, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = onDelete, Modifier.size(28.dp)) { Icon(Icons.Default.Delete, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebtDialog(debt: Debt?, onDismiss: () -> Unit, onConfirm: (Debt) -> Unit) {
    var personName by remember { mutableStateOf(debt?.personName ?: "") }
    var amount by remember { mutableStateOf(debt?.amount?.toString() ?: "") }
    var description by remember { mutableStateOf(debt?.description ?: "") }
    var isOwedToMe by remember { mutableStateOf(debt?.isOwedToMe ?: true) }
    var currency by remember { mutableStateOf(debt?.currency ?: "EGP") }
    var curExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (debt == null) "Add Debt" else "Edit Debt", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isOwedToMe, onClick = { isOwedToMe = true }, label = { Text("They Owe Me") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = !isOwedToMe, onClick = { isOwedToMe = false }, label = { Text("I Owe Them") }, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(value = personName, onValueChange = { personName = it }, label = { Text("Person Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = amount, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                ExposedDropdownMenuBox(expanded = curExpanded, onExpandedChange = { curExpanded = !curExpanded }) {
                    OutlinedTextField(value = currency, onValueChange = {}, label = { Text("Currency") }, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(curExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    ExposedDropdownMenu(expanded = curExpanded, onDismissRequest = { curExpanded = false }) {
                        listOf("EGP", "Dollar", "Euro").forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { currency = c; curExpanded = false }) }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm((debt ?: Debt()).copy(personName = personName, amount = amount.toDoubleOrNull() ?: 0.0, description = description, isOwedToMe = isOwedToMe, currency = currency)) }, enabled = personName.isNotBlank() && amount.isNotBlank(), shape = RoundedCornerShape(10.dp)) { Text("Save") } },
        dismissButton = { OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) { Text("Cancel") } }
    )
}
