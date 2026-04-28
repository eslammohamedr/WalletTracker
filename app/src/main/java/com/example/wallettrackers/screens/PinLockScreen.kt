package com.example.wallettrackers.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PinLockScreen(
    mode: PinLockMode,
    onSuccess: () -> Unit,
    onCancel: (() -> Unit)? = null,
    storedPin: String = "",
    onPinSet: ((String) -> Unit)? = null
) {
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var isConfirming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun handleDigit(digit: String) {
        errorMessage = ""
        if (mode == PinLockMode.SET) {
            if (!isConfirming) {
                if (pin.length < 4) pin += digit
                if (pin.length == 4) isConfirming = true
            } else {
                if (confirmPin.length < 4) confirmPin += digit
                if (confirmPin.length == 4) {
                    if (confirmPin == pin) {
                        onPinSet?.invoke(pin)
                        onSuccess()
                    } else {
                        errorMessage = "PINs don't match, try again"
                        pin = ""; confirmPin = ""; isConfirming = false
                    }
                }
            }
        } else {
            if (pin.length < 4) pin += digit
            if (pin.length == 4) {
                if (pin == storedPin) onSuccess()
                else { errorMessage = "Incorrect PIN"; pin = "" }
            }
        }
    }

    fun handleBackspace() {
        errorMessage = ""
        if (isConfirming) { if (confirmPin.isNotEmpty()) confirmPin = confirmPin.dropLast(1) }
        else { if (pin.isNotEmpty()) pin = pin.dropLast(1) }
    }

    val currentPin = if (isConfirming) confirmPin else pin
    val title = when {
        mode == PinLockMode.SET && !isConfirming -> "Set PIN"
        mode == PinLockMode.SET && isConfirming -> "Confirm PIN"
        else -> "Enter PIN"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        if (errorMessage.isNotEmpty()) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            Spacer(Modifier.height(20.dp))
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier.size(18.dp).clip(CircleShape).background(
                        if (i < currentPin.length) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
        }

        Spacer(Modifier.height(40.dp))

        val digits = listOf(
            listOf("1","2","3"),
            listOf("4","5","6"),
            listOf("7","8","9"),
            listOf("","0","⌫")
        )

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            digits.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { label ->
                        if (label == "") {
                            Spacer(Modifier.size(80.dp))
                        } else if (label == "⌫") {
                            FilledTonalIconButton(onClick = { handleBackspace() }, modifier = Modifier.size(80.dp)) {
                                Icon(Icons.Default.Backspace, null)
                            }
                        } else {
                            FilledTonalButton(
                                onClick = { handleDigit(label) },
                                modifier = Modifier.size(80.dp),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Text(label, fontSize = 24.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        if (onCancel != null) {
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

enum class PinLockMode { SET, VERIFY }
