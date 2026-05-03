package com.example.wallettrackers

import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.FinancialCalculator
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

/**
 * Unit tests for FinancialCalculator.exportToCsvString — covers RPT-F-001 through RPT-F-004
 */
class CsvExportTest {

    private fun dateFor(year: Int, month: Int, day: Int, hour: Int = 10, minute: Int = 30) =
        Calendar.getInstance().apply {
            set(year, month, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

    // ── RPT-F-001: header row ────────────────────────────────────────────

    @Test
    fun `exportToCsvString first line is header row`() {
        val csv = FinancialCalculator.exportToCsvString(emptyList())
        val firstLine = csv.lines().first()
        assertEquals("Date,Account,Category,Type,Amount,Currency,Comment,Balance After", firstLine)
    }

    @Test
    fun `exportToCsvString empty list produces only header`() {
        val csv = FinancialCalculator.exportToCsvString(emptyList())
        val nonBlankLines = csv.lines().filter { it.isNotBlank() }
        assertEquals(1, nonBlankLines.size)
    }

    // ── RPT-F-002: column values ─────────────────────────────────────────

    @Test
    fun `exportToCsvString formats date as yyyy-MM-dd HH-mm`() {
        val record = Record(
            timestamp   = dateFor(2026, 3, 15, 14, 30),   // April 15 2026, 14:30
            accountName = "CIB Debit",
            category    = "Groceries",
            type        = "Expense",
            amount      = "250.00",
            currency    = "EGP",
            comment     = "Carrefour",
            balanceAfter = "9750.00"
        )
        val dataLine = FinancialCalculator.exportToCsvString(listOf(record)).lines()[1]
        assertTrue(dataLine.startsWith("2026-04-15 14:30"))
    }

    @Test
    fun `exportToCsvString wraps account name in quotes`() {
        val record = Record(accountName = "CIB Debit", category = "Groceries", amount = "100")
        val dataLine = FinancialCalculator.exportToCsvString(listOf(record)).lines()[1]
        assertTrue(dataLine.contains("\"CIB Debit\""))
    }

    @Test
    fun `exportToCsvString wraps category in quotes`() {
        val record = Record(accountName = "CIB", category = "Food & Drinks", amount = "100")
        val dataLine = FinancialCalculator.exportToCsvString(listOf(record)).lines()[1]
        assertTrue(dataLine.contains("\"Food & Drinks\""))
    }

    @Test
    fun `exportToCsvString wraps comment in quotes`() {
        val record = Record(accountName = "CIB", category = "Groceries", amount = "100",
                            comment = "Carrefour, Maadi")
        val dataLine = FinancialCalculator.exportToCsvString(listOf(record)).lines()[1]
        assertTrue(dataLine.contains("\"Carrefour, Maadi\""))
    }

    @Test
    fun `exportToCsvString writes amount without quotes`() {
        val record = Record(accountName = "CIB", category = "Groceries",
                            amount = "250.00", currency = "EGP")
        val dataLine = FinancialCalculator.exportToCsvString(listOf(record)).lines()[1]
        assertTrue(dataLine.contains(",250.00,"))
    }

    @Test
    fun `exportToCsvString writes currency without quotes`() {
        val record = Record(accountName = "CIB", category = "Groceries",
                            amount = "250.00", currency = "EGP")
        val dataLine = FinancialCalculator.exportToCsvString(listOf(record)).lines()[1]
        assertTrue(dataLine.contains(",EGP,"))
    }

    // ── RPT-F-003: multiple records ──────────────────────────────────────

    @Test
    fun `exportToCsvString produces one data row per record`() {
        val records = listOf(
            Record(accountName = "CIB",     category = "Groceries", amount = "100"),
            Record(accountName = "CIB",     category = "Uber",      amount = "85"),
            Record(accountName = "Savings", category = "Salary",    amount = "12000", type = "Income")
        )
        val nonBlankLines = FinancialCalculator.exportToCsvString(records)
            .lines().filter { it.isNotBlank() }
        assertEquals(4, nonBlankLines.size)   // 1 header + 3 data
    }

    @Test
    fun `exportToCsvString preserves record order`() {
        val records = listOf(
            Record(accountName = "First",  category = "Groceries", amount = "100"),
            Record(accountName = "Second", category = "Uber",      amount = "85")
        )
        val lines = FinancialCalculator.exportToCsvString(records)
            .lines().filter { it.isNotBlank() }
        assertTrue(lines[1].contains("\"First\""))
        assertTrue(lines[2].contains("\"Second\""))
    }

    // ── RPT-F-004: transfer records included ────────────────────────────

    @Test
    fun `exportToCsvString includes transfer records`() {
        val record = Record(
            accountName = "CIB Debit -> Savings",
            category    = "Transfer",
            amount      = "1000",
            type        = "Expense"
        )
        val csv = FinancialCalculator.exportToCsvString(listOf(record))
        assertTrue(csv.contains("Transfer"))
        assertTrue(csv.contains("CIB Debit -> Savings"))
    }
}
