package com.example.wallettrackers.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.components.NumberPad
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecordScreen(
    accounts: List<Account>,
    onAddRecord: (Record) -> Unit,
    onCancel: () -> Unit,
    onCategoryClick: () -> Unit,
    selectedCategory: String?,
    selectedAccount: Account?,
    onAccountChange: (Account) -> Unit,
    amount: String,
    onAmountChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var comment by remember { mutableStateOf("") }
    val category = selectedCategory ?: ""

    val displayedAccounts = remember(category, accounts) {
        if (category == "Credit") accounts.filter { it.accountType.contains("Credit", ignoreCase = true) }
        else accounts
    }

    val categoryInfo = remember(category) {
        Categories.list.flatMap { it.subCategories + it }.find { it.name == category }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Record", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Amount display area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(vertical = 24.dp, horizontal = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = selectedAccount?.currency ?: "EGP",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                    )
                    Text(
                        text = if (amount.isEmpty()) "0" else amount,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Form section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Category selector
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onCategoryClick() },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = if (category.isEmpty()) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)) else null,
                    tonalElevation = 1.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (categoryInfo != null) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(categoryInfo.color.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = categoryInfo.icon,
                                    contentDescription = null,
                                    tint = categoryInfo.color,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Category,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = if (category.isNotBlank()) category else "Select Category",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (category.isNotBlank()) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Account dropdown
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedAccount?.name ?: "",
                        onValueChange = {},
                        label = { Text(if (category == "Credit") "Pay To (Credit Card)" else "Account") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        if (displayedAccounts.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(if (category == "Credit") "No credit card accounts found" else "No accounts found") },
                                onClick = { expanded = false },
                                enabled = false
                            )
                        } else {
                            displayedAccounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name) },
                                    onClick = { onAccountChange(account); expanded = false }
                                )
                            }
                        }
                    }
                }

                // Comment field
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Note (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                )
            }

            Spacer(Modifier.weight(1f))

            // Number Pad
            NumberPad(
                onNumberClick = { if (amount.length < 10) onAmountChange(amount + it) },
                onBackspace = { if (amount.isNotEmpty()) onAmountChange(amount.dropLast(1)) }
            )

            Spacer(Modifier.height(12.dp))

            // Done button
            Button(
                onClick = {
                    selectedAccount?.let {
                        val record = Record(
                            accountId = it.id,
                            accountName = it.name,
                            category = category,
                            amount = amount,
                            currency = it.currency,
                            comment = comment
                        )
                        onAddRecord(record)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .height(54.dp),
                enabled = selectedAccount != null && category.isNotBlank() && amount.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text("Confirm", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}
