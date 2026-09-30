package com.example.wallettrackers.util

import java.math.BigDecimal
import java.math.RoundingMode

object BalanceAmountFormatter {
    fun format(amount: Double): String =
        BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).toPlainString()
}
