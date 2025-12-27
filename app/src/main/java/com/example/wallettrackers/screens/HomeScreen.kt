package com.example.wallettrackers.screens

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.wallettrackers.auth.UserData
import com.example.wallettrackers.converters.colorToLong
import com.example.wallettrackers.converters.longToColor
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.viewmodel.HomeViewModel
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userData: UserData?,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    viewModel: HomeViewModel,
    onAddRecord: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val accounts by viewModel.accounts
    val records by viewModel.records
    val context = LocalContext.current

    var showAddAccountDialog by remember { mutableStateOf(false) }
    var showAccountOptionsDialog by remember { mutableStateOf(false) }
    var showDeleteAccountDialog by remember { mutableStateOf(false) }
    var showEditAccountDialog by remember { mutableStateOf(false) }
    var selectedAccount by remember { mutableStateOf<Account?>(null) }

    var showRecordOptionsDialog by remember { mutableStateOf(false) }
    var showDeleteRecordDialog by remember { mutableStateOf(false) }
    var showEditRecordDialog by remember { mutableStateOf(false) }
    var selectedRecord by remember { mutableStateOf<Record?>(null) }

    val toastMessage by viewModel.toastMessage
    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.onToastShown()
        }
    }

    if (showAddAccountDialog) {
        AccountDialog(
            onDismiss = { showAddAccountDialog = false },
            onConfirm = { account ->
                viewModel.addAccount(account)
            },
            title = "Add Account",
            confirmButtonText = "Add"
        )
    }

    selectedAccount?.let { account ->
        if (showAccountOptionsDialog) {
            OptionsDialog(
                onDismiss = { showAccountOptionsDialog = false },
                onEdit = {
                    showAccountOptionsDialog = false
                    showEditAccountDialog = true
                },
                onDelete = {
                    showAccountOptionsDialog = false
                    showDeleteAccountDialog = true
                }
            )
        }

        if (showDeleteAccountDialog) {
            DeleteConfirmationDialog(
                onDismiss = { showDeleteAccountDialog = false },
                onConfirm = {
                    viewModel.deleteAccount(account.id)
                    showDeleteAccountDialog = false
                },
                title = "Delete Account",
                text = "Are you sure you want to delete this account?"
            )
        }
        if (showEditAccountDialog) {
            AccountDialog(
                account = account,
                onDismiss = { showEditAccountDialog = false },
                onConfirm = { updatedAccount ->
                    viewModel.updateAccount(updatedAccount)
                },
                title = "Edit Account",
                confirmButtonText = "Update"
            )
        }
    }

    selectedRecord?.let { record ->
        if (showRecordOptionsDialog) {
            OptionsDialog(
                onDismiss = { showRecordOptionsDialog = false },
                onEdit = {
                    showRecordOptionsDialog = false
                    showEditRecordDialog = true
                },
                onDelete = {
                    showRecordOptionsDialog = false
                    showDeleteRecordDialog = true
                }
            )
        }

        if (showDeleteRecordDialog) {
            DeleteConfirmationDialog(
                onDismiss = { showDeleteRecordDialog = false },
                onConfirm = {
                    viewModel.deleteRecord(record.id)
                    showDeleteRecordDialog = false
                },
                title = "Delete Record",
                text = "Are you sure you want to delete this record?"
            )
        }
        if (showEditRecordDialog) {
            RecordDialog(
                record = record,
                accounts = accounts,
                onDismiss = { showEditRecordDialog = false },
                onConfirm = { updatedRecord ->
                    viewModel.updateRecord(updatedRecord)
                },
                title = "Edit Record",
                confirmButtonText = "Update"
            )
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (userData?.profilePictureUrl != null) {
                        AsyncImage(
                            model = userData.profilePictureUrl,
                            contentDescription = "Profile picture",
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    if (userData?.username != null) {
                        Text(
                            text = userData.username,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text("My Wallet", style = MaterialTheme.typography.bodySmall)
                }
                Divider()
                NavigationDrawerItem(
                    label = { Text(text = "Home") },
                    selected = true,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Records") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Investments") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Statistics") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Planned payments") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Budgets") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Debts") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Goals") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Sign Out") },
                    selected = false,
                    onClick = onSignOut,
                    icon = { Icon(Icons.Default.Logout, contentDescription = "Sign Out") }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Delete Account") },
                    selected = false,
                    onClick = { showDeleteAccountDialog = true },
                    icon = { Icon(Icons.Default.Delete, contentDescription = "Delete Account") }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Home") },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                drawerState.apply {
                                    if (isClosed) open() else close()
                                }
                            }
                        }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onAddRecord) {
                    Icon(Icons.Default.Add, contentDescription = "Add Record")
                }
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                item {
                    Text(
                        text = "List of accounts",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
                items(accounts.chunked(2)) { accountRow ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        accountRow.forEach { account ->
                            AccountCard(
                                account = account,
                                onLongClick = {
                                    selectedAccount = account
                                    showAccountOptionsDialog = true
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (accountRow.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
                item {
                    AddAccountCard(onAddAccountClick = { showAddAccountDialog = true })
                }
                item {
                    Text(
                        text = "List of records",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
                items(records) { record ->
                    RecordCard(
                        record = record,
                        onLongClick = {
                            selectedRecord = record
                            showRecordOptionsDialog = true
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordCard(record: Record, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { /* Handle single click if needed */ },
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = longToColor(record.color))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(text = record.accountName, fontWeight = FontWeight.Bold, color = Color.Black)
                Text(text = record.category, style = MaterialTheme.typography.bodySmall, color = Color.Black)
                Text(
                    text = record.timestamp?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(it) } ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
            }
            Text(text = "${record.amount} ${record.currency}", fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AccountCard(
    account: Account,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .padding(8.dp)
            .height(120.dp)
            .combinedClickable(
                onClick = { /* Handle single click if needed */ },
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = longToColor(account.color))
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = account.name, fontWeight = FontWeight.Bold, color = Color.Black)
            Text(text = account.accountType, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color.Black)
            Text(text = "${account.amount} ${account.currency}", fontWeight = FontWeight.Bold, color = Color.Black)
        }
    }
}

@Composable
fun AddAccountCard(onAddAccountClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center
    ) {
        Card(
            modifier = Modifier
                .padding(8.dp)
                .height(120.dp)
                .width(180.dp)
                .clickable(onClick = onAddAccountClick),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Account")
                Text(text = "Add account")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDialog(
    account: Account? = null,
    onDismiss: () -> Unit,
    onConfirm: (Account) -> Unit,
    title: String,
    confirmButtonText: String
) {
    var name by rememberSaveable { mutableStateOf(account?.name ?: "") }
    var accountType by rememberSaveable { mutableStateOf(account?.accountType ?: "Debit") }
    var last4Digits by rememberSaveable { mutableStateOf(account?.last4Digits ?: "") }
    var amount by rememberSaveable { mutableStateOf(account?.amount ?: "") }
    var currency by rememberSaveable { mutableStateOf(account?.currency ?: "EGP") }
    var expandedAccountType by remember { mutableStateOf(false) }
    var expandedCurrency by remember { mutableStateOf(false) }
    val colorPickerController = rememberColorPickerController()
    var selectedColor by remember { mutableStateOf(account?.let { longToColor(it.color) } ?: Color.Red) }
    var showColorPicker by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account Name") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = expandedAccountType,
                    onExpandedChange = { expandedAccountType = !expandedAccountType }
                ) {
                    OutlinedTextField(
                        value = accountType,
                        onValueChange = {},
                        label = { Text("Account Type") },
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAccountType)
                        },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedAccountType,
                        onDismissRequest = { expandedAccountType = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Debit") },
                            onClick = {
                                accountType = "Debit"
                                expandedAccountType = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Credit") },
                            onClick = {
                                accountType = "Credit"
                                expandedAccountType = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Cash") },
                            onClick = {
                                accountType = "Cash"
                                expandedAccountType = false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = last4Digits,
                    onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) last4Digits = it },
                    label = { Text("Last 4 Digits") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.all { char -> char.isDigit() }) amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                ExposedDropdownMenuBox(
                    expanded = expandedCurrency,
                    onExpandedChange = { expandedCurrency = !expandedCurrency }
                ) {
                    OutlinedTextField(
                        value = currency,
                        onValueChange = {},
                        label = { Text("Currency") },
                        readOnly = true,
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCurrency)
                        },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = expandedCurrency,
                        onDismissRequest = { expandedCurrency = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("EGP") },
                            onClick = {
                                currency = "EGP"
                                expandedCurrency = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Dollar") },
                            onClick = {
                                currency = "Dollar"
                                expandedCurrency = false
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Euro") },
                            onClick = {
                                currency = "Euro"
                                expandedCurrency = false
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                Text(text = "Color")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showColorPicker = !showColorPicker }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(selectedColor, shape = CircleShape)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text("Select Color")
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Palette, contentDescription = "Select Color")
                }

                if (showColorPicker) {
                    HsvColorPicker(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        controller = colorPickerController,
                        onColorChanged = { colorEnvelope ->
                            selectedColor = colorEnvelope.color
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val finalAmount = if (accountType == "Credit") {
                                "-${amount}"
                            } else {
                                amount
                            }
                            val updatedAccount = account?.copy(
                                name = name,
                                accountType = accountType,
                                last4Digits = last4Digits,
                                amount = finalAmount,
                                currency = currency,
                                color = colorToLong(selectedColor)
                            ) ?: Account(
                                name = name,
                                accountType = accountType,
                                last4Digits = last4Digits,
                                amount = finalAmount,
                                currency = currency,
                                color = colorToLong(selectedColor)
                            )
                            onConfirm(updatedAccount)
                            onDismiss()
                        },
                        enabled = name.isNotBlank() && last4Digits.length == 4 && amount.isNotBlank()
                    ) {
                        Text(text = confirmButtonText)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordDialog(
    record: Record? = null,
    accounts: List<Account>,
    onDismiss: () -> Unit,
    onConfirm: (Record) -> Unit,
    title: String,
    confirmButtonText: String
) {
    var selectedAccount by remember { mutableStateOf(accounts.find { it.id == record?.accountId }) }
    var category by rememberSaveable { mutableStateOf(record?.category ?: "") }
    var amount by rememberSaveable { mutableStateOf(record?.amount ?: "") }
    var expanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(text = title, style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))

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
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.all { char -> char.isDigit() }) amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(32.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            selectedAccount?.let {
                                val updatedRecord = record?.copy(
                                    accountId = it.id,
                                    accountName = it.name,
                                    category = category,
                                    amount = amount,
                                    currency = it.currency
                                ) ?: Record(
                                    accountId = it.id,
                                    accountName = it.name,
                                    category = category,
                                    amount = amount,
                                    currency = it.currency
                                )
                                onConfirm(updatedRecord)
                                onDismiss()
                            }
                        },
                        enabled = selectedAccount != null && category.isNotBlank() && amount.isNotBlank()
                    ) {
                        Text(text = confirmButtonText)
                    }
                }
            }
        }
    }
}

@Composable
fun OptionsDialog(
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column {
                Text("Edit", modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(16.dp))
                Text("Delete", modifier = Modifier.fillMaxWidth().clickable(onClick = onDelete).padding(16.dp))
            }
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    title: String,
    text: String
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            Button(
                onClick = onConfirm
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss
            ) {
                Text("Cancel")
            }
        }
    )
}
