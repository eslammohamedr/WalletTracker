package com.example.wallettrackers.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object StatementDueDateParser {
    private val formats = listOf("dd/MM/yyyy", "dd-MM-yyyy")

    fun parse(value: String?): Date? {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return formats.firstNotNullOfOrNull { format ->
            val position = ParsePosition(0)
            val parser = SimpleDateFormat(format, Locale.ROOT).apply { isLenient = false }
            parser.parse(normalized, position)?.takeIf { position.index == normalized.length }
        }
    }
}
