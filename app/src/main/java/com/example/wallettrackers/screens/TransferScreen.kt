package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.viewmodel.HomeViewModel

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
                title = { Text("Transfer", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Transfer Between Accounts", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)

            // From account
            ExposedDropdownMenuBox(expanded = fromExpanded, onExpandedChange = { fromExpanded = !fromExpanded }) {
                OutlinedTextField(
                    value = fromAccount?.let { "${it.name} (${it.amount} ${it.currency})" } ?: "",
                    onValueChange = {},
                    label = { Text("From Account") },
                    readOnly = true,
                    leadingIcon = { Icon(Icons.Default.ArrowUpward, null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = fromExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(expanded = fromExpanded, onDismissRequest = { fromExpanded = false }) {
                    activeAccounts.filter { it != toAccount }.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text("${acc.name} — ${acc.amount} ${acc.currency}") },
                            onClick = { fromAccount = acc; fromExpanded = false }
                        )
                    }
                }
            }

            // Swap button
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                IconButton(onClick = {
                    val tmp = fromAccount
                    fromAccount = toAccount
                    toAccount = tmp
                }) {
                    Icon(Icons.Default.SwapVert, contentDescription = "Swap",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp))
                }
            }

            // To account
            ExposedDropdownMenuBox(expanded = toExpanded, onExpandedChange = { toExpanded = !toExpanded }) {
                OutlinedTextField(
                    value = toAccount?.let { "${it.name} (${it.amount} ${it.currency})" } ?: "",
                    onValueChange = {},
                    label = { Text("To Account") },
                    readOnly = true,
                    leadingIcon = { Icon(Icons.Default.ArrowDownward, null) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = toExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(expanded = toExpanded, onDismissRequest = { toExpanded = false }) {
                    activeAccounts.filter { it != fromAccount }.forEach { acc ->
                        DropdownMenuItem(
                            text = { Text("${acc.name} — ${acc.amount} ${acc.currency}") },
                            onClick = { toAccount = acc; toExpanded = false }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = amount,
                onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                suffix = { Text(fromAccount?.currency ?: "") }
            )

            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Note (optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(Modifier.weight(1f))

            val amountVal = amount.toDoubleOrNull() ?: 0.0
            val fromBal = fromAccount?.amount?.toDoubleOrNull() ?: 0.0
            val isValid = fromAccount != null && toAccount != null &&
                    fromAccount != toAccount && amountVal > 0 && amountVal <= fromBal

            if (fromAccount != null && amountVal > fromBal) {
                Text(
                    "Amount exceeds available balance (${fromAccount!!.amount} ${fromAccount!!.currency})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Button(
                onClick = {
                    val from = fromAccount ?: return@Button
                    val to = toAccount ?: return@Button
                    viewModel.transferBetweenAccounts(from, to, amountVal, note)
                    onBack()
                },
                enabled = isValid,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.SwapHoriz, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Transfer", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
