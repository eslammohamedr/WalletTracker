package com.example.wallettrackers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.util.FinancialCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinancialCalculatorCurrencyComponentTest {
    @Test
    fun currencySymbolsNormalizeToTheirCurrencyCodes() {
        assertEquals("USD", FinancialCalculator.normaliseCurrency("$"))
        assertEquals("EUR", FinancialCalculator.normaliseCurrency("€"))
        assertEquals("GBP", FinancialCalculator.normaliseCurrency("£"))
    }

    @Test
    fun foreignCurrencySymbolsCannotPayEgpCardStatements() {
        assertFalse(FinancialCalculator.isEgpStatementPaymentAccount("$", "LocalBank"))
        assertFalse(FinancialCalculator.isEgpStatementPaymentAccount("€", "LocalBank"))
        assertFalse(FinancialCalculator.isEgpStatementPaymentAccount("£", "LocalBank"))
        assertFalse(FinancialCalculator.isEgpStatementPaymentAccount("", "SterlingBank"))
    }
}
