package com.example.wallettrackers.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.ui.theme.*
import com.example.wallettrackers.viewmodel.HomeViewModel

@Composable
fun InsightCard(insight: HomeViewModel.SpendingInsight) {
    val icon = when (insight.icon) {
        "trending_up" -> Icons.Default.TrendingUp
        "trending_down" -> Icons.Default.TrendingDown
        "warning" -> Icons.Default.Warning
        "celebration" -> Icons.Default.Celebration
        else -> Icons.Default.TrendingUp
    }
    val accentColor = if (insight.isPositive) AppGreen else AppAmber

    Box(
        modifier = Modifier
            .width(220.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(AppSurface)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = accentColor, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    insight.message,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = AppTextPrimary,
                    maxLines = 2
                )
                Text(
                    "${if (insight.percentChange > 0) "+" else ""}${insight.percentChange}%",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }
        }
    }
}
