package com.example.wallettrackers.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Warning
import coil.compose.AsyncImage
import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.model.Record
import java.text.SimpleDateFormat
import java.util.*

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordCard(
    record: Record,
    onLongClick: () -> Unit,
    isUnusual: Boolean = false,
    fxRates: Map<String, Double> = emptyMap(),
    onReceiptClick: (() -> Unit)? = null
) {
    val category = Categories.list.flatMap { it.subCategories + it }.find { it.name == record.category }
    val isIncome = record.type == "Income"
    val accentColor = category?.color ?: AppVioletLight
    val amountColor = if (isIncome) AppGreen else AppRed

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // Left accent bar
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(76.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    .background(amountColor.copy(alpha = 0.6f))
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Icon badge
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = category?.icon ?: Icons.Default.Category,
                        contentDescription = category?.name,
                        modifier = Modifier.size(22.dp),
                        tint = accentColor
                    )
                }

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = record.category,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyLarge,
                            color = accentColor
                        )
                        if (isUnusual) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = "Unusual amount",
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    if (record.comment.isNotEmpty()) {
                        Text(
                            text = record.comment,
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        text = record.accountName,
                        style = MaterialTheme.typography.labelSmall,
                        color = AppTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${if (isIncome) "+" else "-"}${record.amount} ${record.currency}",
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.bodyLarge,
                        color = amountColor,
                        letterSpacing = (-0.5).sp
                    )
                    if (record.currency != "EGP") {
                        val rate = fxRates[record.currency]
                        val amt = record.amount.toDoubleOrNull()
                        if (rate != null && amt != null) {
                            Text(
                                text = "≈${String.format("%,.0f", amt * rate)} EGP",
                                style = MaterialTheme.typography.labelSmall,
                                color = AppTextSecondary
                            )
                        }
                    }
                    if (record.balanceAfter.isNotEmpty()) {
                        Text(
                            text = "${record.balanceAfter} ${record.currency}",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTextMuted
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
                        color = AppPrimaryLight.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold
                    )
                    if (record.receiptUrl.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        if (onReceiptClick != null) {
                            AsyncImage(
                                model = record.receiptUrl,
                                contentDescription = "Receipt",
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { onReceiptClick() },
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                Icons.Default.Receipt,
                                contentDescription = "Has receipt",
                                modifier = Modifier.size(16.dp),
                                tint = accentColor.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
        }
    }
}
