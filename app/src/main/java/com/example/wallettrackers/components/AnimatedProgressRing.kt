package com.example.wallettrackers.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.ui.theme.AppGreen
import com.example.wallettrackers.ui.theme.AppPrimary
import com.example.wallettrackers.ui.theme.AppTextMuted
import com.example.wallettrackers.ui.theme.AppTextPrimary
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Composable
fun AnimatedProgressRing(
    progress: Float, // 0f to 1f+
    label: String,
    size: Dp = 100.dp,
    strokeWidth: Dp = 10.dp
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(1200, easing = FastOutSlowInEasing),
        label = "progress"
    )
    val isComplete = progress >= 1f

    val progressColor = if (isComplete) AppGreen else AppPrimary
    val textColor = if (isComplete) AppGreen else AppTextPrimary
    val mutedColor = AppTextMuted

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ringSize = Size(this.size.width, this.size.height)
            val topLeft = Offset.Zero
            val stroke = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)

            // Background track
            drawArc(
                color = Color.Gray.copy(alpha = 0.15f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = stroke
            )

            // Progress arc
            drawArc(
                color = progressColor,
                startAngle = -90f,
                sweepAngle = animatedProgress * 360f,
                useCenter = false,
                topLeft = topLeft,
                size = ringSize,
                style = stroke
            )
        }

        // Confetti particles when complete
        if (isComplete) {
            ConfettiOverlay(size)
        }

        // Center text
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${(animatedProgress * 100).toInt()}%",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(label, fontSize = 9.sp, color = mutedColor)
        }
    }
}

@Composable
private fun ConfettiOverlay(size: Dp) {
    val infiniteTransition = rememberInfiniteTransition(label = "confetti")
    val particles = remember {
        List(12) {
            ConfettiParticle(
                angle = Random.nextFloat() * 360f,
                distance = Random.nextFloat() * 0.3f + 0.35f,
                color = listOf(Color(0xFFFF6B6B), Color(0xFF4ECDC4), Color(0xFFFFE66D), Color(0xFF95E1D3), Color(0xFFA78BFA)).random()
            )
        }
    }
    val animProgress by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2000), RepeatMode.Restart),
        label = "confetti_anim"
    )

    Canvas(modifier = Modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val radius = this.size.width / 2
        particles.forEach { particle ->
            val dist = radius * particle.distance * (0.6f + animProgress * 0.4f)
            val rad = Math.toRadians(particle.angle.toDouble())
            val x = center.x + dist * cos(rad).toFloat()
            val y = center.y + dist * sin(rad).toFloat()
            val alpha = (1f - animProgress).coerceIn(0f, 1f)
            drawCircle(
                color = particle.color.copy(alpha = alpha * 0.8f),
                radius = 3.dp.toPx(),
                center = Offset(x, y)
            )
        }
    }
}

private data class ConfettiParticle(val angle: Float, val distance: Float, val color: Color)
