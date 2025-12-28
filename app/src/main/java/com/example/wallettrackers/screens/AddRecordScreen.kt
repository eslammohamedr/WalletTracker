package com.example.wallettrackers.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRecordScreen(
    accounts: List<Account>,
    onAddRecord: (Record) -> Unit,
    onCancel: () -> Unit,
    onCategoryClick: () -> Unit,
    selectedCategory: String?
) {
    var selectedAccount by remember { mutableStateOf<Account?>(null) }
    var amount by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val category = selectedCategory ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add Record") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(it)
                .padding(16.dp)
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedAccount?.name ?: "",
                    onValueChange = {},
                    label = { Text("Account") },
                    readOnly = true,
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                    },
                    modifier = Modifier.menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.name) },
                            onClick = {
                                selectedAccount = account
                                expanded = false
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onCategoryClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = if (category.isNotBlank()) category else "Select Category")
            }
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = amount,
                onValueChange = { if (it.all { char -> char.isDigit() }) amount = it },
                label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    selectedAccount?.let {
                        val record = Record(
                            accountId = it.id,
                            accountName = it.name,
                            category = category,
                            amount = amount,
                            currency = it.currency
                        )
                        onAddRecord(record)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = selectedAccount != null && category.isNotBlank() && amount.isNotBlank()
            ) {
                Text("Done")
            }
        }
    }
}
