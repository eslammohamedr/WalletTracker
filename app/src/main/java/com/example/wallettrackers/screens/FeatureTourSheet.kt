package com.example.wallettrackers.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.ui.theme.*
import kotlinx.coroutines.launch

private data class TourPage(
    val icon: ImageVector,
    val iconTint: Color,
    val glowColor: Color,
    val title: String,
    val subtitle: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureTourSheet(
    onDismiss: () -> Unit
) {
    val pages = listOf(
        TourPage(
            icon = Icons.Default.Sms,
            iconTint = AppVioletLight,
            glowColor = AppViolet,
            title = "Smart SMS Import",
            subtitle = "Automatic Transaction Tracking",
            description = "Your bank SMS messages are automatically parsed and categorized. Every purchase, transfer, and payment is tracked without manual entry."
        ),
        TourPage(
            icon = Icons.Default.AutoAwesome,
            iconTint = AppAmber,
            glowColor = Color(0xFFD97706),
            title = "AI Chat Assistant",
            subtitle = "Ask About Your Finances",
            description = "Chat with AI about your spending habits. Ask \"How much did I spend on food?\" or \"What's my biggest expense?\" and get instant answers."
        ),
        TourPage(
            icon = Icons.Default.CallSplit,
            iconTint = AppGreen,
            glowColor = Color(0xFF059669),
            title = "Split Receipt",
            subtitle = "Scan & Split with Friends",
            description = "Scan a receipt with your camera, assign items to friends, and share each person's total via WhatsApp. Taxes are split proportionally."
        ),
        TourPage(
            icon = Icons.Default.CameraAlt,
            iconTint = AppPrimaryLight,
            glowColor = AppPrimary,
            title = "Receipt Scanner",
            subtitle = "Photo to Transaction",
            description = "Take a photo of any receipt and AI extracts the amount, merchant, and category automatically. No more manual data entry."
        ),
        TourPage(
            icon = Icons.Default.TrendingUp,
            iconTint = AppVioletLight,
            glowColor = AppViolet,
            title = "AI Insights & Budgets",
            subtitle = "Smart Financial Intelligence",
            description = "Get AI-generated spending reports, smart budget suggestions based on your history, and predictive cash flow forecasts."
        )
    )

    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = AppBackground,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AppTextMuted.copy(alpha = 0.4f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Title
            Text(
                text = "Discover What's Inside",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = AppTextPrimary,
                modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
            )

            // Pager
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxWidth().height(340.dp),
                contentPadding = PaddingValues(horizontal = 32.dp),
                pageSpacing = 16.dp
            ) { pageIndex ->
                val page = pages[pageIndex]
                TourPageCard(page = page)
            }

            Spacer(Modifier.height(20.dp))

            // Page indicators
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pages.size) { index ->
                    val isSelected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (isSelected) 10.dp else 7.dp)
                            .clip(CircleShape)
                            .background(
                                if (isSelected) AppVioletLight
                                else AppTextMuted.copy(alpha = 0.3f)
                            )
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (pagerState.currentPage < pages.size - 1) {
                    // Skip button
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp, AppTextMuted.copy(alpha = 0.3f)
                        )
                    ) {
                        Text("Skip", color = AppTextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    // Next button
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentGradient)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Text("Next", fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    // Last page — "Get Started" button
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentGradient)
                    ) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxSize(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                            elevation = ButtonDefaults.buttonElevation(0.dp)
                        ) {
                            Text("Got It!", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TourPageCard(page: TourPage) {
    val infiniteTransition = rememberInfiniteTransition(label = "tour_glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )
    val floatY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "float"
    )

    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppSurface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Animated icon with glow
            Box(
                modifier = Modifier.graphicsLayer { translationY = floatY },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    page.glowColor.copy(alpha = glowAlpha),
                                    Color.Transparent
                                )
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(page.glowColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = page.icon,
                        contentDescription = null,
                        tint = page.iconTint,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = page.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
                color = AppTextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = page.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppVioletLight,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = page.description,
                style = MaterialTheme.typography.bodyMedium,
                color = AppTextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 22.sp
            )
        }
    }
}
