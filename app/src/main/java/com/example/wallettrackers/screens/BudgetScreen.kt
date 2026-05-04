package com.example.wallettrackers.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.ui.theme.*
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
                title = { Text("Budgets", fontWeight = FontWeight.Bold, color = DGTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = DGTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DGBackground)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = DGIndigo,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Budget")
            }
        },
        containerColor = DGBackground
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            // Month navigator
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
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
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Previous month", tint = DGVioletLight)
                }
                
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                    Text(
                        text = monthLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = DGTextPrimary
                    )
                }

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
                        tint = if (isCurrentMonth) DGTextMuted else DGVioletLight)
                }
            }

            if (budgets.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = DGIndigo.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("No budgets set", style = MaterialTheme.typography.bodyLarge,
                            color = DGTextPrimary)
                        Text("Tap + to create a budget", style = MaterialTheme.typography.bodySmall,
                            color = DGTextSecondary)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
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
internal fun BudgetCard(
    budget: Budget,
    spent: Double,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val progress = if (budget.monthlyLimit > 0) (spent / budget.monthlyLimit).toFloat().coerceIn(0f, 1f) else 0f
    val remaining = budget.monthlyLimit - spent
    val isOverBudget = spent > budget.monthlyLimit

    val category = Categories.list.flatMap { it.subCategories + it }.find { it.name == budget.category }
    val catColor = category?.color ?: DGVioletLight

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = DGSurface),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(catColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = category?.icon ?: Icons.Default.Category,
                            contentDescription = null,
                            tint = catColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(budget.category, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge, color = DGTextPrimary)
                }
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit",
                            modifier = Modifier.size(18.dp),
                            tint = DGTextSecondary)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete",
                            modifier = Modifier.size(18.dp),
                            tint = DGRed)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(DGIndigo.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isOverBudget) DGRed else catColor)
                )
            }
            
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Spent",
                        style = MaterialTheme.typography.labelSmall,
                        color = DGTextSecondary
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%,.2f %s", spent, budget.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isOverBudget) DGRed else DGTextPrimary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (isOverBudget) "Over by" else "Remaining",
                        style = MaterialTheme.typography.labelSmall,
                        color = DGTextSecondary
                    )
                    Text(
                        text = String.format(Locale.getDefault(), "%,.2f %s", if (isOverBudget) -remaining else remaining, budget.currency),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isOverBudget) DGRed else DGGreen
                    )
                }
            }
            
            HorizontalDivider(Modifier.padding(vertical = 12.dp), thickness = 0.5.dp, color = DGIndigo.copy(alpha = 0.2f))
            
            Text(
                text = "Limit: ${String.format(Locale.getDefault(), "%,.2f %s", budget.monthlyLimit, budget.currency)} / mo",
                style = MaterialTheme.typography.labelSmall,
                color = DGTextSecondary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BudgetDialog(
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
        containerColor = DGSurface,
        title = { Text(if (budget == null) "Add Budget" else "Edit Budget", fontWeight = FontWeight.Bold, color = DGTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = !catExpanded }) {
                    OutlinedTextField(
                        value = selectedCategory.ifEmpty { "Select Category" },
                        onValueChange = {},
                        label = { Text("Category") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DGTextPrimary,
                            unfocusedTextColor = DGTextPrimary,
                            focusedContainerColor = DGBackground.copy(alpha = 0.5f),
                            unfocusedContainerColor = DGBackground.copy(alpha = 0.5f),
                            focusedBorderColor = DGVioletLight,
                            unfocusedBorderColor = DGIndigo.copy(alpha = 0.3f),
                            focusedLabelColor = DGVioletLight,
                            unfocusedLabelColor = DGTextSecondary
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = catExpanded, 
                        onDismissRequest = { catExpanded = false },
                        modifier = Modifier.background(DGSurface)
                    ) {
                        allCategories.forEach { cat ->
                            DropdownMenuItem(
                                text = { Text(cat, color = DGTextPrimary) },
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
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = DGTextPrimary,
                        unfocusedTextColor = DGTextPrimary,
                        focusedContainerColor = DGBackground.copy(alpha = 0.5f),
                        unfocusedContainerColor = DGBackground.copy(alpha = 0.5f),
                        focusedBorderColor = DGVioletLight,
                        unfocusedBorderColor = DGIndigo.copy(alpha = 0.3f),
                        focusedLabelColor = DGVioletLight,
                        unfocusedLabelColor = DGTextSecondary
                    )
                )
                ExposedDropdownMenuBox(expanded = curExpanded, onExpandedChange = { curExpanded = !curExpanded }) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = {},
                        label = { Text("Currency") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = curExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DGTextPrimary,
                            unfocusedTextColor = DGTextPrimary,
                            focusedContainerColor = DGBackground.copy(alpha = 0.5f),
                            unfocusedContainerColor = DGBackground.copy(alpha = 0.5f),
                            focusedBorderColor = DGVioletLight,
                            unfocusedBorderColor = DGIndigo.copy(alpha = 0.3f),
                            focusedLabelColor = DGVioletLight,
                            unfocusedLabelColor = DGTextSecondary
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = curExpanded, 
                        onDismissRequest = { curExpanded = false },
                        modifier = Modifier.background(DGSurface)
                    ) {
                        listOf("EGP", "USD", "EUR").forEach { cur ->
                            DropdownMenuItem(
                                text = { Text(cur, color = DGTextPrimary) },
                                onClick = { currency = cur; curExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    onConfirm((budget ?: Budget()).copy(
                        category = selectedCategory,
                        monthlyLimit = limit.toDoubleOrNull() ?: 0.0,
                        currency = currency
                    ))
                },
                enabled = selectedCategory.isNotBlank() && limit.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DGIndigo)
            ) {
                Text(if (budget == null) "Create" else "Update", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = DGTextSecondary)
            }
        }
    )
}
