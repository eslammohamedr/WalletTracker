package com.example.wallettrackers

import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.FinancialCalculator
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for FinancialCalculator — covers STAT-F-002, STAT-F-003, STAT-F-006
 * Run with: ./gradlew test
 */
class FinancialCalculatorTest {

    // ── parseAmount  (STAT-F-002) ─────────────────────────────────────────

    @Test
    fun `parseAmount parses plain integer string`() {
        assertEquals(500.0, FinancialCalculator.parseAmount("500"), 0.001)
    }

    @Test
    fun `parseAmount parses decimal string`() {
        assertEquals(1250.50, FinancialCalculator.parseAmount("1250.50"), 0.001)
    }

    @Test
    fun `parseAmount strips commas from thousands-separated value`() {
        assertEquals(10000.0, FinancialCalculator.parseAmount("10,000"), 0.001)
    }

    @Test
    fun `parseAmount strips currency prefix`() {
        assertEquals(500.0, FinancialCalculator.parseAmount("EGP 500"), 0.001)
    }

    @Test
    fun `parseAmount handles negative values`() {
        assertEquals(-200.0, FinancialCalculator.parseAmount("-200"), 0.001)
    }

    @Test
    fun `parseAmount returns zero for blank string`() {
        assertEquals(0.0, FinancialCalculator.parseAmount(""), 0.001)
    }

    @Test
    fun `parseAmount returns zero for non-numeric string`() {
        assertEquals(0.0, FinancialCalculator.parseAmount("N/A"), 0.001)
    }

    @Test
    fun `parseAmount returns zero for letters-only string`() {
        assertEquals(0.0, FinancialCalculator.parseAmount("abc"), 0.001)
    }

    // ── getCurrencyType  (STAT-F-003) ─────────────────────────────────────

    @Test
    fun `getCurrencyType returns USD for USD currency code`() {
        assertEquals("USD", FinancialCalculator.getCurrencyType("USD", ""))
    }

    @Test
    fun `getCurrencyType returns USD for Dollar word in currency`() {
        assertEquals("USD", FinancialCalculator.getCurrencyType("Dollar", ""))
    }

    @Test
    fun `getCurrencyType returns USD when account name contains USD`() {
        assertEquals("USD", FinancialCalculator.getCurrencyType("EGP", "USD Savings"))
    }

    @Test
    fun `getCurrencyType returns EUR for EUR currency code`() {
        assertEquals("EUR", FinancialCalculator.getCurrencyType("EUR", ""))
    }

    @Test
    fun `getCurrencyType returns EUR for Euro word in currency`() {
        assertEquals("EUR", FinancialCalculator.getCurrencyType("Euro", ""))
    }

    @Test
    fun `getCurrencyType returns EUR when account name contains EUR`() {
        assertEquals("EUR", FinancialCalculator.getCurrencyType("EGP", "EUR Account"))
    }

    @Test
    fun `getCurrencyType returns EGP by default`() {
        assertEquals("EGP", FinancialCalculator.getCurrencyType("EGP", "CIB Debit"))
    }

    @Test
    fun `getCurrencyType returns EGP for empty inputs`() {
        assertEquals("EGP", FinancialCalculator.getCurrencyType("", ""))
    }

    // ── convertToEGP  (STAT-F-002) ───────────────────────────────────────

    @Test
    fun `convertToEGP multiplies USD amount by usdRate`() {
        assertEquals(3000.0, FinancialCalculator.convertToEGP(100.0, "USD", "", 30.0, 35.0), 0.001)
    }

    @Test
    fun `convertToEGP multiplies EUR amount by eurRate`() {
        assertEquals(3500.0, FinancialCalculator.convertToEGP(100.0, "EUR", "", 30.0, 35.0), 0.001)
    }

    @Test
    fun `convertToEGP returns amount unchanged for EGP`() {
        assertEquals(1000.0, FinancialCalculator.convertToEGP(1000.0, "EGP", "CIB Debit", 30.0, 35.0), 0.001)
    }

    @Test
    fun `convertToEGP uses account name to detect USD when currency is EGP`() {
        assertEquals(3000.0, FinancialCalculator.convertToEGP(100.0, "EGP", "USD Savings", 30.0, 35.0), 0.001)
    }

    @Test
    fun `convertToEGP returns zero for zero amount`() {
        assertEquals(0.0, FinancialCalculator.convertToEGP(0.0, "USD", "", 30.0, 35.0), 0.001)
    }

    @Test
    fun `convertToEGP returns zero when usdRate is zero`() {
        assertEquals(0.0, FinancialCalculator.convertToEGP(100.0, "USD", "", 0.0, 35.0), 0.001)
    }

    // ── isExcludedFromSpending  (STAT-F-006) ─────────────────────────────

    private fun record(
        type: String = "Expense",
        category: String = "Groceries",
        comment: String = "",
        accountName: String = "CIB Debit"
    ) = Record(type = type, category = category, comment = comment, accountName = accountName)

    @Test
    fun `isExcludedFromSpending returns false for normal expense`() {
        assertFalse(FinancialCalculator.isExcludedFromSpending(record()))
    }

    @Test
    fun `isExcludedFromSpending returns true for Income records`() {
        assertTrue(FinancialCalculator.isExcludedFromSpending(record(type = "Income")))
    }

    @Test
    fun `isExcludedFromSpending returns true for credit category`() {
        assertTrue(FinancialCalculator.isExcludedFromSpending(record(category = "Credit")))
    }

    @Test
    fun `isExcludedFromSpending returns true for credit payment category`() {
        assertTrue(FinancialCalculator.isExcludedFromSpending(record(category = "Credit Payment")))
    }

    @Test
    fun `isExcludedFromSpending returns true for ATM withdrawal comment`() {
        assertTrue(FinancialCalculator.isExcludedFromSpending(record(comment = "ATM Withdrawal at branch")))
    }

    @Test
    fun `isExcludedFromSpending returns true for transfer account (arrow in name)`() {
        assertTrue(FinancialCalculator.isExcludedFromSpending(record(accountName = "CIB Debit -> Savings")))
    }

    @Test
    fun `isExcludedFromSpending returns true for instapay to credit`() {
        assertTrue(FinancialCalculator.isExcludedFromSpending(
            record(category = "Instapay outcome", comment = "paid credit card")
        ))
    }

    @Test
    fun `isExcludedFromSpending returns false for instapay outcome without credit`() {
        assertFalse(FinancialCalculator.isExcludedFromSpending(
            record(category = "Instapay outcome", comment = "sent to Ahmed")
        ))
    }
}
