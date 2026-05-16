package com.example.wallettrackers.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.ui.theme.AppAmber
import com.example.wallettrackers.ui.theme.AppTextPrimary

@Composable
fun StreakBadge(streakDays: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "streak")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFFFFA726).copy(alpha = 0.15f), Color(0xFFFF7043).copy(alpha = 0.15f))
                )
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.LocalFireDepartment,
                contentDescription = null,
                tint = AppAmber,
                modifier = Modifier.size(28.dp).scale(pulse)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "$streakDays day${if (streakDays != 1) "s" else ""} under budget!",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTextPrimary
                )
                Text(
                    "Keep it up! You're doing great.",
                    fontSize = 12.sp,
                    color = AppAmber
                )
            }
        }
    }
}
