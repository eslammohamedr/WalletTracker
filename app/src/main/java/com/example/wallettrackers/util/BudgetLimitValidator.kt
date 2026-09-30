package com.example.wallettrackers.util

fun isValidBudgetLimit(value: String): Boolean {
    val amount = value.toDoubleOrNull() ?: return false
    return amount.isFinite() && amount > 0.0
}
