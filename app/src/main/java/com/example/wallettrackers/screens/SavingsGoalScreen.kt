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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.SavingsGoal
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavingsGoalScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val goals by viewModel.savingsGoals
    var showDialog by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf<SavingsGoal?>(null) }
    var deleteGoal by remember { mutableStateOf<SavingsGoal?>(null) }
    var addFundsGoal by remember { mutableStateOf<SavingsGoal?>(null) }

    if (showDialog || editingGoal != null) {
        GoalDialog(
            goal = editingGoal,
            onDismiss = { showDialog = false; editingGoal = null },
            onConfirm = { g ->
                if (editingGoal != null) viewModel.updateSavingsGoal(g) else viewModel.addSavingsGoal(g)
                showDialog = false; editingGoal = null
            }
        )
    }

    deleteGoal?.let { g ->
        DeleteConfirmationDialog(
            onDismiss = { deleteGoal = null },
            onConfirm = { viewModel.deleteSavingsGoal(g.id); deleteGoal = null },
            title = "Delete Goal",
            text = "Delete savings goal \"${g.name}\"?"
        )
    }

    addFundsGoal?.let { g ->
        AddFundsDialog(
            goal = g,
            onDismiss = { addFundsGoal = null },
            onConfirm = { amount ->
                viewModel.updateSavingsGoal(g.copy(savedAmount = (g.savedAmount + amount).coerceAtMost(g.targetAmount)))
                addFundsGoal = null
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Savings Goals", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, null) }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        if (goals.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Savings, null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text("No savings goals yet", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Tap + to create a goal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(goals, key = { it.id }) { goal ->
                    GoalCard(
                        goal = goal,
                        onEdit = { editingGoal = goal },
                        onDelete = { deleteGoal = goal },
                        onAddFunds = { addFundsGoal = goal }
                    )
                }
            }
        }
    }
}

@Composable
private fun GoalCard(goal: SavingsGoal, onEdit: () -> Unit, onDelete: () -> Unit, onAddFunds: () -> Unit) {
    val progress = if (goal.targetAmount > 0) (goal.savedAmount / goal.targetAmount).toFloat().coerceIn(0f, 1f) else 0f
    val remaining = goal.targetAmount - goal.savedAmount
    val isComplete = goal.savedAmount >= goal.targetAmount
    val daysLeft = goal.targetDate?.let {
        val diff = it.time - System.currentTimeMillis()
        (diff / (1000 * 60 * 60 * 24)).toInt()
    }

    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(goal.emoji, style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(goal.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                        if (daysLeft != null) {
                            Text(
                                if (daysLeft > 0) "$daysLeft days left" else if (daysLeft == 0) "Due today" else "Overdue",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (daysLeft <= 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                Row {
                    IconButton(onClick = onEdit, Modifier.size(32.dp)) { Icon(Icons.Default.Edit, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    IconButton(onClick = onDelete, Modifier.size(32.dp)) { Icon(Icons.Default.Delete, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error) }
                }
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(10.dp),
                color = if (isComplete) Color(0xFF4CAF50) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.primaryContainer
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${"%.2f".format(goal.savedAmount)} ${goal.currency}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                Text("${"%.2f".format(goal.targetAmount)} ${goal.currency}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!isComplete) {
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = onAddFunds, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)) {
                    Icon(Icons.Default.AddCircle, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add ${"%.2f".format(remaining)} more to complete")
                }
            } else {
                Spacer(Modifier.height(8.dp))
                Text("Goal achieved!", style = MaterialTheme.typography.labelMedium, color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDialog(goal: SavingsGoal?, onDismiss: () -> Unit, onConfirm: (SavingsGoal) -> Unit) {
    var name by remember { mutableStateOf(goal?.name ?: "") }
    var target by remember { mutableStateOf(goal?.targetAmount?.toString() ?: "") }
    var saved by remember { mutableStateOf(goal?.savedAmount?.toString() ?: "0") }
    var currency by remember { mutableStateOf(goal?.currency ?: "EGP") }
    var emoji by remember { mutableStateOf(goal?.emoji ?: "🎯") }
    var curExpanded by remember { mutableStateOf(false) }
    val emojis = listOf("🎯","🏠","✈️","🚗","💍","📱","💪","🎓","🌴","💰")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (goal == null) "New Savings Goal" else "Edit Goal", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Emoji picker
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    emojis.forEach { e ->
                        Surface(onClick = { emoji = e }, shape = RoundedCornerShape(8.dp),
                            color = if (emoji == e) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant) {
                            Text(e, modifier = Modifier.padding(6.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Goal Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = target, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) target = it }, label = { Text("Target Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                OutlinedTextField(value = saved, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) saved = it }, label = { Text("Already Saved") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                ExposedDropdownMenuBox(expanded = curExpanded, onExpandedChange = { curExpanded = !curExpanded }) {
                    OutlinedTextField(value = currency, onValueChange = {}, label = { Text("Currency") }, readOnly = true, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(curExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), shape = RoundedCornerShape(12.dp))
                    ExposedDropdownMenu(expanded = curExpanded, onDismissRequest = { curExpanded = false }) {
                        listOf("EGP", "Dollar", "Euro").forEach { c -> DropdownMenuItem(text = { Text(c) }, onClick = { currency = c; curExpanded = false }) }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm((goal ?: SavingsGoal()).copy(name = name, targetAmount = target.toDoubleOrNull() ?: 0.0, savedAmount = saved.toDoubleOrNull() ?: 0.0, currency = currency, emoji = emoji))
            }, enabled = name.isNotBlank() && target.isNotBlank(), shape = RoundedCornerShape(10.dp)) { Text("Save") }
        },
        dismissButton = { OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) { Text("Cancel") } }
    )
}

@Composable
private fun AddFundsDialog(goal: SavingsGoal, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Funds", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(value = amount, onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it }, label = { Text("Amount to add") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), suffix = { Text(goal.currency) })
        },
        confirmButton = { Button(onClick = { onConfirm(amount.toDoubleOrNull() ?: 0.0) }, enabled = amount.isNotBlank(), shape = RoundedCornerShape(10.dp)) { Text("Add") } },
        dismissButton = { OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) { Text("Cancel") } }
    )
}
