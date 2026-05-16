package com.example.wallettrackers.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.ui.theme.*

data class BottomNavItem(
    val route: String,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomNavItems = listOf(
    BottomNavItem("home", "Home", Icons.Filled.Home, Icons.Outlined.Home),
    BottomNavItem("all_records", "Records", Icons.Filled.Receipt, Icons.Outlined.Receipt),
    BottomNavItem("statistics", "Stats", Icons.Filled.BarChart, Icons.Outlined.BarChart),
    BottomNavItem("budget", "Budget", Icons.Filled.Savings, Icons.Outlined.Savings)
)

val bottomNavRoutes = bottomNavItems.map { it.route }

@Composable
fun BottomNavBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    onAddClick: () -> Unit
) {
    // Animated rotating glow on the FAB
    val infiniteTransition = rememberInfiniteTransition(label = "fab_glow")
    val fabGlowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "fab_glow_alpha"
    )

    var fabPressed by remember { mutableStateOf(false) }
    val fabScale by animateFloatAsState(
        targetValue = if (fabPressed) 0.90f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "fab_scale"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Main bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .shadow(
                    elevation = 32.dp,
                    shape = RoundedCornerShape(32.dp),
                    ambientColor = Color.Black.copy(alpha = 0.7f),
                    spotColor = Color.Black.copy(alpha = 0.7f)
                )
                .clip(RoundedCornerShape(32.dp))
                .background(AppSurface)
        ) {
            // Gradient overlay
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                AppPrimary.copy(alpha = 0.08f),
                                AppViolet.copy(alpha = 0.08f)
                            )
                        )
                    )
            )

            // Nav items
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                bottomNavItems.take(2).forEach { item ->
                    AnimatedNavBarItem(
                        item = item,
                        isSelected = currentRoute == item.route,
                        onClick = { onNavigate(item.route) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.width(72.dp))

                bottomNavItems.drop(2).forEach { item ->
                    AnimatedNavBarItem(
                        item = item,
                        isSelected = currentRoute == item.route,
                        onClick = { onNavigate(item.route) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Floating Add button with pulse glow + spring press
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-14).dp),
            contentAlignment = Alignment.Center
        ) {
            // Pulsing glow ring behind FAB
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                AppViolet.copy(alpha = fabGlowAlpha * 0.5f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // FAB itself
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .graphicsLayer { scaleX = fabScale; scaleY = fabScale }
                    .shadow(
                        elevation = 20.dp,
                        shape = CircleShape,
                        ambientColor = AppPrimary.copy(alpha = 0.7f),
                        spotColor = AppViolet.copy(alpha = 0.7f)
                    )
                    .clip(CircleShape)
                    .background(AccentGradient)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {
                            fabPressed = true
                            onAddClick()
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Record",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }

            // Reset fabPressed after animation
            LaunchedEffect(fabPressed) {
                if (fabPressed) {
                    kotlinx.coroutines.delay(180)
                    fabPressed = false
                }
            }
        }
    }
}

@Composable
private fun AnimatedNavBarItem(
    item: BottomNavItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isSelected) 1.12f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "nav_scale_${item.route}"
    )

    val indicatorWidth by animateDpAsState(
        targetValue = if (isSelected) 22.dp else 0.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "indicator_width_${item.route}"
    )

    val iconAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0.55f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "icon_alpha_${item.route}"
    )

    val iconColor = if (isSelected) AppVioletLight else AppTextSecondary

    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .graphicsLayer { scaleX = scale; scaleY = scale },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Animated sliding pill indicator
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(indicatorWidth)
                .clip(RoundedCornerShape(2.dp))
                .background(AccentGradient)
        )
        Spacer(Modifier.height(5.dp))

        Icon(
            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
            contentDescription = item.label,
            tint = iconColor.copy(alpha = iconAlpha),
            modifier = Modifier.size(22.dp)
        )

        Spacer(Modifier.height(3.dp))

        Text(
            text = item.label,
            fontSize = 9.sp,
            fontWeight = if (isSelected) FontWeight.Black else FontWeight.SemiBold,
            color = iconColor.copy(alpha = iconAlpha),
            letterSpacing = 0.3.sp
        )
    }
}
