package com.example.wallettrackers

import com.example.wallettrackers.util.BalanceAmountFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class BalanceAmountFormatterTest {
    @Test
    fun roundsBinaryFloatingPointBalanceToCurrencyPrecision() {
        val balance = 9999.83 - 0.04

        assertEquals("9999.79", BalanceAmountFormatter.format(balance))
    }

    @Test
    fun preservesTwoDecimalPlacesForWholeAndFractionalBalances() {
        assertEquals("10000.00", BalanceAmountFormatter.format(10000.0))
        assertEquals("4386.63", BalanceAmountFormatter.format(4386.63))
    }
}
