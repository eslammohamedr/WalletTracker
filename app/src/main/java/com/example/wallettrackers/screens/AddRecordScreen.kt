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
import androidx.compose.ui.window.Dialog
import com.example.wallettrackers.components.NumberPad
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.core.content.ContextCompat
import com.example.wallettrackers.ui.theme.*
import java.io.File

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
    onAddSplitRecords: ((List<Record>) -> Unit)? = null,
    customSubCategoryNames: List<String> = emptyList(),
    onScanReceipt: ((android.graphics.Bitmap) -> Unit)? = null,
    scannedReceipt: com.example.wallettrackers.service.ExtractedTransaction? = null,
    isScanning: Boolean = false,
    onClearScannedReceipt: () -> Unit = {},
    onVoiceAudio: ((ByteArray) -> Unit)? = null,
    onVoiceText: (String) -> Unit = {},
    isVoiceProcessing: Boolean = false,
    voiceResult: com.example.wallettrackers.service.ExtractedTransaction? = null,
    onClearVoiceResult: () -> Unit = {},
    voiceNeedsDeviceFallback: Boolean = false,
    onVoiceFallbackHandled: () -> Unit = {},
    onVoiceCategoryDetected: (String) -> Unit = {},
    voiceMultiResults: List<com.example.wallettrackers.service.ExtractedTransaction> = emptyList(),
    onClearVoiceMultiResults: () -> Unit = {},
    onAddVoiceRecords: (List<Record>) -> Unit = {},
    onUpdateVoiceRecord: (Int, com.example.wallettrackers.service.ExtractedTransaction) -> Unit = { _, _ -> },
    onRemoveVoiceRecord: (Int) -> Unit = {},
    onPickVoiceCategory: (Int) -> Unit = {}
) {
    var comment by remember { mutableStateOf("") }
    var recordType by remember { mutableStateOf("Expense") }

    // Receipt scan: image picker
    val context = androidx.compose.ui.platform.LocalContext.current
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && onScanReceipt != null) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                if (bitmap != null) onScanReceipt(bitmap)
            } catch (_: Exception) { }
        }
    }

    // Auto-fill from scanned receipt
    LaunchedEffect(scannedReceipt) {
        scannedReceipt?.let { receipt ->
            if (receipt.amount.isNotBlank()) onAmountChange(receipt.amount)
            if (receipt.comment.isNotBlank()) comment = receipt.comment
            if (receipt.type.isNotBlank()) recordType = receipt.type
            onClearScannedReceipt()
        }
    }

    // ── Voice add-record (Arabic) ───────────────────────────────────────────
    var isRecording by remember { mutableStateOf(false) }
    val recorderRef = remember { mutableStateOf<MediaRecorder?>(null) }
    val audioFileRef = remember { mutableStateOf<File?>(null) }

    fun startRecording() {
        try {
            val file = File(context.cacheDir, "voice_record.m4a")
            @Suppress("DEPRECATION")
            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorderRef.value = rec
            audioFileRef.value = file
            isRecording = true
        } catch (_: Exception) {
            isRecording = false
            recorderRef.value = null
        }
    }

    fun finishRecording(send: Boolean) {
        val rec = recorderRef.value
        recorderRef.value = null
        isRecording = false
        var stoppedOk = true
        try { rec?.stop() } catch (_: Exception) { stoppedOk = false }
        try { rec?.release() } catch (_: Exception) { }
        val file = audioFileRef.value
        if (send && stoppedOk && file != null && file.exists() && file.length() > 0L) {
            val bytes = try { file.readBytes() } catch (_: Exception) { null }
            if (bytes != null) onVoiceAudio?.invoke(bytes)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startRecording() }

    val recognizerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!text.isNullOrBlank()) onVoiceText(text)
        }
    }

    fun launchDeviceRecognizer() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-EG")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "قول المبلغ والحاجة اللي صرفت عليها…")
        }
        try {
            (context as? com.example.wallettrackers.MainActivity)?.markExpectingReturn()
            recognizerLauncher.launch(intent)
        } catch (_: Exception) { }
    }

    fun onMicClick() {
        if (isRecording) { finishRecording(send = true); return }
        val granted = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startRecording()
        else audioPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
    }

    // When cloud (Whisper) transcription fails, fall back to on-device recognizer
    LaunchedEffect(voiceNeedsDeviceFallback) {
        if (voiceNeedsDeviceFallback) {
            onVoiceFallbackHandled()
            launchDeviceRecognizer()
        }
    }

    // Auto-fill from voice result. If a category was detected, set it via navigation
    // first (voiceResult stays set, so the rebuilt screen fills the rest, then clears).
    LaunchedEffect(voiceResult) {
        voiceResult?.let { r ->
            if (r.amount.isNotBlank()) onAmountChange(r.amount)
            val detected = r.category.takeIf { it.isNotBlank() }
            val currentCategory = selectedCategory ?: ""
            if (detected != null && !detected.equals(currentCategory, ignoreCase = true)) {
                onVoiceCategoryDetected(detected)
            } else {
                if (r.comment.isNotBlank()) comment = r.comment
                if (r.type.isNotBlank()) recordType = r.type
                onClearVoiceResult()
            }
        }
    }

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
                title = { Text("Add Record", fontWeight = FontWeight.Bold, color = AppTextPrimary) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = AppTextPrimary)
                    }
                },
                actions = {
                    if (onVoiceAudio != null) {
                        if (isVoiceProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                color = AppVioletLight,
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = { onMicClick() }) {
                                Icon(
                                    Icons.Default.Mic,
                                    contentDescription = "Add by voice (Arabic)",
                                    tint = if (isRecording) AppRed else AppVioletLight
                                )
                            }
                        }
                    }
                    if (onScanReceipt != null) {
                        if (isScanning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp).padding(end = 8.dp),
                                color = AppVioletLight,
                                strokeWidth = 2.dp
                            )
                        } else {
                            IconButton(onClick = {
                                (context as? com.example.wallettrackers.MainActivity)?.markExpectingReturn()
                                imagePickerLauncher.launch("image/*")
                            }) {
                                Icon(Icons.Default.CameraAlt, contentDescription = "Scan Receipt", tint = AppVioletLight)
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = AppBackground
                )
            )
        },
        containerColor = AppBackground
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
                                color = if (isSelected) Color.White else AppTextSecondary
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
                                color = if (recordType == "Income") AppGreen else AppRed,
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
                        color = AppSurface,
                        border = if (category.isEmpty()) BorderStroke(1.dp, AppPrimary.copy(alpha = 0.3f)) else null,
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
                                        .background(AppPrimary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        Icons.Default.Category,
                                        contentDescription = null,
                                        tint = AppVioletLight,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }

                            Spacer(Modifier.width(16.dp))
                            Text(
                                text = if (category.isNotBlank()) category else "Select Category",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (category.isNotBlank()) AppTextPrimary else AppTextSecondary,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = AppTextSecondary,
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
                            color = AppTextSecondary,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        if (displayedAccounts.isEmpty()) {
                            Text(
                                text = if (category == "Credit") "No credit card accounts found" else "No accounts found",
                                fontSize = 13.sp,
                                color = AppTextSecondary
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
                                            color = if (isSelected) Color.White else AppTextSecondary
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
                                color = AppTextSecondary,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(Modifier.height(8.dp))
                            if (nonCreditAccounts.isEmpty()) {
                                Text(
                                    text = "No debit accounts found",
                                    fontSize = 13.sp,
                                    color = AppTextSecondary
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
                                                color = if (isSelected) Color.White else AppTextSecondary
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
                            focusedTextColor = AppTextPrimary,
                            unfocusedTextColor = AppTextPrimary,
                            focusedContainerColor = AppSurface,
                            unfocusedContainerColor = AppSurface,
                            focusedBorderColor = AppVioletLight,
                            unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                            cursorColor = AppVioletLight,
                            focusedLabelColor = AppVioletLight,
                            unfocusedLabelColor = AppTextSecondary
                        )
                    )

                    // Split toggle (only for Expense, not Credit category)
                    if (onAddSplitRecords != null && recordType == "Expense" && category != "Credit") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (splitMode) AppPrimary.copy(alpha = 0.15f) else AppSurface)
                                .clickable { splitMode = !splitMode }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(Icons.Default.CallSplit, null, tint = if (splitMode) AppPrimaryLight else AppTextSecondary, modifier = Modifier.size(18.dp))
                            Text("Split across categories", style = MaterialTheme.typography.bodyMedium,
                                color = if (splitMode) AppPrimaryLight else AppTextSecondary, modifier = Modifier.weight(1f))
                            Switch(checked = splitMode, onCheckedChange = { splitMode = it },
                                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = AppPrimary))
                        }
                    }

                    // Split rows
                    if (splitMode) {
                        val allCategories = remember(customSubCategoryNames) {
                            (Categories.list.flatMap { c -> (c.subCategories + c).map { it.name } } + customSubCategoryNames).distinct()
                        }
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
                                                focusedTextColor = if (item.category.isEmpty()) AppTextSecondary else AppTextPrimary,
                                                unfocusedTextColor = if (item.category.isEmpty()) AppTextSecondary else AppTextPrimary,
                                                focusedContainerColor = AppSurface,
                                                unfocusedContainerColor = AppSurface,
                                                focusedBorderColor = AppVioletLight,
                                                unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                                            ),
                                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                                        )
                                        ExposedDropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                            modifier = Modifier.background(AppSurface)
                                        ) {
                                            allCategories.forEach { cat ->
                                                DropdownMenuItem(
                                                    text = { Text(cat, color = AppTextPrimary, style = MaterialTheme.typography.bodySmall) },
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
                                            focusedTextColor = AppTextPrimary, unfocusedTextColor = AppTextPrimary,
                                            focusedContainerColor = AppSurface, unfocusedContainerColor = AppSurface,
                                            focusedBorderColor = AppVioletLight, unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                                            cursorColor = AppVioletLight, focusedLabelColor = AppVioletLight, unfocusedLabelColor = AppTextSecondary
                                        )
                                    )
                                    if (splitItems.size > 2) {
                                        IconButton(onClick = { splitItems = splitItems.toMutableList().also { it.removeAt(idx) } }, modifier = Modifier.size(32.dp)) {
                                            Icon(Icons.Default.Remove, null, tint = AppRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                            TextButton(
                                onClick = { splitItems = splitItems + SplitItem("", "") },
                                modifier = Modifier.align(Alignment.End)
                            ) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp), tint = AppPrimaryLight)
                                Spacer(Modifier.width(4.dp))
                                Text("Add split", style = MaterialTheme.typography.labelMedium, color = AppPrimaryLight)
                            }
                        }
                    }
                }
            }

            // Fixed section at bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppBackground)
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
                                Brush.linearGradient(listOf(AppPrimary.copy(alpha = 0.2f), AppPrimary.copy(alpha = 0.2f)))
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
                            color = if (canSubmit) Color.White else AppTextSecondary
                        )
                    }
                }
            }
        }
    }

    // Recording dialog — shown while capturing voice
    if (isRecording) {
        Dialog(onDismissRequest = { finishRecording(send = false) }) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = AppSurface
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val pulse = rememberInfiniteTransition(label = "rec_pulse")
                    val scale by pulse.animateFloat(
                        initialValue = 1f,
                        targetValue = 1.25f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "rec_scale"
                    )
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .graphicsLayer { scaleX = scale; scaleY = scale }
                            .clip(CircleShape)
                            .background(AppRed.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = AppRed,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Listening…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "اتكلم بالعربي: قول المبلغ والحاجة اللي صرفت عليها",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { finishRecording(send = false) }) {
                            Text("Cancel", color = AppTextSecondary)
                        }
                        Button(
                            onClick = { finishRecording(send = true) },
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Done", color = Color.White)
                        }
                    }
                }
            }
        }
    }

    // Multi-record review dialog — when voice detected several transactions
    if (voiceMultiResults.isNotEmpty()) {
        Dialog(onDismissRequest = { onClearVoiceMultiResults() }) {
            Surface(shape = RoundedCornerShape(24.dp), color = AppSurface) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "Heard ${voiceMultiResults.size} ${if (voiceMultiResults.size == 1) "record" else "records"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = AppTextPrimary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Review & edit, pick an account, then add them all",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppTextSecondary
                    )
                    Spacer(Modifier.height(12.dp))

                    // Account chips
                    if (accounts.isEmpty()) {
                        Text("No accounts found", fontSize = 13.sp, color = AppTextSecondary)
                    } else {
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            accounts.forEach { acc ->
                                val isSelected = selectedAccount?.id == acc.id
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(
                                            if (isSelected) AccentGradient
                                            else Brush.linearGradient(listOf(Color(0x1A7C3AED), Color(0x1A4F46E5)))
                                        )
                                        .clickable { onAccountChange(acc) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = acc.name,
                                        fontSize = 13.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) Color.White else AppTextSecondary
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Detected records list — editable rows
                    Column(
                        modifier = Modifier
                            .heightIn(max = 340.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        voiceMultiResults.forEachIndexed { idx, r ->
                            val isIncome = r.type.equals("Income", ignoreCase = true)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AppBackground)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Category — tap to choose from the categories screen
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable { onPickVoiceCategory(idx) },
                                        shape = RoundedCornerShape(12.dp),
                                        color = AppSurface,
                                        border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(Icons.Default.Category, null, tint = AppVioletLight, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(10.dp))
                                            Text(
                                                r.category.ifBlank { "Select Category" },
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (r.category.isNotBlank()) AppTextPrimary else AppTextSecondary,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Icon(Icons.Default.ChevronRight, null, tint = AppTextSecondary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    IconButton(
                                        onClick = { onRemoveVoiceRecord(idx) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.Remove, "Remove", tint = AppRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Expense / Income toggle chip
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(10.dp))
                                            .background((if (isIncome) AppGreen else AppRed).copy(alpha = 0.15f))
                                            .clickable {
                                                onUpdateVoiceRecord(idx, r.copy(type = if (isIncome) "Expense" else "Income"))
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(
                                            if (isIncome) "Income" else "Expense",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (isIncome) AppGreen else AppRed
                                        )
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = r.amount,
                                        onValueChange = { v -> onUpdateVoiceRecord(idx, r.copy(amount = v.filter { it.isDigit() || it == '.' })) },
                                        modifier = Modifier.weight(1f),
                                        singleLine = true,
                                        label = { Text("Amount") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        shape = RoundedCornerShape(12.dp),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = AppTextPrimary, unfocusedTextColor = AppTextPrimary,
                                            focusedContainerColor = AppSurface, unfocusedContainerColor = AppSurface,
                                            focusedBorderColor = AppVioletLight, unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                                            cursorColor = AppVioletLight, focusedLabelColor = AppVioletLight, unfocusedLabelColor = AppTextSecondary
                                        )
                                    )
                                }
                                // Note (what the user said it was for)
                                OutlinedTextField(
                                    value = r.comment,
                                    onValueChange = { v -> onUpdateVoiceRecord(idx, r.copy(comment = v)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                    label = { Text("Note") },
                                    shape = RoundedCornerShape(12.dp),
                                    textStyle = MaterialTheme.typography.bodySmall,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = AppTextPrimary, unfocusedTextColor = AppTextPrimary,
                                        focusedContainerColor = AppSurface, unfocusedContainerColor = AppSurface,
                                        focusedBorderColor = AppVioletLight, unfocusedBorderColor = AppPrimary.copy(alpha = 0.3f),
                                        cursorColor = AppVioletLight, focusedLabelColor = AppVioletLight, unfocusedLabelColor = AppTextSecondary
                                    )
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(20.dp))

                    val canAddAll = selectedAccount != null &&
                        voiceMultiResults.any { (it.amount.toDoubleOrNull() ?: 0.0) > 0 }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onClearVoiceMultiResults() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", color = AppTextSecondary)
                        }
                        Button(
                            onClick = {
                                val acc = selectedAccount ?: return@Button
                                val records = voiceMultiResults
                                    .filter { (it.amount.toDoubleOrNull() ?: 0.0) > 0 }
                                    .map { r ->
                                        Record(
                                            accountId = acc.id,
                                            accountName = acc.name,
                                            category = r.category.ifBlank { "Others" },
                                            amount = r.amount,
                                            currency = acc.currency,
                                            comment = r.comment,
                                            type = if (r.type.equals("Income", ignoreCase = true)) "Income" else "Expense"
                                        )
                                    }
                                onAddVoiceRecords(records)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = canAddAll,
                            colors = ButtonDefaults.buttonColors(containerColor = AppPrimary)
                        ) {
                            Text("Add all", color = Color.White)
                        }
                    }
                }
            }
        }
    }
}
