package com.example.wallettrackers.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.wallettrackers.ui.theme.AppPrimary
import com.example.wallettrackers.ui.theme.AppSurface
import java.util.Locale
import kotlin.math.roundToLong

// ── Animated Number Counter ───────────────────────────────────────────────────

@Composable
fun AnimatedCounter(
    targetValue: Double,
    durationMs: Int = 1200,
    format: (Double) -> String = { String.format(Locale.getDefault(), "%,.2f", it) },
    style: TextStyle = TextStyle(fontSize = 38.sp, fontWeight = FontWeight.ExtraBold),
    color: Color = Color.White
) {
    val animatedValue by animateFloatAsState(
        targetValue = targetValue.toFloat(),
        animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
        label = "counter_animation"
    )
    Text(
        text = format(animatedValue.toDouble()),
        style = style,
        color = color
    )
}

// ── Shimmer Loading Effect ───────────────────────────────────────────────────

fun Modifier.shimmerEffect(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    this
        .clip(shape)
        .background(
            Brush.linearGradient(
                colors = listOf(
                    AppSurface,
                    AppPrimary.copy(alpha = 0.3f),
                    AppSurface
                ),
                start = Offset(translateAnim - 300f, 0f),
                end = Offset(translateAnim, 0f)
            )
        )
}

// ── Pulse Glow Ring ───────────────────────────────────────────────────────────

@Composable
fun PulseRing(
    color: Color,
    size: Dp = 120.dp,
    content: @Composable () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
                .background(
                    Brush.radialGradient(
                        colors = listOf(color.copy(alpha = 0.5f), Color.Transparent)
                    )
                )
        )
        content()
    }
}

// ── Float Up Animation ────────────────────────────────────────────────────────

@Composable
fun FloatingAnimation(
    amplitude: Float = 10f,
    durationMs: Int = 2800,
    content: @Composable (offsetY: Float) -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "float")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = amplitude,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float_y"
    )
    content(offsetY)
}

// ── Scale on Press (Bounce Click) ────────────────────────────────────────────

fun Modifier.bounceClick(
    scaleDown: Float = 0.93f,
    pressed: Boolean
): Modifier = composed {
    val scale by animateFloatAsState(
        targetValue = if (pressed) scaleDown else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "bounce_scale"
    )
    this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

// ── Animated Gradient Shift ──────────────────────────────────────────────────

@Composable
fun animatedGradientAngle(): Float {
    val infiniteTransition = rememberInfiniteTransition(label = "gradient_shift")
    return infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradient_angle"
    ).value
}

// ── Count Up Text ─────────────────────────────────────────────────────────────

@Composable
fun CountUpText(
    target: Int,
    durationMs: Int = 800,
    style: TextStyle = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold),
    color: Color = Color.White,
    suffix: String = ""
) {
    val animatedValue by animateFloatAsState(
        targetValue = target.toFloat(),
        animationSpec = tween(durationMillis = durationMs, easing = FastOutSlowInEasing),
        label = "count_up"
    )
    Text(
        text = "${animatedValue.roundToLong()}$suffix",
        style = style,
        color = color
    )
}

// ── Stagger Index Delay ──────────────────────────────────────────────────────

fun staggerDelay(index: Int, baseDelayMs: Int = 60): Int = index * baseDelayMs
