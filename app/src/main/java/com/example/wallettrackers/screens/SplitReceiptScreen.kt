package com.example.wallettrackers.screens

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.MainActivity
import com.example.wallettrackers.ui.theme.*
import com.example.wallettrackers.viewmodel.HomeViewModel
import java.text.NumberFormat
import java.util.Locale

private val currencyFormat = NumberFormat.getNumberInstance(Locale.US).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}

private val personColors = listOf(
    0xFF2563EB, 0xFF7C3AED, 0xFF059669, 0xFFDC2626,
    0xFFD97706, 0xFF0891B2, 0xFFDB2777, 0xFF65A30D,
    0xFF9333EA, 0xFF0369A1, 0xFFEA580C, 0xFF0D9488
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitReceiptScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit
) {
    val receipt by viewModel.splitReceipt
    val isScanning by viewModel.isSplitScanning
    val people by viewModel.splitPeople
    val assignments by viewModel.splitAssignments
    val context = LocalContext.current

    // 0 = scan, 1 = review items, 2 = assign people, 3 = summary
    var step by rememberSaveable { mutableStateOf(if (receipt != null) 1 else 0) }
    // true when viewing a saved split from history (read-only)
    var viewingSaved by rememberSaveable { mutableStateOf(false) }

    // Update step when receipt loads
    LaunchedEffect(receipt) {
        if (receipt != null && step == 0) step = 1
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) viewModel.scanReceiptForSplit(bitmap)
            } catch (_: Exception) {}
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            when (step) {
                                0 -> "Split Receipt"
                                1 -> "Review Items"
                                2 -> "Assign Items"
                                else -> "Split Summary"
                            },
                            fontWeight = FontWeight.Bold,
                            color = AppTextPrimary
                        )
                        if (receipt != null) {
                            Text(
                                receipt!!.merchant.ifBlank { "Receipt" } + " - ${receipt!!.currency} ${currencyFormat.format(receipt!!.total)}",
                                color = AppTextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            step == 0 -> onBack()
                            viewingSaved -> { viewModel.clearSplitReceipt(); viewingSaved = false; step = 0 }
                            step == 1 -> { viewModel.clearSplitReceipt(); step = 0 }
                            else -> step--
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AppTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppBackground)
            )
        },
        containerColor = AppBackground
    ) { padding ->
        AnimatedContent(
            targetState = step,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            modifier = Modifier.padding(padding),
            label = "step"
        ) { currentStep ->
            when (currentStep) {
                0 -> ScanStep(
                    isScanning = isScanning,
                    onPickImage = {
                        (context as? MainActivity)?.markExpectingReturn()
                        imagePickerLauncher.launch("image/*")
                    },
                    viewModel = viewModel,
                    onOpenSaved = { saved ->
                        viewModel.loadSavedSplitForView(saved)
                        viewingSaved = true
                        step = 3
                    }
                )
                1 -> ReviewItemsStep(
                    viewModel = viewModel,
                    onNext = { step = 2 }
                )
                2 -> AssignStep(
                    viewModel = viewModel,
                    onNext = { step = 3 }
                )
                3 -> SummaryStep(
                    viewModel = viewModel,
                    isViewingSaved = viewingSaved,
                    onDone = {
                        if (!viewingSaved) viewModel.saveSplitToHistory()
                        else viewModel.clearSplitReceipt()
                        viewingSaved = false
                        onBack()
                    },
                    onRescan = {
                        viewModel.clearSplitReceipt()
                        viewingSaved = false
                        step = 0
                    }
                )
            }
        }
    }
}

// ── Step 0: Scan ─────────────────────────────────────────────────────────────

@Composable
private fun ScanStep(
    isScanning: Boolean,
    onPickImage: () -> Unit,
    viewModel: HomeViewModel,
    onOpenSaved: (HomeViewModel.SavedSplit) -> Unit
) {
    val savedSplits by viewModel.savedSplits
    var deleteConfirm by remember { mutableStateOf<String?>(null) }

    // Delete confirmation dialog
    deleteConfirm?.let { splitId ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text("Delete Split", color = AppTextPrimary) },
            text = { Text("Remove this saved split?", color = AppTextSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSavedSplit(splitId)
                    deleteConfirm = null
                }) { Text("Delete", color = AppRed, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) {
                    Text("Cancel", color = AppTextSecondary)
                }
            },
            containerColor = AppSurface
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header + scan button
        item {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.Receipt,
                    contentDescription = null,
                    tint = AppVioletLight,
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Scan a Receipt to Split",
                    style = MaterialTheme.typography.headlineSmall,
                    color = AppTextPrimary,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Take a photo or pick an image of your receipt.\nAI will extract all items, taxes, and service charges.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AppTextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(24.dp))

                if (isScanning) {
                    CircularProgressIndicator(color = AppVioletLight, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Scanning receipt...", color = AppTextSecondary, fontSize = 14.sp)
                } else {
                    Button(
                        onClick = onPickImage,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppVioletLight),
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scan Receipt", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // Saved splits history
        if (savedSplits.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Previous Splits",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
            }

            items(savedSplits.size) { index ->
                val saved = savedSplits[index]
                val dateStr = remember(saved.timestamp) {
                    java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                        .format(java.util.Date(saved.timestamp))
                }
                val peopleNames = saved.people.map { it.name }.joinToString(", ")

                Card(
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable { onOpenSaved(saved) }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AppVioletLight.copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Receipt, null, tint = AppVioletLight, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                saved.receipt.merchant.ifBlank { "Receipt" },
                                fontWeight = FontWeight.SemiBold,
                                color = AppTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${saved.receipt.currency} ${currencyFormat.format(saved.receipt.total)} · ${saved.people.size} people",
                                color = AppTextSecondary,
                                fontSize = 13.sp
                            )
                            Text(
                                dateStr,
                                color = AppTextMuted,
                                fontSize = 12.sp
                            )
                        }
                        IconButton(
                            onClick = { deleteConfirm = saved.id },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(Icons.Default.Delete, "Delete", tint = AppTextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Step 1: Review Items ─────────────────────────────────────────────────────

@Composable
private fun ReviewItemsStep(viewModel: HomeViewModel, onNext: () -> Unit) {
    val receipt by viewModel.splitReceipt
    val r = receipt ?: return

    var editingIndex by remember { mutableStateOf(-1) }
    var editName by remember { mutableStateOf("") }
    var editPrice by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Verify the items below. Tap to edit if anything is wrong.",
                    color = AppTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            itemsIndexed(r.items) { index, item ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (editingIndex == index) {
                        // Edit mode
                        Column(modifier = Modifier.padding(12.dp)) {
                            OutlinedTextField(
                                value = editName,
                                onValueChange = { editName = it },
                                label = { Text("Item name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppVioletLight,
                                    focusedTextColor = AppTextPrimary,
                                    unfocusedTextColor = AppTextPrimary
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editPrice,
                                onValueChange = { editPrice = it },
                                label = { Text("Price") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = AppVioletLight,
                                    focusedTextColor = AppTextPrimary,
                                    unfocusedTextColor = AppTextPrimary
                                )
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { editingIndex = -1 }) {
                                    Text("Cancel", color = AppTextSecondary)
                                }
                                Spacer(Modifier.width(8.dp))
                                TextButton(onClick = {
                                    viewModel.editSplitReceiptItem(index, editName, editPrice.toDoubleOrNull() ?: item.totalPrice)
                                    editingIndex = -1
                                }) {
                                    Text("Save", color = AppVioletLight, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Display mode
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    editingIndex = index
                                    editName = item.name
                                    editPrice = item.totalPrice.toString()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    color = AppTextPrimary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.quantity > 1) {
                                    Text(
                                        "${item.quantity} x ${currencyFormat.format(item.unitPrice)}",
                                        color = AppTextSecondary,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                currencyFormat.format(item.totalPrice),
                                color = AppTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            // Extras
            item {
                Spacer(Modifier.height(8.dp))
                ExtrasCard(viewModel)
            }

            // Total
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppVioletLight.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Total", fontWeight = FontWeight.Bold, color = AppTextPrimary, fontSize = 16.sp)
                        Text(
                            "${r.currency} ${currencyFormat.format(r.total)}",
                            fontWeight = FontWeight.Bold,
                            color = AppVioletLight,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }

        // Next button
        Button(
            onClick = onNext,
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppVioletLight),
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)
        ) {
            Text("Next: Assign to Friends", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ExtrasCard(viewModel: HomeViewModel) {
    val receipt by viewModel.splitReceipt
    val r = receipt ?: return

    Card(
        colors = CardDefaults.cardColors(containerColor = AppSurface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (r.subtotal > 0) ExtraRow("Subtotal", r.subtotal)
            if (r.tax > 0 || r.taxPercent > 0) {
                val taxLabel = if (r.taxPercent > 0) "Tax (${r.taxPercent.toInt()}%)" else "Tax"
                val taxAmount = if (r.tax > 0) r.tax else if (r.taxPercent > 0 && r.subtotal > 0) r.subtotal * r.taxPercent / 100.0 else 0.0
                ExtraRow(taxLabel, taxAmount)
            }
            if (r.serviceCharge > 0 || r.servicePercent > 0) {
                val svcLabel = if (r.servicePercent > 0) "Service (${r.servicePercent.toInt()}%)" else "Service Charge"
                val svcAmount = if (r.serviceCharge > 0) r.serviceCharge else if (r.servicePercent > 0 && r.subtotal > 0) r.subtotal * r.servicePercent / 100.0 else 0.0
                ExtraRow(svcLabel, svcAmount)
            }
            if (r.discount > 0) ExtraRow("Discount", -r.discount, isDiscount = true)
        }
    }
}

@Composable
private fun ExtraRow(label: String, amount: Double, isDiscount: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = AppTextSecondary, fontSize = 14.sp)
        Text(
            currencyFormat.format(amount),
            color = if (isDiscount) AppGreen else AppTextSecondary,
            fontSize = 14.sp
        )
    }
}

// ── Step 2: Assign Items ─────────────────────────────────────────────────────

@Composable
private fun AssignStep(viewModel: HomeViewModel, onNext: () -> Unit) {
    val receipt by viewModel.splitReceipt
    val r = receipt ?: return
    val people by viewModel.splitPeople
    val assignments by viewModel.splitAssignments
    val context = LocalContext.current

    var showAddPerson by remember { mutableStateOf(false) }
    var newPersonName by remember { mutableStateOf("") }
    var newPersonPhone by remember { mutableStateOf("") }

    // Track whether to launch contact picker after permission granted
    var launchContactPicker by remember { mutableStateOf(false) }

    // Contact picker launcher
    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val contentResolver = context.contentResolver
                // Get display name
                var contactName = ""
                var contactPhone: String? = null
                contentResolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts._ID), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        contactName = cursor.getString(0) ?: ""
                        val contactId = cursor.getString(1)
                        // Get phone number
                        contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                            arrayOf(contactId),
                            null
                        )?.use { phoneCursor ->
                            if (phoneCursor.moveToFirst()) {
                                contactPhone = phoneCursor.getString(0)
                            }
                        }
                    }
                }
                if (contactName.isNotBlank()) {
                    viewModel.addSplitPerson(contactName, contactPhone)
                }
            } catch (_: Exception) { }
        }
    }

    // Permission launcher (defined after contactPickerLauncher so it can reference it)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            (context as? MainActivity)?.markExpectingReturn()
            contactPickerLauncher.launch(null)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // People chips bar
        Surface(color = AppSurface, tonalElevation = 2.dp) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("People", fontWeight = FontWeight.Bold, color = AppTextPrimary, modifier = Modifier.weight(1f))
                    TextButton(onClick = { viewModel.splitEvenlyAll() }) {
                        Text("Split All Evenly", color = AppVioletLight, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    people.forEachIndexed { idx, person ->
                        val color = androidx.compose.ui.graphics.Color(personColors[idx % personColors.size])
                        AssistChip(
                            onClick = { },
                            label = { Text(person.name, fontSize = 13.sp) },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier.size(8.dp).clip(CircleShape).background(color)
                                )
                            },
                            trailingIcon = {
                                if (people.size > 1) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier.size(14.dp).clickable { viewModel.removeSplitPerson(person.id) },
                                        tint = AppTextSecondary
                                    )
                                }
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = color.copy(alpha = 0.1f),
                                labelColor = AppTextPrimary
                            )
                        )
                    }
                    // Add person manually
                    AssistChip(
                        onClick = { showAddPerson = true },
                        label = { Text("Add", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.PersonAdd, null, Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = AppVioletLight.copy(alpha = 0.1f),
                            labelColor = AppVioletLight
                        )
                    )
                    // Add from contacts
                    AssistChip(
                        onClick = {
                            val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
                                context, android.Manifest.permission.READ_CONTACTS
                            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            if (hasPermission) {
                                (context as? MainActivity)?.markExpectingReturn()
                                contactPickerLauncher.launch(null)
                            } else {
                                permissionLauncher.launch(android.Manifest.permission.READ_CONTACTS)
                            }
                        },
                        label = { Text("Contacts", fontSize = 13.sp) },
                        leadingIcon = { Icon(Icons.Default.Contacts, null, Modifier.size(16.dp)) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = AppGreen.copy(alpha = 0.1f),
                            labelColor = AppGreen
                        )
                    )
                }
            }
        }

        // Add person dialog
        if (showAddPerson) {
            AlertDialog(
                onDismissRequest = { showAddPerson = false; newPersonName = ""; newPersonPhone = "" },
                title = { Text("Add Person", color = AppTextPrimary) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newPersonName,
                            onValueChange = { newPersonName = it },
                            placeholder = { Text("Name") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppVioletLight,
                                focusedTextColor = AppTextPrimary,
                                unfocusedTextColor = AppTextPrimary
                            )
                        )
                        OutlinedTextField(
                            value = newPersonPhone,
                            onValueChange = { newPersonPhone = it },
                            placeholder = { Text("Phone (for WhatsApp)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AppVioletLight,
                                focusedTextColor = AppTextPrimary,
                                unfocusedTextColor = AppTextPrimary
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.addSplitPerson(newPersonName, newPersonPhone.ifBlank { null })
                        newPersonName = ""
                        newPersonPhone = ""
                        showAddPerson = false
                    }) { Text("Add", color = AppVioletLight) }
                },
                dismissButton = {
                    TextButton(onClick = { showAddPerson = false; newPersonName = ""; newPersonPhone = "" }) {
                        Text("Cancel", color = AppTextSecondary)
                    }
                },
                containerColor = AppSurface
            )
        }

        // Item list with assignment toggles
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Tap a person's color to assign them to an item. Items can be shared.",
                    color = AppTextSecondary,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            itemsIndexed(r.items) { index, item ->
                val assignedIds = assignments[index] ?: emptyList()
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppSurface),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    color = AppTextPrimary,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (assignedIds.isNotEmpty()) {
                                    val names = people.filter { it.id in assignedIds }.map { it.name }
                                    val shareEach = item.totalPrice / assignedIds.size
                                    Text(
                                        "${names.joinToString(", ")} (${currencyFormat.format(shareEach)} each)",
                                        color = AppTextSecondary,
                                        fontSize = 12.sp
                                    )
                                } else {
                                    Text("Not assigned", color = AppAmber, fontSize = 12.sp)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                currencyFormat.format(item.totalPrice),
                                color = AppTextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        // Person toggle buttons
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            people.forEachIndexed { idx, person ->
                                val color = androidx.compose.ui.graphics.Color(personColors[idx % personColors.size])
                                val isAssigned = person.id in assignedIds
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(if (isAssigned) color else color.copy(alpha = 0.1f))
                                        .then(
                                            if (!isAssigned) Modifier.border(1.dp, color.copy(alpha = 0.4f), CircleShape)
                                            else Modifier
                                        )
                                        .clickable { viewModel.toggleItemAssignment(index, person.id) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        person.name.take(2).uppercase(),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAssigned) androidx.compose.ui.graphics.Color.White
                                               else color
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Unassigned warning + Next
        val unassignedCount = r.items.indices.count { (assignments[it] ?: emptyList()).isEmpty() }
        Column(modifier = Modifier.padding(16.dp)) {
            AnimatedVisibility(visible = unassignedCount > 0) {
                Text(
                    "$unassignedCount item${if (unassignedCount != 1) "s" else ""} not assigned",
                    color = AppAmber,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppVioletLight),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text("View Split Summary", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

// ── Step 3: Summary ──────────────────────────────────────────────────────────

@Composable
private fun SummaryStep(
    viewModel: HomeViewModel,
    isViewingSaved: Boolean = false,
    onDone: () -> Unit,
    onRescan: () -> Unit
) {
    val receipt by viewModel.splitReceipt
    val r = receipt ?: return
    val people by viewModel.splitPeople
    val whatsAppSent by viewModel.whatsAppSent
    val context = LocalContext.current
    val totals = remember(viewModel.splitAssignments.value, viewModel.splitReceipt.value, viewModel.splitPeople.value) {
        viewModel.calculateSplit()
    }

    // Count how many non-"Me" people have been sent
    val sendablePeople = totals.filter { it.person.name != "Me" && it.grandTotal > 0 }
    val allSent = sendablePeople.isNotEmpty() && sendablePeople.all { it.person.id in whatsAppSent }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = AppVioletLight.copy(alpha = 0.1f)),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            r.merchant.ifBlank { "Receipt" },
                            fontWeight = FontWeight.Bold,
                            color = AppTextPrimary,
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Total: ${r.currency} ${currencyFormat.format(r.total)}",
                            color = AppVioletLight,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        if (r.tax > 0 || r.taxPercent > 0 || r.serviceCharge > 0 || r.servicePercent > 0) {
                            Spacer(Modifier.height(4.dp))
                            val extras = buildList {
                                if (r.tax > 0 || r.taxPercent > 0) {
                                    val pct = if (r.taxPercent > 0) " (${r.taxPercent.toInt()}%)" else ""
                                    val amt = if (r.tax > 0) r.tax else r.subtotal * r.taxPercent / 100.0
                                    add("Tax$pct: ${currencyFormat.format(amt)}")
                                }
                                if (r.serviceCharge > 0 || r.servicePercent > 0) {
                                    val pct = if (r.servicePercent > 0) " (${r.servicePercent.toInt()}%)" else ""
                                    val amt = if (r.serviceCharge > 0) r.serviceCharge else r.subtotal * r.servicePercent / 100.0
                                    add("Service$pct: ${currencyFormat.format(amt)}")
                                }
                                if (r.discount > 0) add("Discount: -${currencyFormat.format(r.discount)}")
                            }.joinToString(" | ")
                            Text(extras, color = AppTextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Per-person breakdown
            totals.forEachIndexed { idx, pt ->
                item(key = pt.person.id) {
                    val color = androidx.compose.ui.graphics.Color(personColors[idx % personColors.size])
                    val isSent = pt.person.id in whatsAppSent
                    Card(
                        colors = CardDefaults.cardColors(containerColor = AppSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(color),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSent) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Sent",
                                            tint = androidx.compose.ui.graphics.Color.White,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        Text(
                                            pt.person.name.take(2).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = androidx.compose.ui.graphics.Color.White,
                                            fontSize = 14.sp
                                        )
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(pt.person.name, fontWeight = FontWeight.Bold, color = AppTextPrimary)
                                        if (isSent) {
                                            Spacer(Modifier.width(6.dp))
                                            Icon(
                                                Icons.Default.CheckCircle,
                                                contentDescription = "Sent",
                                                tint = AppGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    // Show assigned items
                                    val currentAssignments = viewModel.splitAssignments.value
                                    val myItems = r.items.indices.filter { i ->
                                        pt.person.id in (currentAssignments[i] ?: emptyList())
                                    }.map { r.items[it].name }
                                    if (myItems.isNotEmpty()) {
                                        Text(
                                            myItems.joinToString(", "),
                                            color = AppTextSecondary,
                                            fontSize = 12.sp,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                Text(
                                    "${r.currency} ${currencyFormat.format(pt.grandTotal)}",
                                    fontWeight = FontWeight.Bold,
                                    color = color,
                                    fontSize = 18.sp
                                )
                            }

                            Spacer(Modifier.height(8.dp))
                            HorizontalDivider(color = AppTextMuted.copy(alpha = 0.2f))
                            Spacer(Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth()) {
                                BreakdownLabel("Items", pt.itemsTotal, Modifier.weight(1f))
                                if (pt.taxShare > 0) BreakdownLabel("Tax", pt.taxShare, Modifier.weight(1f))
                                if (pt.serviceShare > 0) BreakdownLabel("Service", pt.serviceShare, Modifier.weight(1f))
                                if (pt.discountShare > 0) BreakdownLabel("Discount", -pt.discountShare, Modifier.weight(1f))
                            }

                            // WhatsApp send button (skip for "Me")
                            if (pt.person.name != "Me" && pt.grandTotal > 0) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    if (isSent) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, null, tint = AppGreen, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Sent via WhatsApp", color = AppGreen, fontSize = 13.sp)
                                        }
                                    } else {
                                        OutlinedButton(
                                            onClick = {
                                                val message = viewModel.buildSplitMessage(pt)
                                                sendWhatsApp(context, pt.person.phone, message)
                                                viewModel.markWhatsAppSent(pt.person.id)
                                            },
                                            shape = RoundedCornerShape(20.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(
                                                contentColor = androidx.compose.ui.graphics.Color(0xFF25D366)
                                            ),
                                            border = androidx.compose.foundation.BorderStroke(
                                                1.dp,
                                                androidx.compose.ui.graphics.Color(0xFF25D366).copy(alpha = 0.5f)
                                            ),
                                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Share,
                                                null,
                                                modifier = Modifier.size(16.dp),
                                                tint = androidx.compose.ui.graphics.Color(0xFF25D366)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                "Send via WhatsApp",
                                                fontSize = 13.sp,
                                                color = androidx.compose.ui.graphics.Color(0xFF25D366)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bottom actions
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Send all via WhatsApp
            if (sendablePeople.isNotEmpty() && !allSent) {
                OutlinedButton(
                    onClick = {
                        totals.forEach { pt ->
                            if (pt.person.name != "Me" && pt.grandTotal > 0 && pt.person.id !in whatsAppSent) {
                                val message = viewModel.buildSplitMessage(pt)
                                sendWhatsApp(context, pt.person.phone, message)
                                viewModel.markWhatsAppSent(pt.person.id)
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = androidx.compose.ui.graphics.Color(0xFF25D366)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        androidx.compose.ui.graphics.Color(0xFF25D366).copy(alpha = 0.5f)
                    )
                ) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color(0xFF25D366))
                    Spacer(Modifier.width(8.dp))
                    Text("Send All via WhatsApp", fontWeight = FontWeight.SemiBold, color = androidx.compose.ui.graphics.Color(0xFF25D366))
                }
            }

            if (allSent) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = AppGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("All messages sent!", color = AppGreen, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onRescan,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = AppTextSecondary)
                ) {
                    Icon(Icons.Default.CameraAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New Scan")
                }
                Button(
                    onClick = onDone,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppVioletLight),
                    modifier = Modifier.weight(1f).height(52.dp)
                ) {
                    Icon(
                        if (isViewingSaved) Icons.Default.Close else Icons.Default.Save,
                        null, Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isViewingSaved) "Close" else "Save & Done",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun sendWhatsApp(context: android.content.Context, phone: String?, message: String) {
    (context as? MainActivity)?.markExpectingReturn()
    try {
        val intent = if (phone != null) {
            // Direct message to specific number
            val cleanPhone = phone.replace(Regex("[^+\\d]"), "")
            Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$cleanPhone?text=${Uri.encode(message)}"))
        } else {
            // Open WhatsApp share sheet to pick contact
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                `package` = "com.whatsapp"
                putExtra(Intent.EXTRA_TEXT, message)
            }
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        // If WhatsApp isn't installed, fall back to generic share
        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Send split to ${phone ?: "friend"}"))
        } catch (_: Exception) { }
    }
}

@Composable
private fun BreakdownLabel(label: String, amount: Double, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = AppTextSecondary, fontSize = 11.sp)
        Text(
            currencyFormat.format(amount),
            color = if (amount < 0) AppGreen else AppTextPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
