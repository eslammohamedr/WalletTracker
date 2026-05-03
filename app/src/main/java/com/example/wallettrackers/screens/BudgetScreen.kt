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
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.util.BudgetCalculator
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val categorySubcategoryMap: Map<String, List<String>> by lazy {
    Categories.list.associate { parent ->
        parent.name to parent.subCategories.map { it.name }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val budgets by viewModel.budgets
    val records by viewModel.records
    var showAddDialog by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<Budget?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Budget?>(null) }

    val now = remember { Calendar.getInstance() }
    var selectedMonth by remember { mutableStateOf(now.get(Calendar.MONTH)) }
    var selectedYear by remember { mutableStateOf(now.get(Calendar.YEAR)) }

    val monthLabel = remember(selectedMonth, selectedYear) {
        val cal = Calendar.getInstance().apply { set(Calendar.MONTH, selectedMonth); set(Calendar.YEAR, selectedYear) }
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    }
    val isCurrentMonth = selectedMonth == now.get(Calendar.MONTH) && selectedYear == now.get(Calendar.YEAR)

    val allCategories = remember {
        Categories.list.flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }
    }

    if (showAddDialog || editingBudget != null) {
        BudgetDialog(
            budget = editingBudget,
            allCategories = allCategories,
            onDismiss = { showAddDialog = false; editingBudget = null },
            onConfirm = { budget ->
                if (editingBudget != null) viewModel.updateBudget(budget)
                else viewModel.addBudget(budget)
                showAddDialog = false
                editingBudget = null
            }
        )
    }

    showDeleteDialog?.let { budget ->
        DeleteConfirmationDialog(
            onDismiss = { showDeleteDialog = null },
            onConfirm = { viewModel.deleteBudget(budget.id); showDeleteDialog = null },
            title = "Delete Budget",
            text = "Remove budget for \"${budget.category}\"?"
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budgets", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Budget")
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Month navigator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    val cal = Calendar.getInstance().apply {
                        set(Calendar.MONTH, selectedMonth)
                        set(Calendar.YEAR, selectedYear)
                        add(Calendar.MONTH, -1)
                    }
                    selectedMonth = cal.get(Calendar.MONTH)
                    selectedYear = cal.get(Calendar.YEAR)
                }) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month")
                }
                Text(
                    text = monthLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.MONTH, selectedMonth)
                            set(Calendar.YEAR, selectedYear)
                            add(Calendar.MONTH, 1)
                        }
                        selectedMonth = cal.get(Calendar.MONTH)
                        selectedYear = cal.get(Calendar.YEAR)
                    },
                    enabled = !isCurrentMonth
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Next month",
                        tint = if (isCurrentMonth) MaterialTheme.colorScheme.outline
                               else LocalContentColor.current)
                }
            }

            if (budgets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No budgets set", style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Tap + to create a budget", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(budgets, key = { it.id }) { budget ->
                        val spent = remember(records, budget.category, selectedMonth, selectedYear) {
                            BudgetCalculator.spentInMonth(records, budget.category, selectedMonth, selectedYear, categorySubcategoryMap)
                        }
                        BudgetCard(
                            budget = budget,
                            spent = spent,
                            onEdit = { editingBudget = budget },
                            onDelete = { showDeleteDialog = budget }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BudgetCard(
    budget: Budget,
    spent: Double,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val progress = if (budget.monthlyLimit > 0) (spent / budget.monthlyLimit).toFloat().coerceIn(0f, 1f) else 0f
    val remaining = budget.monthlyLimit - spent
    val isOverBudget = spent > budget.monthlyLimit

    val category = Categories.list.flatMap { it.subCategories + it }.find { it.name == budget.category }
    val catColor = category?.color ?: MaterialTheme.colorScheme.primary

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = category?.icon ?: Icons.Default.Category,
                        contentDescription = null,
                        tint = catColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(budget.category, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge)
                }
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (isOverBudget) MaterialTheme.colorScheme.error else catColor,
                trackColor = catColor.copy(alpha = 0.15f)
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Spent: ${"%.2f".format(spent)} ${budget.currency}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOverBudget) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = if (isOverBudget) "Over by ${"%.2f".format(-remaining)} ${budget.currency}"
                           else "Left: ${"%.2f".format(remaining)} ${budget.currency}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isOverBudget) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                )
            }
            Text(
                text = "Limit: ${"%.2f".format(budget.monthlyLimit)} ${budget.currency}/mo",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BudgetDialog(
    budget: Budget?,
    allCategories: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (Budget) -> Unit
) {
    var selectedCategory by remember { mutableStateOf(budget?.category ?: "") }
    var limit by remember { mutableStateOf(budget?.monthlyLimit?.toString() ?: "") }
    var currency by remember { mutableStateOf(budget?.currency ?: "EGP") }
    var catExpanded by remember { mutableStateOf(false) }
    var curExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (budget == null) "Add Budget" else "Edit Budget", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = !catExpanded }) {
                    OutlinedTextField(
                        value = selectedCategory.ifEmpty { "Select Category" },
                        onValueChange = {},
                        label = { Text("Category") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }) {
                        allCategories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat) },
                                onClick = { selectedCategory = cat; catExpanded = false }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = limit,
                    onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) limit = it },
                    label = { Text("Monthly Limit") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenuBox(expanded = curExpanded, onExpandedChange = { curExpanded = !curExpanded }) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = {},
                        label = { Text("Currency") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = curExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = curExpanded, onDismissRequest = { curExpanded = false }) {
                        listOf("EGP", "Dollar", "Euro").forEach { cur ->
                            DropdownMenuItem(text = { Text(cur) }, onClick = { currency = cur; curExpanded = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        (budget ?: Budget()).copy(
                            category = selectedCategory,
                            monthlyLimit = limit.toDoubleOrNull() ?: 0.0,
                            currency = currency
                        )
                    )
                },
                enabled = selectedCategory.isNotBlank() && limit.isNotBlank(),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Save") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) { Text("Cancel") }
        }
    )
}
