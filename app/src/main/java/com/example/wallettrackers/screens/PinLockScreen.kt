package com.example.wallettrackers.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

import com.example.wallettrackers.ui.theme.*

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
        mode == PinLockMode.SET && !isConfirming -> "Security PIN"
        mode == PinLockMode.SET && isConfirming -> "Confirm PIN"
        else -> "Welcome Back"
    }

    val subtitle = when {
        mode == PinLockMode.SET && !isConfirming -> "Create a 4-digit code to protect your data"
        mode == PinLockMode.SET && isConfirming -> "Verify your new security code"
        else -> "Enter your PIN to access dashboard"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(AppBackground).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AppPrimary.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Lock, null, Modifier.size(32.dp), tint = AppVioletLight)
        }
        
        Spacer(Modifier.height(32.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = AppTextPrimary)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = AppTextSecondary, textAlign = TextAlign.Center)
        
        Spacer(Modifier.height(24.dp))

        if (errorMessage.isNotEmpty()) {
            Surface(color = AppRed.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp)) {
                Text(errorMessage, color = AppRed, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontWeight = FontWeight.Bold)
            }
        } else {
            Spacer(Modifier.height(24.dp))
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier.size(16.dp).clip(CircleShape).background(
                        if (i < currentPin.length) AppVioletLight
                        else AppPrimary.copy(alpha = 0.2f)
                    )
                )
            }
        }

        Spacer(Modifier.height(56.dp))

        val digits = listOf(
            listOf("1","2","3"),
            listOf("4","5","6"),
            listOf("7","8","9"),
            listOf("","0","⌫")
        )

        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            digits.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    row.forEach { label ->
                        if (label == "") {
                            Spacer(Modifier.size(76.dp))
                        } else if (label == "⌫") {
                            Surface(
                                onClick = { handleBackspace() },
                                modifier = Modifier.size(76.dp),
                                shape = CircleShape,
                                color = AppSurface,
                                border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.3f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Backspace, null, tint = AppTextSecondary, modifier = Modifier.size(24.dp))
                                }
                            }
                        } else {
                            Surface(
                                onClick = { handleDigit(label) },
                                modifier = Modifier.size(76.dp),
                                shape = CircleShape,
                                color = AppSurface,
                                border = BorderStroke(1.dp, AppPrimary.copy(alpha = 0.3f))
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(label, fontSize = 28.sp, fontWeight = FontWeight.Black, color = AppTextPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (onCancel != null) {
            Spacer(Modifier.height(32.dp))
            TextButton(onClick = onCancel) { 
                Text("Cancel", color = AppTextSecondary, fontWeight = FontWeight.Bold) 
            }
        }
    }
}

enum class PinLockMode { SET, VERIFY }
