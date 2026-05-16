package com.example.wallettrackers.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.viewmodel.HomeViewModel

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val accounts by viewModel.accounts
    val activeAccounts = remember(accounts) { accounts.filter { !it.isArchived } }

    var fromAccount by remember { mutableStateOf<Account?>(null) }
    var toAccount by remember { mutableStateOf<Account?>(null) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var fromExpanded by remember { mutableStateOf(false) }
    var toExpanded by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transfer Funds", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground
                )
            )
        },
        containerColor = AppBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                Text(
                    text = "Transfer Between Accounts",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
            }

            // From account
            ExposedDropdownMenuBox(expanded = fromExpanded, onExpandedChange = { fromExpanded = !fromExpanded }) {
                OutlinedTextField(
                    value = fromAccount?.let { "${it.name} (${it.amount} ${it.currency})" } ?: "",
                    onValueChange = {},
                    label = { Text("Source Account") },
                    readOnly = true,
                    leadingIcon = { Icon(Icons.Default.ArrowUpward, null, tint = AppRed) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fromExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppTextPrimary,
                        unfocusedTextColor = AppTextPrimary,
                        focusedContainerColor = AppSurface,
                        unfocusedContainerColor = AppSurface,
                        focusedBorderColor = AppVioletLight,
                        unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                        focusedLabelColor = AppVioletLight,
                        unfocusedLabelColor = AppTextSecondary
                    )
                )
                ExposedDropdownMenu(
                    expanded = fromExpanded, 
                    onDismissRequest = { fromExpanded = false },
                    modifier = Modifier.background(AppSurface)
                ) {
                    activeAccounts.filter { it != toAccount }.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text("${acc.name} — ${acc.amount} ${acc.currency}", color = AppTextPrimary) },
                            onClick = { fromAccount = acc; fromExpanded = false }
                        )
                    }
                }
            }

            // Swap button
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = {
                        val tmp = fromAccount
                        fromAccount = toAccount
                        toAccount = tmp
                    },
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(AppPrimary.copy(alpha = 0.2f))
                ) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Swap",
                        tint = AppVioletLight,
                        modifier = Modifier.size(24.dp))
                }
            }

            // To account
            ExposedDropdownMenuBox(expanded = toExpanded, onExpandedChange = { toExpanded = !toExpanded }) {
                OutlinedTextField(
                    value = toAccount?.let { "${it.name} (${it.amount} ${it.currency})" } ?: "",
                    onValueChange = {},
                    label = { Text("Destination Account") },
                    readOnly = true,
                    leadingIcon = { Icon(Icons.Default.ArrowDownward, null, tint = AppGreen) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = AppTextPrimary,
                        unfocusedTextColor = AppTextPrimary,
                        focusedContainerColor = AppSurface,
                        unfocusedContainerColor = AppSurface,
                        focusedBorderColor = AppVioletLight,
                        unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                        focusedLabelColor = AppVioletLight,
                        unfocusedLabelColor = AppTextSecondary
                    )
                )
                ExposedDropdownMenu(
                    expanded = toExpanded, 
                    onDismissRequest = { toExpanded = false },
                    modifier = Modifier.background(AppSurface)
                ) {
                    activeAccounts.filter { it != fromAccount }.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text("${acc.name} — ${acc.amount} ${acc.currency}", color = AppTextPrimary) },
                            onClick = { toAccount = acc; toExpanded = false }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                label = { Text("Transfer Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                suffix = { Text(fromAccount?.currency ?: "", color = AppTextSecondary) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppTextPrimary,
                    unfocusedTextColor = AppTextPrimary,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                    focusedBorderColor = AppVioletLight,
                    unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                    focusedLabelColor = AppVioletLight,
                    unfocusedLabelColor = AppTextSecondary
                )
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppTextPrimary,
                    unfocusedTextColor = AppTextPrimary,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                    focusedBorderColor = AppVioletLight,
                    unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                    focusedLabelColor = AppVioletLight,
                    unfocusedLabelColor = AppTextSecondary
                )
            )

            Spacer(Modifier.weight(1f))

            val amountVal = amount.toDoubleOrNull() ?: 0.0
            val fromBal = fromAccount?.amount?.toDoubleOrNull() ?: 0.0
            val isValid = fromAccount != null && toAccount != null &&
                    fromAccount != toAccount && amountVal > 0 && amountVal <= fromBal

            if (fromAccount != null && amountVal > fromBal) {
                Surface(
                    color = AppRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Amount exceeds available balance (${fromAccount!!.amount} ${fromAccount!!.currency})",
                        style = MaterialTheme.typography.labelSmall,
                        color = AppRed,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isValid) AccentGradient else SolidColor(AppPrimary.copy(alpha = 0.2f)))
            ) {
                Button(
                    onClick = {
                        val from = fromAccount ?: return@Button
                        val to = toAccount ?: return@Button
                        viewModel.transferBetweenAccounts(from, to, amountVal, note)
                        onBack()
                    },
                    enabled = isValid,
                    modifier = Modifier.fillMaxSize(),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, tint = if (isValid) Color.White else AppTextSecondary)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Execute Transfer", 
                        style = MaterialTheme.typography.titleMedium, 
                        fontWeight = FontWeight.Bold,
                        color = if (isValid) Color.White else AppTextSecondary
                    )
                }
            }
        }
    }
}
