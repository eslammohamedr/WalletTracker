package com.example.wallettrackers.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backspace
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.ui.theme.*

@Composable
fun NumberPad(
    onNumberClick: (String) -> Unit,
    onBackspace: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpringNumberButton("1", { onNumberClick("1") }, Modifier.weight(1f))
            SpringNumberButton("2", { onNumberClick("2") }, Modifier.weight(1f))
            SpringNumberButton("3", { onNumberClick("3") }, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpringNumberButton("4", { onNumberClick("4") }, Modifier.weight(1f))
            SpringNumberButton("5", { onNumberClick("5") }, Modifier.weight(1f))
            SpringNumberButton("6", { onNumberClick("6") }, Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpringNumberButton("7", { onNumberClick("7") }, Modifier.weight(1f))
            SpringNumberButton("8", { onNumberClick("8") }, Modifier.weight(1f))
            SpringNumberButton("9", { onNumberClick("9") }, Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpringNumberButton(".", { onNumberClick(".") }, Modifier.weight(1f))
            SpringNumberButton("0", { onNumberClick("0") }, Modifier.weight(1f))
            SpringBackspaceButton(onClick = onBackspace, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun SpringNumberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "num_scale_$text"
    )
    val currentOnClick by rememberUpdatedState(onClick)

    Box(
        modifier = modifier
            .aspectRatio(1.8f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(AppSurface)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        currentOnClick()
                        tryAwaitRelease()
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.04f),
                            Color.Transparent
                        )
                    )
                )
        )
        Text(
            text = text,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = AppTextPrimary
        )
    }
}

@Composable
private fun SpringBackspaceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.88f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "backspace_scale"
    )
    val currentOnClick by rememberUpdatedState(onClick)

    Box(
        modifier = modifier
            .aspectRatio(1.8f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(16.dp))
            .background(AppRed.copy(alpha = 0.15f))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        currentOnClick()
                        tryAwaitRelease()
                        pressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Backspace,
            contentDescription = "Backspace",
            tint = AppRed,
            modifier = Modifier.size(24.dp)
        )
    }
}
