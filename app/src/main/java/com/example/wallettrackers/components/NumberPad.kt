package com.example.wallettrackers.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun NumberPad(
    onNumberClick: (String) -> Unit,
    onBackspace: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NumberButton("1", { onNumberClick("1") }, Modifier.weight(1f))
            NumberButton("2", { onNumberClick("2") }, Modifier.weight(1f))
            NumberButton("3", { onNumberClick("3") }, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NumberButton("4", { onNumberClick("4") }, Modifier.weight(1f))
            NumberButton("5", { onNumberClick("5") }, Modifier.weight(1f))
            NumberButton("6", { onNumberClick("6") }, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NumberButton("7", { onNumberClick("7") }, Modifier.weight(1f))
            NumberButton("8", { onNumberClick("8") }, Modifier.weight(1f))
            NumberButton("9", { onNumberClick("9") }, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            NumberButton(".", { onNumberClick(".") }, Modifier.weight(1f))
            NumberButton("0", { onNumberClick("0") }, Modifier.weight(1f))
            IconButton(
                onClick = { onBackspace() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.Backspace,
                    contentDescription = "Backspace",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun NumberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    FilledTonalButton(
        onClick = onClick,
        shape = CircleShape,
        modifier = modifier
            .aspectRatio(1.5f)
    ) {
        Text(text, fontSize = 24.sp)
    }
}
