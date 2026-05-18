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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Bill
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.util.*

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BillScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val suggestions by viewModel.suggestedBills
    val bills by viewModel.bills
    val context = LocalContext.current
    var showDialog by remember { mutableStateOf(false) }
    var suggestionsExpanded by remember { mutableStateOf(true) }
    var billToEdit by remember { mutableStateOf<Bill?>(null) }
    var billToDelete by remember { mutableStateOf<Bill?>(null) }

    LaunchedEffect(viewModel.records.value.size) {
        viewModel.detectRecurringBills(context)
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

    if (billToEdit != null) {
        BillDialog(
            bill = billToEdit,
            onDismiss = { billToEdit = null },
            onConfirm = { b ->
                viewModel.updateBill(b, context)
                billToEdit = null
            }
        )
    }

    if (billToDelete != null) {
        AlertDialog(
            onDismissRequest = { billToDelete = null },
            containerColor = AppSurface,
            title = { Text("Remove Bill", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
            text = { Text("Remove \"${billToDelete!!.name}\" from your recurring bills?", color = AppTextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteBill(billToDelete!!.id, context); billToDelete = null }) {
                    Text("Remove", color = AppRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { billToDelete = null }) { Text("Cancel", color = AppTextSecondary) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Monthly Bills", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.addLastMonthSubscriptionsAsBills(context) }) {
                        Icon(Icons.Default.PlaylistAdd, contentDescription = "Import last month's subscriptions", tint = AppVioletLight)
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
                Icon(Icons.Default.Add, contentDescription = "Add Bill")
            }
        },
        containerColor = AppBackground
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            if (suggestions.isNotEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = AppSurface),
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppPrimary.copy(alpha = 0.15f))
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(AppViolet.copy(alpha = 0.15f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Default.AutoAwesome,
                                            null,
                                            modifier = Modifier.size(20.dp),
                                            tint = AppVioletLight
                                        )
                                    }
                                    Column {
                                        Text(
                                            "Recurring Bills Found",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = AppTextPrimary
                                        )
                                        Text(
                                            "${suggestions.size} suggestion${if (suggestions.size > 1) "s" else ""} from history",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = AppTextSecondary
                                        )
                                    }
                                }
                                IconButton(onClick = { suggestionsExpanded = !suggestionsExpanded }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        if (suggestionsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        null,
                                        tint = AppTextSecondary
                                    )
                                }
                            }

                            AnimatedVisibility(visible = suggestionsExpanded) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    suggestions.forEach { suggestion ->
                                        SuggestionCard(
                                            suggestion = suggestion,
                                            onAdd = { viewModel.confirmBillSuggestion(suggestion, context) },
                                            onDismiss = { viewModel.dismissBillSuggestion(suggestion, context) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (bills.isEmpty()) {
                item {
                    com.example.wallettrackers.components.EmptyState(
                        icon = Icons.Default.ReceiptLong,
                        title = "No recurring bills yet",
                        subtitle = "Tap + to add a recurring monthly payment"
                    )
                }
            }

            if (bills.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.padding(top = if (suggestions.isEmpty()) 0.dp else 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(AppPrimary)
                        )
                        Text(
                            "My Bills",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppTextPrimary
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "${bills.size} active",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextSecondary
                        )
                    }
                }
                items(bills) { bill ->
                    BillItemCard(
                        bill = bill,
                        onEdit = { billToEdit = bill },
                        onDelete = { billToDelete = bill }
                    )
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
    val accent = if (suggestion.detectionType == "Subscription") AppAmber else AppVioletLight
    val success = AppGreen
    val chipBg = AppPrimary.copy(alpha = 0.2f)

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppBackground.copy(alpha = 0.4f)),
        border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(catIcon, null, Modifier.size(20.dp), tint = accent)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    suggestion.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BillChip("Day ${suggestion.dayOfMonth}", chipBg, AppTextSecondary)
                    BillChip("${String.format(Locale.getDefault(), "%.0f", suggestion.amount)} ${suggestion.currency}", chipBg, accent)
                    BillChip(suggestion.detectionType, accent.copy(alpha = 0.15f), accent)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = onAdd,
                    modifier = Modifier.height(32.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = success),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Add", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppBackground)
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.height(24.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall, color = AppRed.copy(alpha = 0.8f), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun BillItemCard(bill: Bill, onEdit: () -> Unit, onDelete: () -> Unit) {
    val catIcon = Categories.list.flatMap { it.subCategories + it }
        .find { it.name == bill.category }?.icon ?: Icons.Default.Receipt
    val accent = AppPrimaryLight

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(catIcon, null, Modifier.size(20.dp), tint = accent)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    bill.name,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextPrimary
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BillChip(
                        "Day ${bill.dayOfMonth}",
                        AppPrimary.copy(alpha = 0.2f),
                        AppTextSecondary
                    )
                    BillChip(
                        "${String.format(Locale.getDefault(), "%.0f", bill.amount)} ${bill.currency}",
                        AppPrimary.copy(alpha = 0.2f),
                        accent
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = AppPrimaryLight,
                        modifier = Modifier.size(18.dp)
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = AppRed.copy(alpha = 0.7f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BillChip(text: String, bgColor: Color, textColor: Color) {
    Surface(shape = RoundedCornerShape(6.dp), color = bgColor) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            fontWeight = FontWeight.Bold
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
        containerColor = AppSurface,
        title = { Text(if (bill == null) "New Recurring Bill" else "Edit Bill", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name, 
                    onValueChange = { name = it }, 
                    label = { Text("Bill Name") }, 
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
                    label = { Text("Monthly Amount") }, 
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
                    value = dayOfMonth, 
                    onValueChange = { val n = it.toIntOrNull(); if (it.isEmpty() || (n != null && n in 1..31)) dayOfMonth = it }, 
                    label = { Text("Due Day (1-31)") }, 
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), 
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
                ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = !catExpanded }) {
                    OutlinedTextField(
                        value = category, 
                        onValueChange = {}, 
                        label = { Text("Category") }, 
                        readOnly = true, 
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(catExpanded) }, 
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
                    ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false }, modifier = Modifier.background(AppSurface)) {
                        allCategories.forEach { c -> DropdownMenuItem(text = { Text(c, color = AppTextPrimary) }, onClick = { category = c; catExpanded = false }) }
                    }
                }
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
                    ExposedDropdownMenu(expanded = curExpanded, onDismissRequest = { curExpanded = false }, modifier = Modifier.background(AppSurface)) {
                        listOf("EGP", "USD", "EUR").forEach { c -> DropdownMenuItem(text = { Text(c, color = AppTextPrimary) }, onClick = { currency = c; curExpanded = false }) }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm((bill ?: Bill()).copy(name = name, amount = amount.toDoubleOrNull() ?: 0.0, dayOfMonth = dayOfMonth.toIntOrNull() ?: 1, category = category, currency = currency)) }, 
                enabled = name.isNotBlank() && amount.isNotBlank(), 
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
            ) { Text("Save Bill", fontWeight = FontWeight.Bold) }
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
