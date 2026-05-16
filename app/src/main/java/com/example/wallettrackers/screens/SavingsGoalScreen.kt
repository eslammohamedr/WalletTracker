package com.example.wallettrackers.screens

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.model.SavingsGoal
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.text.SimpleDateFormat
import java.util.*

import com.example.wallettrackers.ui.theme.*

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
                title = { Text("Savings Goals", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
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
                Icon(Icons.Default.Add, contentDescription = "Add Goal")
            }
        },
        containerColor = AppBackground
    ) { pad ->
        if (goals.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Savings,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = AppPrimary.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No savings goals yet", style = MaterialTheme.typography.bodyLarge, color = AppTextPrimary)
                    Text("Tap + to create a goal", style = MaterialTheme.typography.bodySmall, color = AppTextSecondary)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(goal.emoji, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(goal.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge, color = AppTextPrimary)
                        if (daysLeft != null) {
                            Text(
                                text = if (daysLeft > 0) "$daysLeft days left" else if (daysLeft == 0) "Due today" else "Overdue",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (daysLeft <= 0) AppRed else AppAmber.copy(alpha = 0.8f),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
                Row {
                    IconButton(onClick = onEdit, Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, null, Modifier.size(18.dp), tint = AppTextSecondary)
                    }
                    IconButton(onClick = onDelete, Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, null, Modifier.size(18.dp), tint = AppRed.copy(alpha = 0.8f))
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(AppPrimary.copy(alpha = 0.2f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (isComplete) AppGreen else AppVioletLight)
                )
            }
            
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Saved", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                    Text(
                        text = "${String.format(Locale.getDefault(), "%,.2f", goal.savedAmount)} ${goal.currency}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isComplete) AppGreen else AppTextPrimary
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Target", style = MaterialTheme.typography.labelSmall, color = AppTextSecondary)
                    Text(
                        text = "${String.format(Locale.getDefault(), "%,.2f", goal.targetAmount)} ${goal.currency}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                }
            }
            
            if (!isComplete) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAddFunds,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppPrimary.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(vertical = 10.dp)
                ) {
                    Icon(Icons.Default.AddCircle, null, Modifier.size(18.dp), tint = AppVioletLight)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Add ${String.format(Locale.getDefault(), "%,.2f", remaining)} more",
                        color = AppVioletLight,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            } else {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = AppGreen.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, null, tint = AppGreen, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Goal Achieved!",
                            style = MaterialTheme.typography.labelLarge,
                            color = AppGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
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
        containerColor = AppSurface,
        title = {
            Text(
                if (goal == null) "New Savings Goal" else "Edit Goal",
                fontWeight = FontWeight.Bold,
                color = AppTextPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // Emoji picker
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    emojis.take(5).forEach { e ->
                        Surface(
                            onClick = { emoji = e },
                            shape = RoundedCornerShape(10.dp),
                            color = if (emoji == e) AppPrimary.copy(alpha = 0.4f) else AppBackground.copy(alpha = 0.5f),
                            border = if (emoji == e) BorderStroke(1.dp, AppVioletLight) else null
                        ) {
                            Text(e, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    emojis.drop(5).forEach { e ->
                        Surface(
                            onClick = { emoji = e },
                            shape = RoundedCornerShape(10.dp),
                            color = if (emoji == e) AppPrimary.copy(alpha = 0.4f) else AppBackground.copy(alpha = 0.5f),
                            border = if (emoji == e) BorderStroke(1.dp, AppVioletLight) else null
                        ) {
                            Text(e, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Goal Name") },
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
                    value = target,
                    onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) target = it },
                    label = { Text("Target Amount") },
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
                    value = saved,
                    onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) saved = it },
                    label = { Text("Already Saved") },
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
                    onConfirm((goal ?: SavingsGoal()).copy(
                        name = name,
                        targetAmount = target.toDoubleOrNull() ?: 0.0,
                        savedAmount = saved.toDoubleOrNull() ?: 0.0,
                        currency = currency,
                        emoji = emoji
                    ))
                },
                enabled = name.isNotBlank() && target.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
            ) { Text("Save Goal", fontWeight = FontWeight.Bold) }
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

@Composable
private fun AddFundsDialog(goal: SavingsGoal, onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var amount by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppSurface,
        title = { Text("Add Funds to ${goal.name}", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
        text = {
            OutlinedTextField(
                value = amount,
                onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                label = { Text("Amount to add") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                suffix = { Text(goal.currency, color = AppTextSecondary) },
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
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(amount.toDoubleOrNull() ?: 0.0) },
                enabled = amount.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
            ) { Text("Add", fontWeight = FontWeight.Bold) }
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
