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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.wallettrackers.auth.UserData
import com.example.wallettrackers.converters.colorToLong
import com.example.wallettrackers.converters.longToColor
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.viewmodel.HomeViewModel
import com.github.skydoves.colorpicker.compose.HsvColorPicker
import com.github.skydoves.colorpicker.compose.rememberColorPickerController
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
private fun contentColorFor(backgroundColor: Color): Color {
    val luminance = (0.299 * backgroundColor.red + 0.587 * backgroundColor.green + 0.114 * backgroundColor.blue)
    return if (luminance > 0.5) Color.Black else Color.White
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    userData: UserData?,
    onSignOut: () -> Unit,
    onDeleteAccount: () -> Unit,
    viewModel: HomeViewModel,
    onAddRecord: () -> Unit,
    onSeeAllRecords: () -> Unit,
    isDarkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    onCurrencyConverter: () -> Unit,
    onCategoriesClick: () -> Unit,
    onStatisticsClick: () -> Unit,
    onSmsClick: () -> Unit
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

    val sortedAccounts = remember(accounts) {
        accounts.sortedWith(compareBy { 
            when (it.accountType.lowercase()) {
                "cash" -> 0
                "debit" -> 1
                "credit" -> 2
                else -> 3
            }
        })
    }

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
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Records") },
                    selected = false,
                    onClick = onSeeAllRecords
                )
                NavigationDrawerItem(
                    label = { Text(text = "Categories") },
                    selected = false,
                    onClick = onCategoriesClick
                )
                NavigationDrawerItem(
                    label = { Text(text = "Statistics") },
                    selected = false,
                    onClick = onStatisticsClick
                )
                NavigationDrawerItem(
                    label = { Text(text = "SMS") },
                    selected = false,
                    onClick = onSmsClick,
                    icon = { Icon(Icons.Default.Sms, contentDescription = "SMS") }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Planned payments") },
                    selected = false,
                    onClick = { /*TODO*/ }
                )
                NavigationDrawerItem(
                    label = { Text(text = "Currency Converter") },
                    selected = false,
                    onClick = onCurrencyConverter
                )
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Dark Mode")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = isDarkTheme, onCheckedChange = onThemeChange)
                }
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
                    .fillMaxSize(),
                contentPadding = PaddingValues(8.dp)
            ) {
                item {
                    Text(
                        text = "List of accounts",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 8.dp)
                    )
                }

                val accountItems = sortedAccounts + listOf<Any?>(null)
                items(accountItems.chunked(3)) { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { item ->
                            when (item) {
                                is Account -> AccountCard(
                                    account = item,
                                    onLongClick = {
                                        selectedAccount = item
                                        showAccountOptionsDialog = true
                                    },
                                    modifier = Modifier.weight(1f)
                                )
                                null -> AddAccountCard(
                                    onAddAccountClick = { showAddAccountDialog = true },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (rowItems.size < 3) {
                            repeat(3 - rowItems.size) {
                                Spacer(Modifier.weight(1f).padding(4.dp))
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "List of records",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                }
                items(records.take(3)) { record ->
                    RecordCard(
                        record = record,
                        onLongClick = {
                            selectedRecord = record
                            showRecordOptionsDialog = true
                        }
                    )
                }
                if (records.size > 3) {
                    item {
                        TextButton(onClick = onSeeAllRecords, modifier = Modifier.fillMaxWidth()) {
                            Text("See More")
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordCard(record: Record, onLongClick: () -> Unit) {
    val category = Categories.list.flatMap { it.subCategories + it }.find { it.name == record.category }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(
                onClick = { /* Handle single click if needed */ },
                onLongClick = onLongClick
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (category != null) {
                Icon(
                    imageVector = category.icon,
                    contentDescription = category.name,
                    modifier = Modifier.size(40.dp),
                    tint = category.color
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Category,
                    contentDescription = "Category",
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = record.category, fontWeight = FontWeight.Bold)
                if (record.comment.isNotEmpty()) {
                    Text(
                        text = record.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(text = record.accountName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                val isIncome = record.type == "Income"
                Text(
                    text = "${if (isIncome) "+" else "-"}${record.amount} ${record.currency}",
                    fontWeight = FontWeight.Bold,
                    color = if (isIncome) Color(0xFF4CAF50) else Color.Red
                )
                if (record.balanceAfter.isNotEmpty()) {
                    Text(text = "(${record.balanceAfter} ${record.currency})", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    text = record.timestamp.let {
                        val cal = Calendar.getInstance()
                        cal.time = it
                        val today = Calendar.getInstance()
                        if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                            "Today"
                        } else {
                            SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(it)
                        }
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
    val cardColor = longToColor(account.color)
    val textColor = contentColorFor(cardColor)
    Card(
        modifier = modifier
            .defaultMinSize(minHeight = 100.dp)
            .combinedClickable(
                onClick = { /* Handle single click if needed */ },
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(text = account.name, fontWeight = FontWeight.Bold, color = textColor, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = account.accountType, style = MaterialTheme.typography.bodySmall, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(text = "${account.amount} ${account.currency}", fontWeight = FontWeight.Bold, color = textColor, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun AddAccountCard(onAddAccountClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .height(100.dp)
            .fillMaxWidth()
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
                    verticalAlignment = Alignment.CenterVertically) {
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
    var comment by rememberSaveable { mutableStateOf(record?.comment ?: "") }
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
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment") },
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
                                    currency = it.currency,
                                    comment = comment
                                ) ?: Record(
                                    accountId = it.id,
                                    accountName = it.name,
                                    category = category,
                                    amount = amount,
                                    currency = it.currency,
                                    comment = comment
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
