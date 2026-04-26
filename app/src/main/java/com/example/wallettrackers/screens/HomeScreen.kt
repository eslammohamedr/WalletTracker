package com.example.wallettrackers.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
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
import com.example.wallettrackers.ui.theme.pickAutoColor
import com.example.wallettrackers.viewmodel.HomeViewModel
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

    val editingRecord by viewModel.editingRecord
    val showEditRecordDialog by viewModel.showEditDialog
    var optionSelectedRecord by remember { mutableStateOf<Record?>(null) }

    // Show delete account confirmation from drawer
    var showDeleteUserDialog by remember { mutableStateOf(false) }

    val sortedAccounts = remember(accounts) {
        accounts.sortedWith(compareBy {
            when (it.accountType.lowercase()) {
                "cash" -> 0
                "debit" -> 1
                "credit", "credit card" -> 2
                else -> 3
            }
        })
    }

    val totalBalance = remember(accounts) {
        accounts.filter { it.accountType.lowercase() != "credit card" }
            .sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
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
            onConfirm = { account -> viewModel.addAccount(account) },
            existingColors = accounts.map { it.color },
            title = "Add Account",
            confirmButtonText = "Add"
        )
    }

    selectedAccount?.let { account ->
        if (showAccountOptionsDialog) {
            OptionsDialog(
                onDismiss = { showAccountOptionsDialog = false },
                onEdit = { showAccountOptionsDialog = false; showEditAccountDialog = true },
                onDelete = { showAccountOptionsDialog = false; showDeleteAccountDialog = true }
            )
        }
        if (showDeleteAccountDialog) {
            DeleteConfirmationDialog(
                onDismiss = { showDeleteAccountDialog = false },
                onConfirm = { viewModel.deleteAccount(account.id); showDeleteAccountDialog = false },
                title = "Delete Account",
                text = "Are you sure you want to delete \"${account.name}\"?"
            )
        }
        if (showEditAccountDialog) {
            AccountDialog(
                account = account,
                onDismiss = { showEditAccountDialog = false },
                onConfirm = { updatedAccount -> viewModel.updateAccount(updatedAccount) },
                existingColors = accounts.filter { it.id != account.id }.map { it.color },
                title = "Edit Account",
                confirmButtonText = "Update"
            )
        }
    }

    if (showRecordOptionsDialog && optionSelectedRecord != null) {
        OptionsDialog(
            onDismiss = { showRecordOptionsDialog = false },
            onEdit = { showRecordOptionsDialog = false; viewModel.startEditing(optionSelectedRecord!!) },
            onDelete = { showRecordOptionsDialog = false; showDeleteRecordDialog = true }
        )
    }

    if (showDeleteRecordDialog && optionSelectedRecord != null) {
        DeleteConfirmationDialog(
            onDismiss = { showDeleteRecordDialog = false },
            onConfirm = { viewModel.deleteRecord(optionSelectedRecord!!.id); showDeleteRecordDialog = false },
            title = "Delete Record",
            text = "Are you sure you want to delete this record?"
        )
    }

    if (showEditRecordDialog && editingRecord != null) {
        RecordDialog(
            record = editingRecord,
            accounts = accounts,
            onDismiss = { viewModel.stopEditing() },
            onConfirm = { updatedRecord -> viewModel.updateRecord(updatedRecord); viewModel.stopEditing() },
            onCategoryClick = onCategoriesClick,
            title = "Edit Record",
            confirmButtonText = "Update"
        )
    }

    if (showDeleteUserDialog) {
        DeleteConfirmationDialog(
            onDismiss = { showDeleteUserDialog = false },
            onConfirm = { onDeleteAccount(); showDeleteUserDialog = false },
            title = "Delete Account",
            text = "This will permanently delete your account and all data. This cannot be undone."
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                // Profile Header
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 24.dp, vertical = 28.dp)
                ) {
                    Column {
                        if (userData?.profilePictureUrl != null) {
                            AsyncImage(
                                model = userData.profilePictureUrl,
                                contentDescription = "Profile picture",
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = userData?.username?.firstOrNull()?.uppercase() ?: "?",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = userData?.username ?: "User",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "My Wallet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text("Home") },
                    selected = true,
                    icon = { Icon(Icons.Default.Home, contentDescription = null) },
                    onClick = { scope.launch { drawerState.close() } }
                )
                NavigationDrawerItem(
                    label = { Text("Records") },
                    selected = false,
                    icon = { Icon(Icons.Default.List, contentDescription = null) },
                    onClick = { scope.launch { drawerState.close() }; onSeeAllRecords() }
                )
                NavigationDrawerItem(
                    label = { Text("Categories") },
                    selected = false,
                    icon = { Icon(Icons.Default.Category, contentDescription = null) },
                    onClick = { scope.launch { drawerState.close() }; onCategoriesClick() }
                )
                NavigationDrawerItem(
                    label = { Text("Statistics") },
                    selected = false,
                    icon = { Icon(Icons.Default.BarChart, contentDescription = null) },
                    onClick = { scope.launch { drawerState.close() }; onStatisticsClick() }
                )
                NavigationDrawerItem(
                    label = { Text("SMS Center") },
                    selected = false,
                    icon = { Icon(Icons.Default.Sms, contentDescription = null) },
                    onClick = { scope.launch { drawerState.close() }; onSmsClick() }
                )
                NavigationDrawerItem(
                    label = { Text("Currency Converter") },
                    selected = false,
                    icon = { Icon(Icons.Default.CurrencyExchange, contentDescription = null) },
                    onClick = { scope.launch { drawerState.close() }; onCurrencyConverter() }
                )

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                // Dark Mode Toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (isDarkTheme) Icons.Default.DarkMode else Icons.Default.LightMode,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Dark Mode", modifier = Modifier.weight(1f))
                    Switch(checked = isDarkTheme, onCheckedChange = onThemeChange)
                }

                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

                NavigationDrawerItem(
                    label = { Text("Sign Out") },
                    selected = false,
                    icon = { Icon(Icons.Default.Logout, contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    onClick = onSignOut
                )
                NavigationDrawerItem(
                    label = { Text("Delete Account", color = MaterialTheme.colorScheme.error) },
                    selected = false,
                    icon = { Icon(Icons.Default.DeleteForever, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error) },
                    onClick = { showDeleteUserDialog = true }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = if (userData?.username != null) "Hi, ${userData.username.split(" ").first()}" else "Home",
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.apply { if (isClosed) open() else close() } } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = onAddRecord,
                    icon = { Icon(Icons.Default.Add, contentDescription = "Add Record") },
                    text = { Text("Add Record") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize(),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                // Balance Banner
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Total Balance",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = String.format(Locale.getDefault(), "%,.2f EGP", totalBalance),
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }

                // Accounts section header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Accounts",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${accounts.size} total",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                val accountItems = sortedAccounts + listOf<Any?>(null)
                items(accountItems.chunked(3)) { rowItems ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
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
                            repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // Records section header
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Records",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (records.size > 3) {
                            TextButton(onClick = onSeeAllRecords) {
                                Text("See All", color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }

                if (records.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.ReceiptLong,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "No records yet",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Tap + to add your first record",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                } else {
                    items(records.take(3)) { record ->
                        RecordCard(
                            record = record,
                            onLongClick = {
                                optionSelectedRecord = record
                                showRecordOptionsDialog = true
                            }
                        )
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
    val isIncome = record.type == "Income"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon with colored circle background
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background((category?.color ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = category?.icon ?: Icons.Default.Category,
                    contentDescription = category?.name,
                    modifier = Modifier.size(24.dp),
                    tint = category?.color ?: MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.category,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (record.comment.isNotEmpty()) {
                    Text(
                        text = record.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = record.accountName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${if (isIncome) "+" else "-"}${record.amount} ${record.currency}",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isIncome) Color(0xFF22C55E) else Color(0xFFEF4444)
                )
                if (record.balanceAfter.isNotEmpty()) {
                    Text(
                        text = "${record.balanceAfter} ${record.currency}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = record.timestamp.let {
                        val cal = Calendar.getInstance()
                        cal.time = it
                        val today = Calendar.getInstance()
                        if (cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)) {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(it)
                        } else {
                            SimpleDateFormat("dd MMM", Locale.getDefault()).format(it)
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

    val accountTypeIcon = when (account.accountType.lowercase()) {
        "credit card" -> Icons.Default.CreditCard
        "cash" -> Icons.Default.Payments
        else -> Icons.Default.AccountBalance
    }

    Card(
        modifier = modifier
            .defaultMinSize(minHeight = 110.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = account.name,
                    fontWeight = FontWeight.Bold,
                    color = textColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = accountTypeIcon,
                    contentDescription = account.accountType,
                    tint = textColor.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }

            Column {
                if (account.last4Digits.isNotEmpty()) {
                    Text(
                        text = "•••• ${account.last4Digits}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                }

                val displayAmount = if (account.accountType.contains("Credit", ignoreCase = true)) {
                    val creditLimit = account.creditLimit ?: 0.0
                    val available = account.amount.toDoubleOrNull() ?: 0.0
                    val debt = creditLimit - available
                    String.format(Locale.getDefault(), "%.2f", debt)
                } else {
                    account.amount
                }

                Text(
                    text = "$displayAmount ${account.currency}",
                    fontWeight = FontWeight.ExtraBold,
                    color = textColor,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (account.accountType.contains("Credit", ignoreCase = true) && account.creditLimit != null) {
                    Text(
                        text = "Avail: ${account.amount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun AddAccountCard(onAddAccountClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .defaultMinSize(minHeight = 110.dp)
            .clickable(onClick = onAddAccountClick),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = "Add Account",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Add",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDialog(
    account: Account? = null,
    existingColors: List<Long> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (Account) -> Unit,
    title: String,
    confirmButtonText: String
) {
    var name by rememberSaveable { mutableStateOf(account?.name ?: "") }
    var accountType by rememberSaveable { mutableStateOf(account?.accountType ?: "Debit") }
    var last4Digits by rememberSaveable { mutableStateOf(account?.last4Digits ?: "") }
    var amount by rememberSaveable { mutableStateOf(account?.amount ?: "") }
    var creditLimit by rememberSaveable { mutableStateOf(account?.creditLimit?.toString() ?: "") }
    var billingDay by rememberSaveable { mutableStateOf(account?.billingDay?.toString() ?: "") }
    var currency by rememberSaveable { mutableStateOf(account?.currency ?: "EGP") }
    var expandedAccountType by remember { mutableStateOf(false) }
    var expandedCurrency by remember { mutableStateOf(false) }

    // Auto-assign color: use existing account color when editing, pick unused palette color when adding
    val assignedColor = remember(account, existingColors) {
        if (account != null) longToColor(account.color)
        else pickAutoColor(existingColors)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            LazyColumn(modifier = Modifier.padding(20.dp)) {
                item {
                    // Title row with color swatch preview
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .clip(CircleShape)
                                .background(assignedColor)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(text = title, style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Account Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(Modifier.height(10.dp))

                    ExposedDropdownMenuBox(
                        expanded = expandedAccountType,
                        onExpandedChange = { expandedAccountType = !expandedAccountType },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = accountType,
                            onValueChange = {},
                            label = { Text("Account Type") },
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedAccountType) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(expanded = expandedAccountType, onDismissRequest = { expandedAccountType = false }) {
                            listOf("Debit", "Credit Card", "Cash").forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type) },
                                    onClick = { accountType = type; expandedAccountType = false }
                                )
                            }
                        }
                    }

                    if (accountType != "Cash") {
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = last4Digits,
                            onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) last4Digits = it },
                            label = { Text("Last 4 Digits") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    if (accountType == "Credit Card") {
                        OutlinedTextField(
                            value = creditLimit,
                            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) creditLimit = it },
                            label = { Text("Credit Limit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                            label = { Text("Available Credit") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = billingDay,
                            onValueChange = {
                                val n = it.toIntOrNull()
                                if (it.isEmpty() || (n != null && n in 1..31)) billingDay = it
                            },
                            label = { Text("Statement Day (1–31)") },
                            placeholder = { Text("e.g. 15") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    } else {
                        OutlinedTextField(
                            value = amount,
                            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                            label = { Text("Current Balance") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    ExposedDropdownMenuBox(
                        expanded = expandedCurrency,
                        onExpandedChange = { expandedCurrency = !expandedCurrency },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = currency,
                            onValueChange = {},
                            label = { Text("Currency") },
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedCurrency) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )
                        ExposedDropdownMenu(expanded = expandedCurrency, onDismissRequest = { expandedCurrency = false }) {
                            listOf("EGP", "Dollar", "Euro").forEach { cur ->
                                DropdownMenuItem(
                                    text = { Text(cur) },
                                    onClick = { currency = cur; expandedCurrency = false }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(10.dp)
                        ) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val colorLong = colorToLong(assignedColor)
                                val digits = if (accountType == "Cash") "" else last4Digits
                                val parsedBillingDay = billingDay.toIntOrNull()
                                val updatedAccount = account?.copy(
                                    name = name, accountType = accountType, last4Digits = digits,
                                    amount = amount,
                                    creditLimit = if (accountType == "Credit Card") creditLimit.toDoubleOrNull() else null,
                                    billingDay = if (accountType == "Credit Card") parsedBillingDay else null,
                                    currency = currency, color = colorLong
                                ) ?: Account(
                                    name = name, accountType = accountType, last4Digits = digits,
                                    amount = amount,
                                    creditLimit = if (accountType == "Credit Card") creditLimit.toDoubleOrNull() else null,
                                    billingDay = if (accountType == "Credit Card") parsedBillingDay else null,
                                    currency = currency, color = colorLong
                                )
                                onConfirm(updatedAccount)
                                onDismiss()
                            },
                            enabled = name.isNotBlank() && amount.isNotBlank() &&
                                    (accountType == "Cash" || last4Digits.length == 4) &&
                                    (accountType != "Credit Card" || creditLimit.isNotBlank()),
                            shape = RoundedCornerShape(10.dp)
                        ) { Text(confirmButtonText) }
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
    onCategoryClick: () -> Unit = {},
    title: String,
    confirmButtonText: String
) {
    var selectedAccount by remember(record) { mutableStateOf(accounts.find { it.id == record?.accountId }) }
    var category by remember(record?.category) { mutableStateOf(record?.category ?: "") }
    var amount by remember(record?.amount) { mutableStateOf(record?.amount ?: "") }
    var comment by remember(record?.comment) { mutableStateOf(record?.comment ?: "") }
    var expanded by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selectedAccount?.name ?: "",
                        onValueChange = {},
                        label = { Text("Account") },
                        readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        accounts.forEach { account ->
                            DropdownMenuItem(
                                text = { Text(account.name) },
                                onClick = { selectedAccount = account; expanded = false }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Surface(
                    onClick = onCategoryClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (category.isNotEmpty()) category else "Select Category",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (category.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Icon(Icons.Default.Category, contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) amount = it },
                    label = { Text("Amount") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Comment (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                        Text("Cancel")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            selectedAccount?.let {
                                val updatedRecord = record?.copy(
                                    accountId = it.id, accountName = it.name,
                                    category = category, amount = amount,
                                    currency = it.currency, comment = comment
                                ) ?: Record(
                                    accountId = it.id, accountName = it.name,
                                    category = category, amount = amount,
                                    currency = it.currency, comment = comment
                                )
                                onConfirm(updatedRecord)
                                onDismiss()
                            }
                        },
                        enabled = selectedAccount != null && category.isNotBlank() && amount.isNotBlank(),
                        shape = RoundedCornerShape(10.dp)
                    ) { Text(confirmButtonText) }
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
        Card(shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(8.dp)) {
                ListItem(
                    headlineContent = { Text("Edit", fontWeight = FontWeight.Medium) },
                    leadingContent = {
                        Icon(Icons.Default.Edit, null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onEdit)
                )
                ListItem(
                    headlineContent = {
                        Text("Delete", color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Medium)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                    },
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onDelete)
                )
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
        icon = {
            Icon(
                Icons.Default.DeleteForever,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text(title, textAlign = TextAlign.Center) },
        text = {
            Text(text, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                shape = RoundedCornerShape(10.dp)
            ) { Text("Delete") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shape = RoundedCornerShape(10.dp)) {
                Text("Cancel")
            }
        }
    )
}
