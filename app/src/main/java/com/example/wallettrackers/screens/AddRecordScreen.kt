package com.example.wallettrackers.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.components.NumberPad
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

import com.example.wallettrackers.ui.theme.*

private data class SplitItem(val category: String, val amount: String)

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
    onAmountChange: (String) -> Unit,
    payFromAccount: Account? = null,
    onPayFromAccountChange: (Account) -> Unit = {},
    onAddSplitRecords: ((List<Record>) -> Unit)? = null
) {
    var comment by remember { mutableStateOf("") }
    var recordType by remember { mutableStateOf("Expense") }
    var splitMode by remember { mutableStateOf(false) }
    var splitItems by remember { mutableStateOf(listOf(SplitItem("", ""), SplitItem("", ""))) }
    val splitTotal = remember(splitItems) { splitItems.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
    val category = selectedCategory ?: ""
    val scrollState = rememberScrollState()

    val displayedAccounts = remember(category, accounts) {
        if (category == "Credit") accounts.filter { it.accountType.contains("Credit", ignoreCase = true) }
        else accounts
    }

    val nonCreditAccounts = remember(accounts) {
        accounts.filter { !it.accountType.contains("Credit", ignoreCase = true) }
    }

    val categoryInfo = remember(category) {
        Categories.list.flatMap { it.subCategories + it }.find { it.name == category }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Record", fontWeight = FontWeight.Bold, color = DGTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = DGTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = DGBackground
                )
            )
        },
        containerColor = DGBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Amount display area — Dark Hero gradient
                // Amount Hero with animated scale pulse on each digit press
                val amountScale by animateFloatAsState(
                    targetValue = 1f,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "amount_scale"
                )

                // Expense / Income type toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Expense", "Income").forEach { type ->
                        val isSelected = recordType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    if (isSelected) AccentGradient
                                    else Brush.linearGradient(listOf(Color(0x1A7C3AED), Color(0x1A4F46E5)))
                                )
                                .clickable { recordType = type }
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = type,
                                fontSize = 13.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) Color.White else DGTextSecondary
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HeroGradient)
                        .padding(vertical = 20.dp, horizontal = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(
                            targetState = selectedAccount?.currency ?: "EGP",
                            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                            label = "currency_anim"
                        ) { currency ->
                            Text(
                                text = currency,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color(0xB3C4B5FD),
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // Animated amount display — scales up slightly on change
                        AnimatedContent(
                            targetState = if (splitMode) "%.2f".format(splitTotal)
                                          else if (amount.isEmpty()) "0" else amount,
                            transitionSpec = {
                                (fadeIn(tween(120)) + androidx.compose.animation.scaleIn(
                                    tween(120), initialScale = 0.92f
                                )) togetherWith fadeOut(tween(80))
                            },
                            label = "amount_anim"
                        ) { displayAmount ->
                            Text(
                                text = displayAmount,
                                fontSize = if (splitMode) 42.sp else 64.sp,
                                fontWeight = FontWeight.Black,
                                textAlign = TextAlign.Center,
                                color = if (recordType == "Income") DGGreen else DGRed,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .graphicsLayer { scaleX = amountScale; scaleY = amountScale },
                                letterSpacing = (-1.5).sp
                            )
                        }
                        if (splitMode) {
                            Text("Split across ${splitItems.count { it.category.isNotBlank() && it.amount.isNotBlank() }} categories",
                                style = MaterialTheme.typography.labelSmall, color = Color(0xB3C4B5FD))
                        }
                        if (selectedAccount != null) {
                            Spacer(Modifier.height(8.dp))
                            AnimatedContent(
                                targetState = selectedAccount.name,
                                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                                label = "account_name_anim"
                            ) { name ->
                                Surface(
                                    color = Color.White.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // Form section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Category selector
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onCategoryClick() },
                        shape = RoundedCornerShape(16.dp),
                        color = DGSurface,
                        border = if (category.isEmpty()) BorderStroke(1.dp, DGIndigo.copy(alpha = 0.3f)) else null,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (categoryInfo != null) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(categoryInfo.color.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = categoryInfo.icon,
                                        contentDescription = null,
                                        tint = categoryInfo.color,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(DGIndigo.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Category,
                                        contentDescription = null,
                                        tint = DGVioletLight,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = if (category.isNotBlank()) category else "Select Category",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (category.isNotBlank()) DGTextPrimary else DGTextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = DGTextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Account chips
                    Column {
                        Text(
                            text = if (category == "Credit") "Pay To (Credit Card)" else "Account",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = DGTextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        if (displayedAccounts.isEmpty()) {
                            Text(
                                text = if (category == "Credit") "No credit card accounts found" else "No accounts found",
                                fontSize = 13.sp,
                                color = DGTextSecondary
                            )
                        } else {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                displayedAccounts.forEach { account ->
                                    val isSelected = selectedAccount?.id == account.id
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) AccentGradient
                                                else Brush.linearGradient(
                                                    listOf(Color(0x1A7C3AED), Color(0x1A4F46E5))
                                                )
                                            )
                                            .clickable { onAccountChange(account) }
                                            .padding(horizontal = 16.dp, vertical = 10.dp)
                                    ) {
                                        Text(
                                            text = account.name,
                                            fontSize = 13.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) Color.White else DGTextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Pay-from chips (only shown for Credit category)
                    if (category == "Credit") {
                        Column {
                            Text(
                                text = "Pay From (Debit / Cash)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DGTextSecondary,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            if (nonCreditAccounts.isEmpty()) {
                                Text(
                                    text = "No debit accounts found",
                                    fontSize = 13.sp,
                                    color = DGTextSecondary
                                )
                            } else {
                                Row(
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    nonCreditAccounts.forEach { acc ->
                                        val isSelected = payFromAccount?.id == acc.id
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (isSelected) AccentGradient
                                                    else Brush.linearGradient(
                                                        listOf(Color(0x1A7C3AED), Color(0x1A4F46E5))
                                                    )
                                                )
                                                .clickable { onPayFromAccountChange(acc) }
                                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = acc.name,
                                                fontSize = 13.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) Color.White else DGTextSecondary
                                            )
                                        }
                                    }
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
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DGTextPrimary,
                            unfocusedTextColor = DGTextPrimary,
                            focusedContainerColor = DGSurface,
                            unfocusedContainerColor = DGSurface,
                            focusedBorderColor = DGVioletLight,
                            unfocusedBorderColor = DGIndigo.copy(alpha = 0.3f),
                            cursorColor = DGVioletLight,
                            focusedLabelColor = DGVioletLight,
                            unfocusedLabelColor = DGTextSecondary
                        )
                    )

                    // Split toggle (only for Expense, not Credit category)
                    if (onAddSplitRecords != null && recordType == "Expense" && category != "Credit") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (splitMode) DGIndigo.copy(alpha = 0.15f) else DGSurface)
                                .clickable { splitMode = !splitMode }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.CallSplit, null, tint = if (splitMode) DGIndigoLight else DGTextSecondary, modifier = Modifier.size(18.dp))
                            Text("Split across categories", style = MaterialTheme.typography.bodyMedium,
                                color = if (splitMode) DGIndigoLight else DGTextSecondary, modifier = Modifier.weight(1f))
                            Switch(checked = splitMode, onCheckedChange = { splitMode = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = DGIndigo))
                        }
                    }

                    // Split rows
                    if (splitMode) {
                        val allCategories = remember { Categories.list.flatMap { c -> (c.subCategories + c).map { it.name } } }
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            splitItems.forEachIndexed { idx, item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    var expanded by remember { mutableStateOf(false) }
                                    ExposedDropdownMenuBox(
                                        expanded = expanded,
                                        onExpandedChange = { expanded = it },
                                        modifier = Modifier.weight(1.4f)
                                    ) {
                                        OutlinedTextField(
                                            value = item.category.ifEmpty { "Category" },
                                            onValueChange = {},
                                            readOnly = true,
                                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                                            singleLine = true,
                                            shape = RoundedCornerShape(12.dp),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = if (item.category.isEmpty()) DGTextSecondary else DGTextPrimary,
                                                unfocusedTextColor = if (item.category.isEmpty()) DGTextSecondary else DGTextPrimary,
                                                focusedContainerColor = DGSurface,
                                                unfocusedContainerColor = DGSurface,
                                                focusedBorderColor = DGVioletLight,
                                                unfocusedBorderColor = DGIndigo.copy(alpha = 0.3f),
                                            ),
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            modifier = Modifier.background(DGSurface)
                                        ) {
                                            allCategories.forEach { cat ->
                                                DropdownMenuItem(
                                                    text = { Text(cat, color = DGTextPrimary, style = MaterialTheme.typography.bodySmall) },
                                                    onClick = {
                                                        splitItems = splitItems.toMutableList().also { list -> list[idx] = item.copy(category = cat) }
                                                        expanded = false
                                                    }
                                                )
                                            }
                                        }
                                    }
                                    OutlinedTextField(
                                        value = item.amount,
                                        onValueChange = { v -> splitItems = splitItems.toMutableList().also { list -> list[idx] = item.copy(amount = v) } },
                                        label = { Text("Amount") },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = DGTextPrimary, unfocusedTextColor = DGTextPrimary,
                                            focusedContainerColor = DGSurface, unfocusedContainerColor = DGSurface,
                                            focusedBorderColor = DGVioletLight, unfocusedBorderColor = DGIndigo.copy(alpha = 0.3f),
                                            cursorColor = DGVioletLight, focusedLabelColor = DGVioletLight, unfocusedLabelColor = DGTextSecondary
                                        )
                                    )
                                    if (splitItems.size > 2) {
                                        IconButton(onClick = { splitItems = splitItems.toMutableList().also { it.removeAt(idx) } }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Remove, null, tint = DGRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = { splitItems = splitItems + SplitItem("", "") },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp), tint = DGIndigoLight)
                                Spacer(Modifier.width(4.dp))
                                Text("Add split", style = MaterialTheme.typography.labelMedium, color = DGIndigoLight)
                            }
                        }
                    }
                }
            }

            // Fixed section at bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DGBackground)
                    .padding(bottom = 24.dp, top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Number Pad — hidden in split mode
                if (!splitMode) {
                    NumberPad(
                        onNumberClick = { if (amount.length < 10) onAmountChange(amount + it) },
                        onBackspace = { if (amount.isNotEmpty()) onAmountChange(amount.dropLast(1)) }
                    )
                    Spacer(Modifier.height(20.dp))
                }

                // Confirm button — gradient
                val splitValid = splitMode && selectedAccount != null &&
                    splitItems.count { it.category.isNotBlank() && (it.amount.toDoubleOrNull() ?: 0.0) > 0 } >= 2
                val normalValid = !splitMode && selectedAccount != null && category.isNotBlank() && amount.isNotBlank() &&
                        (category != "Credit" || payFromAccount != null)
                val canSubmit = splitValid || normalValid
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .height(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (canSubmit)
                                AccentGradient
                            else
                                Brush.linearGradient(listOf(DGIndigo.copy(alpha = 0.2f), DGIndigo.copy(alpha = 0.2f)))
                        )
                ) {
                    Button(
                        onClick = {
                            if (splitMode && splitValid) {
                                val acc = selectedAccount!!
                                val splitRecords = splitItems
                                    .filter { it.category.isNotBlank() && (it.amount.toDoubleOrNull() ?: 0.0) > 0 }
                                    .map { split ->
                                        Record(
                                            accountId = acc.id,
                                            accountName = acc.name,
                                            category = split.category,
                                            amount = split.amount,
                                            currency = acc.currency,
                                            comment = comment,
                                            type = "Expense"
                                        )
                                    }
                                onAddSplitRecords?.invoke(splitRecords)
                            } else {
                                selectedAccount?.let {
                                    onAddRecord(Record(
                                        accountId = it.id, accountName = it.name,
                                        category = category, amount = amount,
                                        currency = it.currency, comment = comment, type = recordType
                                    ))
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        enabled = canSubmit,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent
                        ),
                        elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
                    ) {
                        Text(
                            if (splitMode) "Confirm Split (${splitItems.count { it.category.isNotBlank() && (it.amount.toDoubleOrNull() ?: 0.0) > 0 }} items)"
                            else "Confirm Record",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (canSubmit) Color.White else DGTextSecondary
                        )
                    }
                }
            }
        }
    }
}
