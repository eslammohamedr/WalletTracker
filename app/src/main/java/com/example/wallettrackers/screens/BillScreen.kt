package com.example.wallettrackers.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.Color
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
    val suggestions by viewModel.suggestedBills
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(viewModel.records.value.size) {
        viewModel.detectRecurringBills()
    }

    if (showDialog) {
        BillDialog(
            bill = null,
            onDismiss = { showDialog = false },
            onConfirm = { b ->
                viewModel.addBill(b, context)
                showDialog = false
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monthly Bills", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null) } },
                actions = {
                    IconButton(onClick = { viewModel.addLastMonthSubscriptionsAsBills(context) }) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "Import last month's subscriptions", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { showDialog = true }) { Icon(Icons.Default.Add, null) } },
        containerColor = MaterialTheme.colorScheme.background
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            if (suggestions.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                                ) {
                                    Icon(
                                        Icons.Default.AutoAwesome,
                                        null,
                                        Modifier.padding(8.dp).size(20.dp),
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Column {
                                    Text(
                                        "Payments & Subscriptions Detected",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Text(
                                        "${suggestions.size} possible bill${if (suggestions.size > 1) "s" else ""} found in history",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                                    )
                                }
                            }
                            IconButton(onClick = { suggestionsExpanded = !suggestionsExpanded }, Modifier.size(32.dp)) {
                                Icon(
                                    if (suggestionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }

                        AnimatedVisibility(visible = suggestionsExpanded) {
                            Column(
                                Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                suggestions.forEach { suggestion ->
                                    SuggestionCard(
                                        suggestion = suggestion,
                                        onAdd = { viewModel.confirmBillSuggestion(suggestion, context) },
                                        onDismiss = { viewModel.dismissBillSuggestion(suggestion) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.24f))
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Icon(
                                    Icons.Default.ReceiptLong,
                                    null,
                                    Modifier.padding(14.dp).size(34.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No detections found",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                "Detected payments and subscriptions will appear here",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionCard(
    suggestion: HomeViewModel.RecurringBillSuggestion,
    onAdd: () -> Unit,
    onDismiss: () -> Unit
) {
    val catIcon = Categories.list.flatMap { it.subCategories + it }
        .find { it.name == suggestion.category }?.icon ?: Icons.Default.Receipt
    val accent = if (suggestion.detectionType == "Subscription") {
        Color(0xFFF59E0B)
    } else {
        MaterialTheme.colorScheme.primary
    }
    val success = MaterialTheme.colorScheme.tertiary
    val chipBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(42.dp)
                    .background(accent.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(catIcon, null, Modifier.size(22.dp), tint = accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    suggestion.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    BillChip("Day ${suggestion.dayOfMonth}", chipBg, MaterialTheme.colorScheme.onSurfaceVariant)
                    BillChip("${"%.0f".format(suggestion.amount)} ${suggestion.currency}", chipBg, accent)
                    BillChip(suggestion.detectionType, accent.copy(alpha = 0.16f), accent)
                    BillChip("${suggestion.monthsDetected}mo", success.copy(alpha = 0.16f), success)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = success,
                        contentColor = MaterialTheme.colorScheme.onTertiary
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                ) {
                    Text("Skip", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun BillChip(text: String, bgColor: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(4.dp), color = bgColor) {
        Text(
            text,
            Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Medium
        )
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
