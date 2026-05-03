package com.example.wallettrackers

import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.BudgetCalculator
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Date

/**
 * Unit tests for BudgetCalculator — covers BUD-F-005 through BUD-F-009
 * Run with: ./gradlew test
 */
class BudgetCalculatorTest {

    // ── helpers ──────────────────────────────────────────────────────────

    private val subcategoryMap = mapOf(
        "Food & Drinks" to listOf("Groceries", "Fast food", "Restaurants", "Cafe"),
        "Shopping"      to listOf("Clothes", "Electronics", "Health and beauty"),
        "Housing"       to listOf("Rent", "Electricity", "Internet", "Mobile"),
        "Transportation" to listOf("Uber", "Public transportation")
    )

    private fun dateFor(year: Int, month: Int, day: Int): Date =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)       // 0-indexed
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.time

    private fun expense(
        category: String,
        amount: String,
        month: Int = 3,    // April (0-indexed)
        year: Int = 2026,
        type: String = "Expense",
        accountName: String = "CIB Debit"
    ) = Record(
        category = category,
        amount = amount,
        type = type,
        timestamp = dateFor(year, month, 15),
        accountName = accountName
    )

    // ── BUD-F-005: sums expenses for exact category in the given month ───

    @Test
    fun `spentInMonth sums matching category records`() {
        val records = listOf(
            expense("Groceries", "200.00"),
            expense("Groceries", "150.50"),
            expense("Restaurants", "80.00")
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(350.50, result, 0.01)
    }

    // ── BUD-F-007: parent budget counts subcategory spending ─────────────

    @Test
    fun `spentInMonth includes subcategory spending under parent budget`() {
        val records = listOf(
            expense("Groceries",   "300.00"),
            expense("Restaurants", "200.00"),
            expense("Cafe",        "50.00"),
            expense("Uber",        "100.00")   // different parent
        )
        val result = BudgetCalculator.spentInMonth(records, "Food & Drinks", month = 3, year = 2026, subcategoryMap)
        assertEquals(550.00, result, 0.01)
    }

    @Test
    fun `spentInMonth does not count subcategories of a different parent`() {
        val records = listOf(
            expense("Uber",   "100.00"),
            expense("Clothes", "250.00")
        )
        // Budget is for "Transportation" — should only count Uber
        val result = BudgetCalculator.spentInMonth(records, "Transportation", month = 3, year = 2026, subcategoryMap)
        assertEquals(100.00, result, 0.01)
    }

    // ── BUD-F-008: excluded record types ─────────────────────────────────

    @Test
    fun `spentInMonth excludes Transfer records`() {
        val records = listOf(
            expense("Groceries", "200.00"),
            expense("Transfer",  "500.00")
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(200.00, result, 0.01)
    }

    @Test
    fun `spentInMonth excludes Credit Payment records`() {
        val records = listOf(
            expense("Groceries",       "200.00"),
            expense("Credit Payment",  "1000.00")
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(200.00, result, 0.01)
    }

    @Test
    fun `spentInMonth excludes transfer-account records (arrow in account name)`() {
        val records = listOf(
            expense("Groceries", "200.00", accountName = "CIB Debit"),
            expense("Groceries", "150.00", accountName = "CIB Debit -> Savings")
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(200.00, result, 0.01)
    }

    @Test
    fun `spentInMonth excludes Income records`() {
        val records = listOf(
            expense("Groceries", "200.00"),
            expense("Groceries", "300.00", type = "Income")
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(200.00, result, 0.01)
    }

    // ── BUD-F-006: month/year isolation ──────────────────────────────────

    @Test
    fun `spentInMonth only counts records from the specified month`() {
        val records = listOf(
            expense("Groceries", "200.00", month = 3, year = 2026),  // April 2026 ✓
            expense("Groceries", "500.00", month = 2, year = 2026),  // March 2026 ✗
            expense("Groceries", "100.00", month = 3, year = 2025)   // April 2025 ✗
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(200.00, result, 0.01)
    }

    @Test
    fun `spentInMonth returns zero when no records match`() {
        val records = listOf(
            expense("Groceries", "200.00", month = 2, year = 2026)
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(0.0, result, 0.01)
    }

    @Test
    fun `spentInMonth returns zero for empty record list`() {
        val result = BudgetCalculator.spentInMonth(emptyList(), "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(0.0, result, 0.01)
    }

    // ── BUD-F-009: invalid amount strings are treated as zero ─────────────

    @Test
    fun `spentInMonth skips records with non-numeric amount`() {
        val records = listOf(
            expense("Groceries", "200.00"),
            expense("Groceries", "N/A")
        )
        val result = BudgetCalculator.spentInMonth(records, "Groceries", month = 3, year = 2026, subcategoryMap)
        assertEquals(200.00, result, 0.01)
    }

    // ── BUD-F-007 edge: unknown category with no subcategory map entry ────

    @Test
    fun `spentInMonth handles category not in subcategoryMap`() {
        val records = listOf(
            expense("Others", "100.00")
        )
        // "Others" has no entry in subcategoryMap — should still count direct matches
        val result = BudgetCalculator.spentInMonth(records, "Others", month = 3, year = 2026, subcategoryMap)
        assertEquals(100.00, result, 0.01)
    }
}
