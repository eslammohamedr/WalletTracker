package com.example.wallettrackers.converters

import androidx.compose.ui.graphics.Color

fun colorToLong(color: Color): Long {
    return color.value.toLong()
}

fun longToColor(long: Long): Color {
    return Color(long.toULong())
}
