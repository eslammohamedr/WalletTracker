package com.example.wallettrackers.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import com.example.wallettrackers.converters.colorToLong

/** Ordered palette used to auto-assign account card colors. */
val AccountColorPalette: List<Color> = listOf(
    Color(0xFF2563EB), // Blue 600
    Color(0xFF7C3AED), // Violet 600
    Color(0xFF059669), // Emerald 600
    Color(0xFFDC2626), // Red 600
    Color(0xFFD97706), // Amber 600
    Color(0xFF0891B2), // Cyan 600
    Color(0xFFDB2777), // Pink 600
    Color(0xFF65A30D), // Lime 600
    Color(0xFF9333EA), // Purple 600
    Color(0xFF0369A1), // Sky 700
    Color(0xFFEA580C), // Orange 600
    Color(0xFF0D9488), // Teal 600
)

/**
 * Returns the first palette color whose Long representation is not in [usedColors].
 * Falls back to the first palette color if all are taken.
 */
fun pickAutoColor(usedColors: List<Long>): Color =
    AccountColorPalette.firstOrNull { colorToLong(it) !in usedColors }
        ?: AccountColorPalette.first()

// === Modern Finance Palette ===

// Dark Glass Palette
val DGBackground    = Color(0xFF09090F)
val DGSurface       = Color(0xFF0F0A28)
val DGViolet        = Color(0xFF7C3AED)
val DGVioletLight   = Color(0xFFA78BFA)
val DGIndigo        = Color(0xFF4F46E5)
val DGIndigoLight   = Color(0xFF6366F1)
val DGTextPrimary   = Color(0xFFF8FAFC)
val DGTextSecondary = Color(0xFF64748B)
val DGTextMuted     = Color(0xFF334155)
val DGRed           = Color(0xFFF87171)
val DGGreen         = Color(0xFF34D399)
val DGAmber         = Color(0xFFFDE68A)

val DarkBackground = DGBackground
val DarkOnBackground = DGTextPrimary
val DarkPrimary = DGVioletLight
val DarkOnPrimary = Color.White
val DarkPrimaryContainer = DGIndigo
val DarkOnPrimaryContainer = Color.White
val DarkSecondary = DGIndigoLight
val DarkOnSecondary = Color.White
val DarkSecondaryContainer = DGIndigo.copy(alpha = 0.5f)
val DarkOnSecondaryContainer = DGTextPrimary
val DarkTertiary = DGGreen
val DarkOnTertiary = Color.Black
val DarkTertiaryContainer = DGGreen.copy(alpha = 0.2f)
val DarkOnTertiaryContainer = DGGreen
val DarkSurface = DGSurface
val DarkSurfaceVariantColor = Color(0xFF162035) // Elevated dark blue surface
val DarkOnSurface = DGTextPrimary
val DarkSurfaceVariant = DGSurface
val DarkOnSurfaceVariant = DGTextSecondary
val DarkOutline = DGIndigo.copy(alpha = 0.3f)
val DarkError = DGRed
val DarkOnError = Color.White

// Gradient accent colors (dark)
val DarkGradientStart = DGIndigo
val DarkGradientEnd = DGViolet
val DarkGradientGreen = DGGreen
val DarkGradientAmber = DGAmber

val HeroGradient = Brush.linearGradient(
    colors = listOf(Color(0xFF3B0764), Color(0xFF2E1065), Color(0xFF1E1B4B))
)
val AccentGradient = Brush.verticalGradient(
    colors = listOf(DGVioletLight, DGIndigo)
)

// Light Theme
val LightBackground = Color(0xFFF0F4F8)         // Slate 100 with blue cast
val LightOnBackground = Color(0xFF0F172A)       // Slate 900
val LightPrimary = Color(0xFF1D4ED8)            // Blue 700
val LightOnPrimary = Color.White
val LightPrimaryContainer = Color(0xFFDBEAFE)   // Blue 100
val LightOnPrimaryContainer = Color(0xFF1E3A8A) // Blue 900
val LightSecondary = Color(0xFF6D28D9)          // Violet 700
val LightOnSecondary = Color.White
val LightSecondaryContainer = Color(0xFFEDE9FE) // Violet 100
val LightOnSecondaryContainer = Color(0xFF4C1D95)// Violet 900
val LightTertiary = Color(0xFF059669)           // Emerald 600
val LightOnTertiary = Color.White
val LightTertiaryContainer = Color(0xFFD1FAE5)
val LightOnTertiaryContainer = Color(0xFF064E3B)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF0F172A)          // Slate 900
val LightSurfaceVariant = Color(0xFFE8EEF6)     // Slightly blue-tinted
val LightOnSurfaceVariant = Color(0xFF475569)   // Slate 600
val LightOutline = Color(0xFFCBD5E1)            // Slate 300
val LightError = Color(0xFFDC2626)
val LightOnError = Color.White

// Gradient accent colors (light)
val LightGradientStart = Color(0xFF1D4ED8)      // Blue 700
val LightGradientEnd = Color(0xFF7C3AED)        // Violet 600
val LightGradientGreen = Color(0xFF059669)      // Emerald 600
val LightGradientAmber = Color(0xFFD97706)      // Amber 600
