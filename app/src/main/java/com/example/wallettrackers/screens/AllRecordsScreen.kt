package com.example.wallettrackers.screens

import android.content.Context
import android.content.ContentValues
import android.graphics.Paint as AndroidPaint
import android.graphics.pdf.PdfDocument
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.viewmodel.HomeViewModel
import com.example.wallettrackers.components.RecordCard
import com.example.wallettrackers.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

private fun exportToPdf(context: Context, records: List<Record>): Boolean {
    return try {
        val doc = PdfDocument()
        val paint = AndroidPaint().apply { isAntiAlias = true }
        val pageW = 595; val pageH = 842
        var pageNum = 1
        var pageObj = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        var canvas = pageObj.canvas
        var y = 60f

        fun ensurePage() {
            if (y > 810f) {
                doc.finishPage(pageObj)
                pageNum++
                pageObj = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = pageObj.canvas
                y = 40f
            }
        }

        val headerFmt = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
        val rowFmt = SimpleDateFormat("dd/MM", Locale.getDefault())
        val fileNameFmt = SimpleDateFormat("yyyy-MM", Locale.getDefault())
        val totalIncome = records.filter { it.type == "Income" }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        val totalExpense = records.filter { it.type == "Expense" }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        val net = totalIncome - totalExpense
        val dateLabel = if (records.isEmpty()) "All Records" else {
            val first = records.minOf { it.timestamp }
            val last = records.maxOf { it.timestamp }
            if (headerFmt.format(first) == headerFmt.format(last)) headerFmt.format(first)
            else "${headerFmt.format(first)} – ${headerFmt.format(last)}"
        }

        // Title
        paint.textSize = 20f; paint.color = android.graphics.Color.parseColor("#4F46E5"); paint.isFakeBoldText = true
        canvas.drawText("Wallet Statement", 40f, y, paint); y += 24f
        paint.textSize = 10f; paint.color = android.graphics.Color.parseColor("#64748B"); paint.isFakeBoldText = false
        canvas.drawText(dateLabel, 40f, y, paint); y += 18f
        paint.color = android.graphics.Color.parseColor("#CBD5E1")
        canvas.drawLine(40f, y, 555f, y, paint); y += 14f

        // Summary
        paint.textSize = 10f; paint.isFakeBoldText = true
        paint.color = android.graphics.Color.parseColor("#22C55E")
        canvas.drawText("Income: +${"%,.0f".format(totalIncome)}", 40f, y, paint)
        paint.color = android.graphics.Color.parseColor("#EF4444")
        canvas.drawText("Expense: -${"%,.0f".format(totalExpense)}", 220f, y, paint)
        paint.color = if (net >= 0) android.graphics.Color.parseColor("#22C55E") else android.graphics.Color.parseColor("#EF4444")
        canvas.drawText("Net: ${if (net >= 0) "+" else ""}${"%,.0f".format(net)}", 410f, y, paint)
        y += 16f
        paint.isFakeBoldText = false
        paint.color = android.graphics.Color.parseColor("#CBD5E1")
        canvas.drawLine(40f, y, 555f, y, paint); y += 12f

        // Column headers
        paint.textSize = 9f; paint.color = android.graphics.Color.parseColor("#64748B"); paint.isFakeBoldText = true
        canvas.drawText("Date", 40f, y, paint)
        canvas.drawText("Category", 105f, y, paint)
        canvas.drawText("Account", 225f, y, paint)
        canvas.drawText("Comment", 330f, y, paint)
        canvas.drawText("Amount", 462f, y, paint)
        y += 14f; paint.isFakeBoldText = false

        // Records
        records.forEach { record ->
            ensurePage()
            val isIncome = record.type == "Income"
            paint.textSize = 8.5f; paint.color = android.graphics.Color.parseColor("#1E293B")
            canvas.drawText(rowFmt.format(record.timestamp), 40f, y, paint)
            canvas.drawText(record.category.take(15), 105f, y, paint)
            canvas.drawText(record.accountName.take(14), 225f, y, paint)
            canvas.drawText(record.comment.take(16), 330f, y, paint)
            paint.color = if (isIncome) android.graphics.Color.parseColor("#22C55E") else android.graphics.Color.parseColor("#EF4444")
            paint.isFakeBoldText = true
            canvas.drawText("${if (isIncome) "+" else "-"}${record.amount} ${record.currency}", 455f, y, paint)
            paint.isFakeBoldText = false
            y += 13f
        }
        doc.finishPage(pageObj)

        val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "wallet_statement_${fileNameFmt.format(Date())}.pdf")
            put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return false
        resolver.openOutputStream(uri)?.use { doc.writeTo(it) }
        cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, cv, null, null)
        doc.close()
        true
    } catch (_: Exception) { false }
}

enum class FilterType {
    DAY, WEEK, MONTH, YEAR
}

private fun getDateLabel(date: Date): String {
    val cal = Calendar.getInstance().apply { time = date }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) -> "Today"
        cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR) -> "Yesterday"
        cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) ->
            SimpleDateFormat("d MMMM", Locale.getDefault()).format(date)
        else ->
            SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(date)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AllRecordsScreen(
    viewModel: HomeViewModel,
    onCategoryClick: () -> Unit,
    onSaveAsRule: (Record) -> Unit = {},
    onBack: () -> Unit
) {
    val records by viewModel.records
    val accounts by viewModel.accounts
    val unusualRecordIds by viewModel.unusualRecordIds
    val fxRates by viewModel.fxRates
    val context = LocalContext.current

    var selectedFilter by remember { mutableStateOf<FilterType?>(null) }
    var selectedAccountFilter by remember { mutableStateOf<String?>(null) }
    var selectedCategoryFilter by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var filterMinAmount by remember { mutableStateOf("") }
    var filterMaxAmount by remember { mutableStateOf("") }

    var showFilterDialog by remember { mutableStateOf(false) }
    var showRecordOptionsDialog by remember { mutableStateOf(false) }
    var showDeleteRecordDialog by remember { mutableStateOf(false) }
    var receiptViewUrl by remember { mutableStateOf<String?>(null) }
    var pendingReceiptRecord by remember { mutableStateOf<Record?>(null) }

    val editingRecord by viewModel.editingRecord
    val showEditRecordDialog by viewModel.showEditDialog
    var optionSelectedRecord by remember { mutableStateOf<Record?>(null) }

    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val record = pendingReceiptRecord
        if (uri != null && record != null) {
            viewModel.attachReceiptToRecord(record, uri)
        }
        pendingReceiptRecord = null
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var pendingDeleteRecord by remember { mutableStateOf<Record?>(null) }

    val filteredRecords = remember(selectedFilter, selectedAccountFilter, selectedCategoryFilter, searchQuery, filterMinAmount, filterMaxAmount, records, pendingDeleteRecord) {
        records.filter { record ->
            record.id != pendingDeleteRecord?.id
        }.filter { record ->
            val timeMatch = if (selectedFilter == null) true else {
                val calendar = Calendar.getInstance()
                val now = calendar.time
                val recordDate = record.timestamp
                when (selectedFilter) {
                    FilterType.DAY -> { calendar.time = now; calendar.add(Calendar.DAY_OF_YEAR, -1); recordDate.after(calendar.time) }
                    FilterType.WEEK -> { calendar.time = now; calendar.add(Calendar.WEEK_OF_YEAR, -1); recordDate.after(calendar.time) }
                    FilterType.MONTH -> { calendar.time = now; calendar.add(Calendar.MONTH, -1); recordDate.after(calendar.time) }
                    FilterType.YEAR -> { calendar.time = now; calendar.add(Calendar.YEAR, -1); recordDate.after(calendar.time) }
                    else -> true
                }
            }
            val accountMatch = selectedAccountFilter == null || record.accountName == selectedAccountFilter
            val categoryMatch = selectedCategoryFilter == null || record.category == selectedCategoryFilter
            val searchMatch = searchQuery.isBlank() || listOf(record.category, record.accountName, record.comment)
                .any { it.contains(searchQuery, ignoreCase = true) }
            val amt = record.amount.toDoubleOrNull() ?: 0.0
            val minMatch = filterMinAmount.isBlank() || amt >= (filterMinAmount.toDoubleOrNull() ?: 0.0)
            val maxMatch = filterMaxAmount.isBlank() || amt <= (filterMaxAmount.toDoubleOrNull() ?: Double.MAX_VALUE)
            timeMatch && accountMatch && categoryMatch && searchMatch && minMatch && maxMatch
        }
    }

    val groupedRecords = remember(filteredRecords) {
        filteredRecords.groupBy { getDateLabel(it.timestamp) }.entries.toList()
    }

    if (showFilterDialog) {
        FilterDialog(
            accounts = accounts,
            currentAccount = selectedAccountFilter,
            currentCategory = selectedCategoryFilter,
            currentMinAmount = filterMinAmount,
            currentMaxAmount = filterMaxAmount,
            onDismiss = { showFilterDialog = false },
            onApply = { acc, cat, min, max ->
                selectedAccountFilter = acc; selectedCategoryFilter = cat
                filterMinAmount = min; filterMaxAmount = max
                showFilterDialog = false
            }
        )
    }

    receiptViewUrl?.let { url ->
        Dialog(onDismissRequest = { receiptViewUrl = null }) {
            Card(shape = RoundedCornerShape(16.dp)) {
                AsyncImage(
                    model = url,
                    contentDescription = "Receipt",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f),
                    contentScale = ContentScale.Fit
                )
            }
        }
    }

    if (showRecordOptionsDialog && optionSelectedRecord != null) {
        OptionsDialog(
            onDismiss = { showRecordOptionsDialog = false },
            onEdit = { showRecordOptionsDialog = false; viewModel.startEditing(optionSelectedRecord!!) },
            onSaveAsRule = {
                showRecordOptionsDialog = false
                onSaveAsRule(optionSelectedRecord!!)
            },
            onDelete = { showRecordOptionsDialog = false; showDeleteRecordDialog = true },
            onAttachReceipt = {
                showRecordOptionsDialog = false
                pendingReceiptRecord = optionSelectedRecord
                pickImageLauncher.launch("image/*")
            }
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
            onCategoryClick = onCategoryClick,
            title = "Edit Record",
            confirmButtonText = "Update"
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("All Records", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val csv = viewModel.exportToCsvString(filteredRecords)
                        val cv = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "wallet_records_export.csv")
                            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                            put(MediaStore.Downloads.IS_PENDING, 1)
                        }
                        val resolver = context.contentResolver
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                            cv.clear()
                            cv.put(MediaStore.Downloads.IS_PENDING, 0)
                            resolver.update(uri, cv, null, null)
                            Toast.makeText(context, "Saved to Downloads: wallet_records_export.csv", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Export failed", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.Download, contentDescription = "Export CSV", tint = AppTextPrimary)
                    }
                    IconButton(onClick = {
                        val ok = exportToPdf(context, filteredRecords)
                        Toast.makeText(
                            context,
                            if (ok) "PDF saved to Downloads" else "PDF export failed",
                            Toast.LENGTH_LONG
                        ).show()
                    }) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "Export PDF", tint = AppTextPrimary)
                    }
                    if (selectedAccountFilter != null || selectedCategoryFilter != null) {
                        IconButton(onClick = { selectedAccountFilter = null; selectedCategoryFilter = null }) {
                            Icon(Icons.Default.FilterListOff, contentDescription = "Clear Filters",
                                tint = AppRed)
                        }
                    }
                    IconButton(onClick = { showFilterDialog = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = "Filter", tint = AppTextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground
                )
            )
        },
        bottomBar = {
            NavigationBar(containerColor = AppSurface) {
                listOf(
                    FilterType.DAY to "Day",
                    FilterType.WEEK to "Week",
                    FilterType.MONTH to "Month",
                    FilterType.YEAR to "Year"
                ).forEach { (filter, label) ->
                    NavigationBarItem(
                        icon = { Icon(Icons.Filled.DateRange, contentDescription = label) },
                        label = { Text(label) },
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = if (selectedFilter == filter) null else filter },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppVioletLight,
                            selectedTextColor = AppVioletLight,
                            unselectedIconColor = AppTextSecondary,
                            unselectedTextColor = AppTextSecondary,
                            indicatorColor = AppPrimary.copy(alpha = 0.2f)
                        )
                    )
                }
            }
        },
        containerColor = AppBackground
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search category, account, comment...", color = AppTextSecondary) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = AppTextSecondary) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", tint = AppTextSecondary)
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppTextPrimary,
                    unfocusedTextColor = AppTextPrimary,
                    focusedContainerColor = AppSurface,
                    unfocusedContainerColor = AppSurface,
                    focusedBorderColor = AppVioletLight,
                    unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                    cursorColor = AppVioletLight
                )
            )

            // Active filter chips
            if (selectedAccountFilter != null || selectedCategoryFilter != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedAccountFilter?.let {
                        FilterChip(
                            selected = true,
                            onClick = { selectedAccountFilter = null },
                            label = { Text(it, color = AppVioletLight) },
                            trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = AppVioletLight) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppPrimary.copy(alpha = 0.2f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = true, borderColor = AppVioletLight)
                        )
                    }
                    selectedCategoryFilter?.let {
                        FilterChip(
                            selected = true,
                            onClick = { selectedCategoryFilter = null },
                            label = { Text(it, color = AppVioletLight) },
                            trailingIcon = { Icon(Icons.Default.Close, null, modifier = Modifier.size(16.dp), tint = AppVioletLight) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AppPrimary.copy(alpha = 0.2f)
                            ),
                            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = true, borderColor = AppVioletLight)
                        )
                    }
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (filteredRecords.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Default.SearchOff,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = AppTextSecondary
                                )
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "No records found",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = AppTextPrimary
                                )
                                Text(
                                    "Try adjusting your filters",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppTextSecondary
                                )
                            }
                        }
                    }
                } else {
                    groupedRecords.forEach { (dateLabel, dayRecords) ->
                        stickyHeader(key = dateLabel) {
                            Surface(
                                color = AppBackground,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                                            Text(
                                                text = dateLabel,
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color = AppTextPrimary
                                            )
                                        }
                                        val uniqueCurrencies = dayRecords.map { it.currency }.distinct()
                                        if (uniqueCurrencies.size == 1) {
                                            val dayNet = dayRecords.sumOf {
                                                if (it.type == "Income") it.amount.toDoubleOrNull() ?: 0.0
                                                else -(it.amount.toDoubleOrNull() ?: 0.0)
                                            }
                                            Text(
                                                text = "${if (dayNet >= 0) "+" else ""}${
                                                    String.format(Locale.getDefault(), "%.2f", dayNet)
                                                } ${uniqueCurrencies.first()}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (dayNet >= 0) AppGreen else AppRed
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(dayRecords, key = { it.id }) { record ->
                            val dismissState = rememberSwipeToDismissBoxState(
                                confirmValueChange = { value ->
                                    when (value) {
                                        SwipeToDismissBoxValue.StartToEnd -> {
                                            viewModel.startEditing(record)
                                            false
                                        }
                                        SwipeToDismissBoxValue.EndToStart -> {
                                            pendingDeleteRecord = record
                                            scope.launch {
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Deleted: ${record.category}",
                                                    actionLabel = "Undo",
                                                    duration = SnackbarDuration.Short
                                                )
                                                when (result) {
                                                    SnackbarResult.ActionPerformed -> pendingDeleteRecord = null
                                                    SnackbarResult.Dismissed -> {
                                                        pendingDeleteRecord?.let { viewModel.deleteRecord(it.id) }
                                                        pendingDeleteRecord = null
                                                    }
                                                }
                                            }
                                            false
                                        }
                                        else -> false
                                    }
                                }
                            )
                            SwipeToDismissBox(
                                state = dismissState,
                                backgroundContent = {
                                    val isEdit = dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd
                                    Box(
                                        Modifier.fillMaxSize()
                                            .background(if (isEdit) AppGreen.copy(alpha = 0.2f) else AppRed.copy(alpha = 0.2f))
                                            .padding(start = 24.dp, end = 24.dp),
                                        contentAlignment = if (isEdit) Alignment.CenterStart else Alignment.CenterEnd
                                    ) {
                                        Icon(
                                            if (isEdit) Icons.Default.Edit else Icons.Default.Delete,
                                            null,
                                            tint = if (isEdit) AppGreen else AppRed
                                        )
                                    }
                                },
                                enableDismissFromStartToEnd = true
                            ) {
                                RecordCard(
                                    record = record,
                                    onLongClick = {
                                        optionSelectedRecord = record
                                        showRecordOptionsDialog = true
                                    },
                                    isUnusual = record.id in unusualRecordIds,
                                    fxRates = fxRates,
                                    onReceiptClick = if (record.receiptUrl.isNotEmpty()) {
                                        { receiptViewUrl = record.receiptUrl }
                                    } else null
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterDialog(
    accounts: List<Account>,
    currentAccount: String?,
    currentCategory: String?,
    currentMinAmount: String = "",
    currentMaxAmount: String = "",
    onDismiss: () -> Unit,
    onApply: (account: String?, category: String?, minAmount: String, maxAmount: String) -> Unit
) {
    var selectedAccount by remember { mutableStateOf(currentAccount) }
    var selectedCategory by remember { mutableStateOf(currentCategory) }
    var minAmount by remember { mutableStateOf(currentMinAmount) }
    var maxAmount by remember { mutableStateOf(currentMaxAmount) }
    var accExpanded by remember { mutableStateOf(false) }
    var catExpanded by remember { mutableStateOf(false) }

    val allCategories = remember {
        Categories.list.flatMap { listOf(it.name) + it.subCategories.map { sub -> sub.name } }.distinct()
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = AppPrimary,
        unfocusedBorderColor = AppPrimary.copy(alpha = 0.4f),
        focusedLabelColor = AppPrimary,
        unfocusedLabelColor = AppTextSecondary,
        focusedTextColor = AppTextPrimary,
        unfocusedTextColor = AppTextPrimary,
        focusedContainerColor = AppBackground,
        unfocusedContainerColor = AppBackground,
        focusedTrailingIconColor = AppPrimary,
        unfocusedTrailingIconColor = AppTextSecondary,
    )

    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(AppSurface)
                .padding(24.dp)
        ) {
            Column {
                Text("Filter Records", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold, color = AppTextPrimary)
                Spacer(Modifier.height(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ExposedDropdownMenuBox(expanded = accExpanded, onExpandedChange = { accExpanded = !accExpanded }) {
                        OutlinedTextField(
                            value = selectedAccount ?: "All Accounts",
                            onValueChange = {},
                            label = { Text("Account") },
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors
                        )
                        ExposedDropdownMenu(expanded = accExpanded, onDismissRequest = { accExpanded = false },
                            containerColor = AppSurface) {
                            DropdownMenuItem(text = { Text("All Accounts", color = AppTextPrimary) }, onClick = { selectedAccount = null; accExpanded = false })
                            accounts.forEach { account ->
                                DropdownMenuItem(
                                    text = { Text(account.name, color = AppTextPrimary) },
                                    onClick = { selectedAccount = account.name; accExpanded = false }
                                )
                            }
                        }
                    }

                    ExposedDropdownMenuBox(expanded = catExpanded, onExpandedChange = { catExpanded = !catExpanded }) {
                        OutlinedTextField(
                            value = selectedCategory ?: "All Categories",
                            onValueChange = {},
                            label = { Text("Category") },
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = catExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors
                        )
                        ExposedDropdownMenu(expanded = catExpanded, onDismissRequest = { catExpanded = false },
                            containerColor = AppSurface) {
                            DropdownMenuItem(text = { Text("All Categories", color = AppTextPrimary) }, onClick = { selectedCategory = null; catExpanded = false })
                            allCategories.forEach { cat ->
                                DropdownMenuItem(
                                    text = { Text(cat, color = AppTextPrimary) },
                                    onClick = { selectedCategory = cat; catExpanded = false }
                                )
                            }
                        }
                    }

                    // Amount range
                    Text("Amount Range", style = MaterialTheme.typography.labelMedium, color = AppTextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = minAmount,
                            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) minAmount = it },
                            label = { Text("Min") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors,
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = maxAmount,
                            onValueChange = { if (it.isEmpty() || it.toDoubleOrNull() != null) maxAmount = it },
                            label = { Text("Max") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = fieldColors,
                            singleLine = true
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.5f))
                    ) { Text("Cancel", color = AppTextPrimary) }
                    Button(
                        onClick = { onApply(selectedAccount, selectedCategory, minAmount, maxAmount) },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                    ) { Text("Apply", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
