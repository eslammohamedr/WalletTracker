package com.example.wallettrackers.ui.theme

import androidx.compose.ui.graphics.Color
import com.example.wallettrackers.converters.colorToLong

/** Ordered palette used to auto-assign account card colors. */
val AccountColorPalette: List<Color> = listOf(
    Color(0xFF1D4ED8), // Blue
    Color(0xFF7C3AED), // Violet
    Color(0xFF059669), // Emerald
    Color(0xFFDC2626), // Red
    Color(0xFFF59E0B), // Amber
    Color(0xFF0891B2), // Cyan
    Color(0xFFDB2777), // Pink
    Color(0xFF65A30D), // Lime
    Color(0xFF9333EA), // Purple
    Color(0xFF0369A1), // Sky
    Color(0xFFEA580C), // Orange
    Color(0xFF0D9488), // Teal
)

/**
 * Returns the first palette color whose Long representation is not in [usedColors].
 * Falls back to the first palette color if all are taken.
 */
fun pickAutoColor(usedColors: List<Long>): Color =
    AccountColorPalette.firstOrNull { colorToLong(it) !in usedColors }
        ?: AccountColorPalette.first()

// === Modern Finance Palette ===

// Dark Theme
val DarkBackground = Color(0xFF0F172A)          // Slate 900 – deep navy
val DarkOnBackground = Color(0xFFF1F5F9)        // Slate 100
val DarkPrimary = Color(0xFF60A5FA)             // Blue 400
val DarkOnPrimary = Color(0xFF1E3A8A)           // Blue 900
val DarkPrimaryContainer = Color(0xFF1E40AF)    // Blue 800
val DarkOnPrimaryContainer = Color(0xFFBFDBFE)  // Blue 200
val DarkSecondary = Color(0xFFA78BFA)           // Violet 400
val DarkOnSecondary = Color(0xFF4C1D95)         // Violet 900
val DarkSecondaryContainer = Color(0xFF5B21B6)  // Violet 800
val DarkOnSecondaryContainer = Color(0xFFEDE9FE)// Violet 100
val DarkTertiary = Color(0xFF34D399)            // Emerald 400
val DarkOnTertiary = Color(0xFF064E3B)          // Emerald 900
val DarkTertiaryContainer = Color(0xFF065F46)
val DarkOnTertiaryContainer = Color(0xFFA7F3D0)
val DarkSurface = Color(0xFF1E293B)             // Slate 800
val DarkOnSurface = Color(0xFFF1F5F9)           // Slate 100
val DarkSurfaceVariant = Color(0xFF334155)      // Slate 700
val DarkOnSurfaceVariant = Color(0xFF94A3B8)    // Slate 400
val DarkOutline = Color(0xFF475569)             // Slate 600
val DarkError = Color(0xFFFCA5A5)
val DarkOnError = Color(0xFF7F1D1D)

// Light Theme
val LightBackground = Color(0xFFF1F5F9)         // Slate 100
val LightOnBackground = Color(0xFF0F172A)       // Slate 900
val LightPrimary = Color(0xFF1D4ED8)            // Blue 700
val LightOnPrimary = Color.White
val LightPrimaryContainer = Color(0xFFDBEAFE)   // Blue 100
val LightOnPrimaryContainer = Color(0xFF1E3A8A) // Blue 900
val LightSecondary = Color(0xFF7C3AED)          // Violet 600
val LightOnSecondary = Color.White
val LightSecondaryContainer = Color(0xFFEDE9FE) // Violet 100
val LightOnSecondaryContainer = Color(0xFF4C1D95)// Violet 900
val LightTertiary = Color(0xFF059669)           // Emerald 600
val LightOnTertiary = Color.White
val LightTertiaryContainer = Color(0xFFD1FAE5)
val LightOnTertiaryContainer = Color(0xFF064E3B)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF0F172A)          // Slate 900
val LightSurfaceVariant = Color(0xFFE2E8F0)     // Slate 200
val LightOnSurfaceVariant = Color(0xFF475569)   // Slate 600
val LightOutline = Color(0xFFCBD5E1)            // Slate 300
val LightError = Color(0xFFDC2626)
val LightOnError = Color.White
