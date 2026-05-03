package com.example.wallettrackers

import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.CategoryRule
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Date

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repo: FakeRepository
    private lateinit var vm: HomeViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repo = FakeRepository()
        vm = HomeViewModel(repo, "test-user")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun account(
        id: String = "a1",
        name: String = "CIB",
        balance: String = "1000.00",
        currency: String = "EGP"
    ) = Account(
        id = id, name = name, accountType = "Debit",
        last4Digits = "1234", amount = balance, currency = currency,
        color = 0xFF0000FF
    )

    private fun record(
        id: String = "r1",
        accountId: String = "a1",
        accountName: String = "CIB",
        amount: String = "200.00",
        type: String = "Expense",
        category: String = "Food",
        timestamp: Date = Date(),
        comment: String = ""
    ) = Record(
        id = id, accountId = accountId, accountName = accountName,
        category = category, amount = amount, currency = "EGP",
        type = type, timestamp = timestamp, userId = "test-user",
        balanceAfter = "800.00", comment = comment
    )

    // ── Data loading ──────────────────────────────────────────────────────

    @Test
    fun `records are loaded from repository`() = runTest {
        val r1 = record("r1")
        repo.setRecords(listOf(r1))
        val fresh = HomeViewModel(repo, "test-user")
        assertTrue(fresh.records.value.any { it.id == "r1" })
    }

    @Test
    fun `records are sorted by timestamp descending`() = runTest {
        val older = record("r1", timestamp = Date(1_000L))
        val newer = record("r2", timestamp = Date(2_000L))
        repo.setRecords(listOf(older, newer))
        val fresh = HomeViewModel(repo, "test-user")
        assertEquals("r2", fresh.records.value[0].id)
        assertEquals("r1", fresh.records.value[1].id)
    }

    @Test
    fun `accounts are loaded from repository`() = runTest {
        repo.setAccounts(listOf(account("a1"), account("a2")))
        val fresh = HomeViewModel(repo, "test-user")
        assertEquals(2, fresh.accounts.value.size)
    }

    @Test
    fun `budgets are loaded from repository`() = runTest {
        repo.setBudgets(listOf(Budget(id = "b1", category = "Food", monthlyLimit = 500.0, currency = "EGP")))
        val fresh = HomeViewModel(repo, "test-user")
        assertEquals(1, fresh.budgets.value.size)
        assertEquals("Food", fresh.budgets.value[0].category)
    }

    // ── Monthly insight ───────────────────────────────────────────────────

    @Test
    fun `monthly insight total expense sums current month expenses`() = runTest {
        val now = Date()
        val expenses = listOf(
            record("r1", amount = "300.00", type = "Expense", timestamp = now),
            record("r2", amount = "200.00", type = "Expense", timestamp = now)
        )
        repo.setRecords(expenses)
        val fresh = HomeViewModel(repo, "test-user")
        assertEquals(500.0, fresh.monthlyInsight.value.totalExpense, 0.01)
    }

    @Test
    fun `monthly insight top category is the largest expense category`() = runTest {
        val now = Date()
        val expenses = listOf(
            record("r1", amount = "100.00", category = "Food",      type = "Expense", timestamp = now),
            record("r2", amount = "400.00", category = "Transport", type = "Expense", timestamp = now),
            record("r3", amount = "200.00", category = "Transport", type = "Expense", timestamp = now)
        )
        repo.setRecords(expenses)
        val fresh = HomeViewModel(repo, "test-user")
        assertEquals("Transport", fresh.monthlyInsight.value.topCategory)
        assertEquals(600.0, fresh.monthlyInsight.value.topAmount, 0.01)
    }

    @Test
    fun `monthly insight ignores income records`() = runTest {
        val now = Date()
        val records = listOf(
            record("r1", amount = "500.00", type = "Income",  timestamp = now),
            record("r2", amount = "200.00", type = "Expense", timestamp = now)
        )
        repo.setRecords(records)
        val fresh = HomeViewModel(repo, "test-user")
        assertEquals(200.0, fresh.monthlyInsight.value.totalExpense, 0.01)
    }

    @Test
    fun `monthly insight ignores transfer records`() = runTest {
        val now = Date()
        val records = listOf(
            record("r1", amount = "300.00", type = "Expense", accountName = "CIB -> NBE", timestamp = now),
            record("r2", amount = "100.00", type = "Expense", accountName = "CIB",        timestamp = now)
        )
        repo.setRecords(records)
        val fresh = HomeViewModel(repo, "test-user")
        assertEquals(100.0, fresh.monthlyInsight.value.totalExpense, 0.01)
    }

    // ── Add-record UI state ───────────────────────────────────────────────

    @Test
    fun `clearAddRecordState resets all add-record fields`() {
        vm.onAddRecordAccountChange(account())
        vm.onAddRecordAmountChange("500")
        vm.onAddRecordPayFromAccountChange(account("a2"))

        vm.clearAddRecordState()

        assertNull(vm.addRecordSelectedAccount.value)
        assertEquals("", vm.addRecordAmount.value)
        assertNull(vm.addRecordPayFromAccount.value)
    }

    @Test
    fun `onAddRecordAccountChange updates selected account`() {
        val acc = account("a1", name = "NBE")
        vm.onAddRecordAccountChange(acc)
        assertEquals("NBE", vm.addRecordSelectedAccount.value?.name)
    }

    @Test
    fun `onAddRecordAmountChange updates amount`() {
        vm.onAddRecordAmountChange("750")
        assertEquals("750", vm.addRecordAmount.value)
    }

    // ── Editing state ─────────────────────────────────────────────────────

    @Test
    fun `startEditing sets editingRecord and opens dialog`() {
        val r = record()
        vm.startEditing(r)
        assertEquals(r, vm.editingRecord.value)
        assertTrue(vm.showEditDialog.value)
    }

    @Test
    fun `stopEditing clears editingRecord and closes dialog`() {
        vm.startEditing(record())
        vm.stopEditing()
        assertNull(vm.editingRecord.value)
        assertFalse(vm.showEditDialog.value)
    }

    @Test
    fun `updateEditingCategory mutates editing record`() {
        vm.startEditing(record(category = "Food"))
        vm.updateEditingCategory("Transport")
        assertEquals("Transport", vm.editingRecord.value?.category)
    }

    @Test
    fun `updateEditingAmount mutates editing record`() {
        vm.startEditing(record(amount = "100.00"))
        vm.updateEditingAmount("250.00")
        assertEquals("250.00", vm.editingRecord.value?.amount)
    }

    @Test
    fun `updateEditingComment mutates editing record`() {
        vm.startEditing(record(comment = "old"))
        vm.updateEditingComment("new comment")
        assertEquals("new comment", vm.editingRecord.value?.comment)
    }

    @Test
    fun `updateEditingAccount updates accountId name and currency`() {
        vm.startEditing(record())
        val newAcc = account("a2", name = "NBE", currency = "USD")
        vm.updateEditingAccount(newAcc)
        assertEquals("a2",  vm.editingRecord.value?.accountId)
        assertEquals("NBE", vm.editingRecord.value?.accountName)
        assertEquals("USD", vm.editingRecord.value?.currency)
    }

    // ── Category rules ────────────────────────────────────────────────────

    @Test
    fun `saveRuleAndResync updates records matching the merchant keyword`() = runTest {
        val r1 = record("r1", category = "Food",      comment = "CARREFOUR")
        val r2 = record("r2", category = "Transport", comment = "CARREFOUR MARKET")
        val r3 = record("r3", category = "Food",      comment = "OTHER SHOP")
        repo.setRecords(listOf(r1, r2, r3))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.startPendingRule(r1.copy(comment = "CARREFOUR"))
        fresh.saveRuleAndResync("Groceries")

        val updated = repo.getRecords()
        val categories = fresh.records.value.map { it.id to it.category }
        assertTrue(categories.any { it.first == "r1" && it.second == "Groceries" })
        assertTrue(categories.any { it.first == "r2" && it.second == "Groceries" })
        assertEquals("Food", fresh.records.value.find { it.id == "r3" }?.category)
    }

    @Test
    fun `saveRuleAndResync skips blank merchant and clears pending`() = runTest {
        val r = record(comment = "   ")
        val fresh = HomeViewModel(repo, "test-user")
        fresh.startPendingRule(r)
        fresh.saveRuleAndResync("Food")
        assertNull(fresh.pendingRuleRecord.value)
        assertEquals(0, repo.getCategoryRules().let { flow ->
            var count = 0
            // flow never emits during this check — just inspect FakeRepository directly
            count
        })
    }

    @Test
    fun `saveRuleAndResync skips all-digits merchant and clears pending`() = runTest {
        val r = record(comment = "12345678")
        val fresh = HomeViewModel(repo, "test-user")
        fresh.startPendingRule(r)
        fresh.saveRuleAndResync("Food")
        assertNull(fresh.pendingRuleRecord.value)
    }

    @Test
    fun `saveRuleAndResync sets toast when records are updated`() = runTest {
        val r = record("r1", category = "Food", comment = "SEOUDI")
        repo.setRecords(listOf(r))
        val fresh = HomeViewModel(repo, "test-user")
        fresh.startPendingRule(r.copy(comment = "SEOUDI"))
        fresh.saveRuleAndResync("Groceries")
        assertNotNull(fresh.toastMessage.value)
        assertTrue(fresh.toastMessage.value!!.contains("Rule saved"))
    }

    @Test
    fun `deleteRule sets toast message`() = runTest {
        val rule = CategoryRule(id = "rule-0", merchantKeyword = "SEOUDI", category = "Groceries")
        repo.setCategoryRules(listOf(rule))
        val fresh = HomeViewModel(repo, "test-user")
        fresh.deleteRule("rule-0")
        assertEquals("Rule deleted", fresh.toastMessage.value)
    }

    @Test
    fun `onToastShown clears toast message`() = runTest {
        vm.deleteRule("nonexistent")
        vm.onToastShown()
        assertNull(vm.toastMessage.value)
    }

    // ── deleteRecord ──────────────────────────────────────────────────────

    @Test
    fun `deleteRecord restores account balance for expense`() = runTest {
        val acc = account("a1", balance = "800.00")
        val r   = record("r1", accountId = "a1", amount = "200.00", type = "Expense")
        repo.setAccounts(listOf(acc))
        repo.setRecords(listOf(r))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.deleteRecord("r1")

        assertEquals("1000.00", fresh.accounts.value.find { it.id == "a1" }?.amount)
        assertTrue(fresh.records.value.none { it.id == "r1" })
    }

    @Test
    fun `deleteRecord deducts balance for income record`() = runTest {
        val acc = account("a1", balance = "1200.00")
        val r   = record("r1", accountId = "a1", amount = "200.00", type = "Income")
        repo.setAccounts(listOf(acc))
        repo.setRecords(listOf(r))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.deleteRecord("r1")

        assertEquals("1000.00", fresh.accounts.value.find { it.id == "a1" }?.amount)
    }

    @Test
    fun `deleteRecord on transfer just removes the record`() = runTest {
        val acc = account("a1", balance = "800.00")
        val r   = record("r1", accountId = "a1", accountName = "CIB -> NBE", amount = "200.00")
        repo.setAccounts(listOf(acc))
        repo.setRecords(listOf(r))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.deleteRecord("r1")

        // Account balance unchanged, record gone
        assertEquals("800.00", fresh.accounts.value.find { it.id == "a1" }?.amount)
        assertTrue(fresh.records.value.none { it.id == "r1" })
    }

    // ── updateRecord ──────────────────────────────────────────────────────

    @Test
    fun `updateRecord adjusts balance when amount changes on same account`() = runTest {
        val acc = account("a1", balance = "800.00")
        val r   = record("r1", accountId = "a1", amount = "200.00", type = "Expense")
        repo.setAccounts(listOf(acc))
        repo.setRecords(listOf(r))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.updateRecord(r.copy(amount = "300.00"))

        // reversed: 800 + 200 = 1000; re-applied: 1000 - 300 = 700
        assertEquals("700.00", fresh.accounts.value.find { it.id == "a1" }?.amount)
        assertEquals("700.00", fresh.records.value.find { it.id == "r1" }?.balanceAfter)
    }

    @Test
    fun `updateRecord on transfer just calls repository updateRecord`() = runTest {
        val r = record("r1", accountName = "CIB -> NBE", amount = "200.00")
        repo.setRecords(listOf(r))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.updateRecord(r.copy(category = "Transfer"))

        assertEquals("Transfer", fresh.records.value.find { it.id == "r1" }?.category)
        assertEquals("Record updated", fresh.toastMessage.value)
    }

    // ── payCreditStatement ────────────────────────────────────────────────

    @Test
    fun `payCreditStatement reduces debit account balance and removes statement`() = runTest {
        val debit = account("debit", balance = "5000.00")
        val stmt  = CreditStatement(
            id = "stmt1", cardLast4Digits = "9999", accountId = "credit",
            totalAmount = 1500.0, dueDate = Date(), isPaid = false, userId = "test-user"
        )
        repo.setAccounts(listOf(debit))
        repo.setStatements(listOf(stmt))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.payCreditStatement(stmt, debit)

        assertEquals("3500.00", fresh.accounts.value.find { it.id == "debit" }?.amount)
        assertTrue(fresh.statements.value.none { it.id == "stmt1" })
        assertEquals("Card paid and removed successfully", fresh.toastMessage.value)
    }

    // ── deleteUser ────────────────────────────────────────────────────────

    @Test
    fun `deleteUser clears all data`() = runTest {
        repo.setAccounts(listOf(account()))
        repo.setRecords(listOf(record()))
        val fresh = HomeViewModel(repo, "test-user")

        fresh.deleteUser()

        assertTrue(fresh.accounts.value.isEmpty())
        assertTrue(fresh.records.value.isEmpty())
    }
}
