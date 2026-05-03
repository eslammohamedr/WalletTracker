package com.example.wallettrackers.ui.theme

import androidx.compose.ui.graphics.Color
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

// Dark Theme — rich dark blue base
val DarkBackground = Color(0xFF0A1628)          // Deep navy blue
val DarkOnBackground = Color(0xFFF1F5F9)        // Slate 100
val DarkPrimary = Color(0xFF60A5FA)             // Blue 400
val DarkOnPrimary = Color(0xFF0F2050)           // Deep blue
val DarkPrimaryContainer = Color(0xFF1E3A8A)    // Blue 800
val DarkOnPrimaryContainer = Color(0xFFBFDBFE)  // Blue 200
val DarkSecondary = Color(0xFFA78BFA)           // Violet 400
val DarkOnSecondary = Color(0xFF3B0764)         // Violet 950
val DarkSecondaryContainer = Color(0xFF4C1D95)  // Violet 900
val DarkOnSecondaryContainer = Color(0xFFEDE9FE)// Violet 100
val DarkTertiary = Color(0xFF34D399)            // Emerald 400
val DarkOnTertiary = Color(0xFF022C22)          // Emerald 950
val DarkTertiaryContainer = Color(0xFF064E3B)
val DarkOnTertiaryContainer = Color(0xFFA7F3D0)
val DarkSurface = Color(0xFF0F1F3D)             // Dark blue surface
val DarkSurfaceVariantColor = Color(0xFF162035) // Elevated dark blue surface
val DarkOnSurface = Color(0xFFF1F5F9)           // Slate 100
val DarkSurfaceVariant = Color(0xFF162035)      // Dark blue-slate
val DarkOnSurfaceVariant = Color(0xFF94A3B8)    // Slate 400
val DarkOutline = Color(0xFF1E3A5F)             // Dark blue outline
val DarkError = Color(0xFFFCA5A5)
val DarkOnError = Color(0xFF7F1D1D)

// Gradient accent colors (dark)
val DarkGradientStart = Color(0xFF3B82F6)       // Blue 500
val DarkGradientEnd = Color(0xFF8B5CF6)         // Violet 500
val DarkGradientGreen = Color(0xFF10B981)       // Emerald 500
val DarkGradientAmber = Color(0xFFF59E0B)       // Amber 500

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
