package com.example.wallettrackers.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

val ChartPalette = listOf(
    Color(0xFF7C3AED),
    Color(0xFF4F46E5),
    Color(0xFF34D399),
    Color(0xFFF87171),
    Color(0xFFFBBF24),
    Color(0xFF60A5FA),
)

@Composable
fun WeeklyBarChart(
    labels: List<String>,
    values: List<Double>,
    highlightIndex: Int = labels.lastIndex,
    modifier: Modifier = Modifier
) {
    val maxVal = values.maxOrNull()?.takeIf { it > 0.0 } ?: 1.0
    val animProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(900, easing = FastOutSlowInEasing),
        label = "bar_anim"
    )

    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
        ) {
            val count = labels.size.coerceAtLeast(1)
            val slotW = size.width / count
            val barW = slotW * 0.42f

            values.forEachIndexed { i, amount ->
                val barH = ((amount / maxVal) * size.height * 0.85f * animProgress).toFloat()
                val cx = i * slotW + slotW / 2f
                val isHigh = i == highlightIndex

                drawRoundRect(
                    color = Color(0x1A7C3AED),
                    topLeft = Offset(cx - barW / 2f, 0f),
                    size = Size(barW, size.height),
                    cornerRadius = CornerRadius(barW / 2f)
                )
                if (barH > 0f) {
                    drawRoundRect(
                        brush = if (isHigh)
                            Brush.verticalGradient(
                                colors = listOf(Color(0xFFA78BFA), Color(0xFF4F46E5)),
                                startY = size.height - barH,
                                endY = size.height
                            )
                        else
                            Brush.verticalGradient(
                                colors = listOf(Color(0x707C3AED), Color(0x404F46E5)),
                                startY = size.height - barH,
                                endY = size.height
                            ),
                        topLeft = Offset(cx - barW / 2f, size.height - barH),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barW / 2f)
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    fontSize = 9.sp,
                    fontWeight = if (i == highlightIndex) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == highlightIndex) AppVioletLight else AppTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
fun CategoryDonutChart(
    segments: List<Triple<String, Double, Color>>,
    centerLabel: String = "",
    modifier: Modifier = Modifier
) {
    val total = segments.sumOf { it.second }.takeIf { it > 0.0 } ?: 1.0
    val animProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(1100, easing = FastOutSlowInEasing),
        label = "donut_anim"
    )

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.175f
                val pad = stroke / 2f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(pad, pad)
                var startAngle = -90f

                segments.forEach { (_, amount, color) ->
                    val sweep = ((amount / total) * 360.0 * animProgress).toFloat()
                    drawArc(
                        color = color,
                        startAngle = startAngle,
                        sweepAngle = (sweep - 1.5f).coerceAtLeast(0f),
                        useCenter = false,
                        style = Stroke(width = stroke, cap = StrokeCap.Butt),
                        topLeft = topLeft,
                        size = arcSize
                    )
                    startAngle += sweep
                }
                drawCircle(
                    color = Color(0xFF09090F),
                    radius = size.minDimension / 2f - stroke + 2f
                )
            }
            if (centerLabel.isNotEmpty()) {
                Text(
                    text = centerLabel,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = AppTextPrimary,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(Modifier.width(14.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            segments.take(6).forEach { (name, amount, color) ->
                val pct = (amount / total * 100).toInt()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(color)
                    )
                    Text(
                        text = name,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = AppTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "$pct%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
            }
        }
    }
}

@Composable
fun HomeWeeklyChart(records: List<Record>, modifier: Modifier = Modifier) {
    val chartData = remember(records) {
        val fmt = SimpleDateFormat("EEE", Locale.getDefault())
        val result = (6 downTo 0).map { ago ->
            val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -ago) }
            val dayStart = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayEnd = (dayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
            val total = records.filter {
                it.type == "Expense" &&
                it.timestamp >= dayStart.time &&
                it.timestamp < dayEnd.time
            }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
            Triple(fmt.format(cal.time).take(3), total, ago == 0)
        }
        Triple(
            result.map { it.first },
            result.map { it.second },
            result.indexOfFirst { it.third }
        )
    }
    WeeklyBarChart(
        labels = chartData.first,
        values = chartData.second,
        highlightIndex = chartData.third,
        modifier = modifier
    )
}

@Composable
fun HomeCategoryDonutChart(records: List<Record>, modifier: Modifier = Modifier) {
    val startOfMonth = remember {
        Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.time
    }
    val segments = remember(records) {
        records
            .filter { it.type == "Expense" && it.timestamp >= startOfMonth }
            .groupBy { it.category }
            .mapValues { e -> e.value.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
            .toList()
            .sortedByDescending { it.second }
            .take(6)
            .mapIndexed { i, (cat, amt) ->
                Triple(cat, amt, ChartPalette.getOrElse(i) { Color.Gray })
            }
    }
    if (segments.isEmpty()) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("No expense data this month", fontSize = 13.sp, color = AppTextSecondary)
        }
    } else {
        CategoryDonutChart(
            segments = segments,
            centerLabel = "This\nMonth",
            modifier = modifier
        )
    }
}

@Composable
fun MonthlyTrendChart(
    dailyData: List<Double>,
    modifier: Modifier = Modifier
) {
    var animationPlayed by remember { mutableStateOf(false) }
    val animProgress by animateFloatAsState(
        targetValue = if (animationPlayed) 1f else 0f,
        animationSpec = tween(1000, easing = FastOutSlowInEasing),
        label = "trend"
    )
    LaunchedEffect(Unit) { animationPlayed = true }

    val primaryColor = AppPrimary
    val surfaceColor = AppSurface

    if (dailyData.isEmpty() || dailyData.all { it == 0.0 }) {
        Box(modifier = modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
            Text("No spending data yet", fontSize = 13.sp, color = AppTextSecondary)
        }
    } else {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .background(surfaceColor)
                .padding(16.dp)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val maxVal = dailyData.max().coerceAtLeast(1.0)
                val w = size.width
                val h = size.height
                val stepX = w / (dailyData.size - 1).coerceAtLeast(1)
                val path = androidx.compose.ui.graphics.Path()
                val fillPath = androidx.compose.ui.graphics.Path()

                dailyData.forEachIndexed { index, value ->
                    val x = index * stepX
                    val y = h - (value / maxVal * h * animProgress).toFloat()
                    if (index == 0) {
                        path.moveTo(x, y)
                        fillPath.moveTo(x, h)
                        fillPath.lineTo(x, y)
                    } else {
                        path.lineTo(x, y)
                        fillPath.lineTo(x, y)
                    }
                }
                fillPath.lineTo((dailyData.size - 1) * stepX, h)
                fillPath.close()

                // Fill gradient
                drawPath(
                    path = fillPath,
                    brush = Brush.verticalGradient(
                        listOf(primaryColor.copy(alpha = 0.3f), primaryColor.copy(alpha = 0.0f))
                    )
                )
                // Line
                drawPath(
                    path = path,
                    color = primaryColor,
                    style = Stroke(width = 2.5f.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            // Labels
            Row(
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("30d ago", fontSize = 9.sp, color = AppTextMuted)
                Text("Today", fontSize = 9.sp, color = AppTextMuted)
            }
        }
    }
}
