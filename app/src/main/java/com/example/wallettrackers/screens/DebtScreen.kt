package com.example.wallettrackers.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

import com.example.wallettrackers.ui.theme.*

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
                title = { Text("Debt Tracker", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showDialog = true },
                containerColor = AppPrimary,
                contentColor = Color.White,
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Debt")
            }
        },
        containerColor = AppBackground
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Summary cards
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DebtSummaryCard("Owed to Me", totalOwedToMe, "EGP", AppGreen, Modifier.weight(1f))
                    DebtSummaryCard("I Owe", totalIOwe, "EGP", AppRed, Modifier.weight(1f))
                }
            }

            if (activeDebts.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)) {
                        Box(modifier = Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                        Text("Active", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AppVioletLight)
                    }
                }
                items(activeDebts, key = { it.id }) { debt ->
                    DebtCard(debt = debt, onEdit = { editingDebt = debt }, onDelete = { deleteDebt = debt }, onSettle = { viewModel.updateDebt(debt.copy(isSettled = true)) })
                }
            }

            if (settledDebts.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                        Box(modifier = Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(AppTextMuted))
                        Text("Settled", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = AppTextSecondary)
                    }
                }
                items(settledDebts, key = { it.id }) { debt ->
                    DebtCard(debt = debt, onEdit = { editingDebt = debt }, onDelete = { deleteDebt = debt }, onSettle = null)
                }
            }

            if (debts.isEmpty()) {
                item {
                    Box(Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = AppPrimary.copy(alpha = 0.3f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("No debts recorded", style = MaterialTheme.typography.bodyLarge, color = AppTextPrimary)
                            Text("Tap + to add your first debt", style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DebtSummaryCard(label: String, amount: Double, currency: String, color: Color, modifier: Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${String.format(Locale.getDefault(), "%,.2f", amount)} $currency",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = color,
                letterSpacing = (-0.3).sp
            )
        }
    }
}

@Composable
private fun DebtCard(debt: Debt, onEdit: () -> Unit, onDelete: () -> Unit, onSettle: (() -> Unit)?) {
    val fmt = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    val accentColor = if (debt.isOwedToMe) AppGreen else AppRed
    
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = debt.personName.firstOrNull()?.uppercase() ?: "?",
                    fontWeight = FontWeight.Black,
                    color = accentColor,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            Spacer(Modifier.width(16.dp))
            
            Column(Modifier.weight(1f)) {
                Text(
                    text = debt.personName,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyLarge,
                    color = AppTextPrimary
                )
                if (debt.description.isNotEmpty()) {
                    Text(
                        text = debt.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                debt.dueDate?.let {
                    Text(
                        text = "Due: ${fmt.format(it)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppAmber.copy(alpha = 0.8f),
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (debt.isOwedToMe) "+" else "-"}${String.format(Locale.getDefault(), "%,.2f", debt.amount)} ${debt.currency}",
                    fontWeight = FontWeight.ExtraBold,
                    color = accentColor,
                    style = MaterialTheme.typography.bodyLarge,
                    letterSpacing = (-0.5).sp
                )
                
                if (debt.isSettled) {
                    Surface(
                        color = AppTextMuted.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(
                            text = "Settled",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextSecondary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Row(modifier = Modifier.padding(top = 4.dp)) {
                        onSettle?.let { settle ->
                            TextButton(
                                onClick = settle,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                modifier = Modifier.height(30.dp)
                            ) {
                                Text("Settle", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AppVioletLight)
                            }
                        }
                        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(16.dp), tint = AppTextSecondary)
                        }
                        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp), tint = AppRed.copy(alpha = 0.8f))
                        }
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
        containerColor = AppSurface,
        title = {
            Text(
                if (debt == null) "Add New Debt" else "Edit Debt",
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilterChip(
                        selected = isOwedToMe,
                        onClick = { isOwedToMe = true },
                        label = { Text("They Owe Me") },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppGreen.copy(alpha = 0.2f),
                            selectedLabelColor = AppGreen
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = isOwedToMe, borderColor = if (isOwedToMe) AppGreen else AppPrimary.copy(alpha = 0.3f))
                    )
                    FilterChip(
                        selected = !isOwedToMe,
                        onClick = { isOwedToMe = false },
                        label = { Text("I Owe Them") },
                        modifier = Modifier.weight(1f),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppRed.copy(alpha = 0.2f),
                            selectedLabelColor = AppRed
                        ),
                        border = FilterChipDefaults.filterChipBorder(enabled = true, selected = !isOwedToMe, borderColor = if (!isOwedToMe) AppRed else AppPrimary.copy(alpha = 0.3f))
                    )
                }
                
                OutlinedTextField(
                    value = personName,
                    onValueChange = { personName = it },
                    label = { Text("Person Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppTextPrimary,
                        unfocusedTextColor = AppTextPrimary,
                        focusedContainerColor = AppBackground.copy(alpha = 0.5f),
                        unfocusedContainerColor = AppBackground.copy(alpha = 0.5f),
                        focusedBorderColor = AppVioletLight,
                        unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                        focusedLabelColor = AppVioletLight,
                        unfocusedLabelColor = AppTextSecondary
                    )
                )
                
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppTextPrimary,
                        unfocusedTextColor = AppTextPrimary,
                        focusedContainerColor = AppBackground.copy(alpha = 0.5f),
                        unfocusedContainerColor = AppBackground.copy(alpha = 0.5f),
                        focusedBorderColor = AppVioletLight,
                        unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                        focusedLabelColor = AppVioletLight,
                        unfocusedLabelColor = AppTextSecondary
                    )
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Note (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppTextPrimary,
                        unfocusedTextColor = AppTextPrimary,
                        focusedContainerColor = AppBackground.copy(alpha = 0.5f),
                        unfocusedContainerColor = AppBackground.copy(alpha = 0.5f),
                        focusedBorderColor = AppVioletLight,
                        unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                        focusedLabelColor = AppVioletLight,
                        unfocusedLabelColor = AppTextSecondary
                    )
                )
                
                ExposedDropdownMenuBox(expanded = curExpanded, onExpandedChange = { curExpanded = !curExpanded }) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = {},
                        label = { Text("Currency") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(curExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedContainerColor = AppBackground.copy(alpha = 0.5f),
                            unfocusedContainerColor = AppBackground.copy(alpha = 0.5f),
                            focusedBorderColor = AppVioletLight,
                            unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                            focusedLabelColor = AppVioletLight,
                            unfocusedLabelColor = AppTextSecondary
                        )
                    )
                    ExposedDropdownMenu(
                        expanded = curExpanded, 
                        onDismissRequest = { curExpanded = false },
                        modifier = Modifier.background(AppSurface)
                    ) {
                        listOf("EGP", "USD", "EUR").forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c, color = AppTextPrimary) }, 
                                onClick = { currency = c; curExpanded = false }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm((debt ?: Debt()).copy(
                        personName = personName,
                        amount = amount.toDoubleOrNull() ?: 0.0,
                        description = description,
                        isOwedToMe = isOwedToMe,
                        currency = currency
                    ))
                },
                enabled = personName.isNotBlank() && amount.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
            ) { Text("Save", fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss, 
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.5f))
            ) { Text("Cancel", color = AppTextPrimary) }
        }
    )
}
