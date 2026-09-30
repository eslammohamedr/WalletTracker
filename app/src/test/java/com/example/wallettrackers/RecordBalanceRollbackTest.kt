package com.example.wallettrackers

import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.RecordBalanceRollback
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordBalanceRollbackTest {
    @Test
    fun deletingSmsRecordRestoresPreSmsBalanceWhenAccountStillMatchesPrintedBalance() {
        val record = Record(
            amount = "0.03",
            type = "Expense",
            balanceBefore = "10000.00",
            balanceAfter = "9999.80"
        )

        assertEquals("10000.00", RecordBalanceRollback.restoredBalance("9999.80", record))
    }

    @Test
    fun deletionReversesOnlyRecordAmountWhenAccountChangedAfterward() {
        val record = Record(
            amount = "0.03",
            type = "Expense",
            balanceBefore = "10000.00",
            balanceAfter = "9999.80"
        )

        assertEquals("10000.03", RecordBalanceRollback.restoredBalance("10000.00", record))
    }

    @Test
    fun oldRecordsWithoutBalanceSnapshotKeepExistingRollbackBehavior() {
        val record = Record(amount = "0.03", type = "Expense")

        assertEquals("10000.00", RecordBalanceRollback.restoredBalance("9999.97", record))
    }

    @Test
    fun deletingForeignCurrencyExpenseWithoutBalanceSnapshotsDoesNotChangeNativeAccountBalance() {
        val record = Record(
            amount = "0.25",
            currency = "USD",
            type = "Expense",
            accountName = "TestCard",
            balanceBefore = "",
            balanceAfter = ""
        )

        assertEquals("3000.00", RecordBalanceRollback.restoredBalance("3000.00", record, "EGP"))
    }

    @Test
    fun sameCurrencyExpenseWithoutBalanceSnapshotsStillReversesBalance() {
        val record = Record(amount = "0.25", currency = "EGP", type = "Expense")

        assertEquals("3000.25", RecordBalanceRollback.restoredBalance("3000.00", record, "EGP"))
    }

    @Test
    fun deletingCreditSideOnlyPaymentReversesAvailableCreditIncrease() {
        val record = Record(
            amount = "47.29",
            category = "Credit Payment",
            type = "Expense",
            accountName = "TestCard"
        )

        assertEquals("3000.00", RecordBalanceRollback.restoredBalance("3047.29", record, "EGP"))
    }

    @Test
    fun printedBalanceSnapshotStillRestoresNativeBalanceForCurrencyMismatch() {
        val record = Record(
            amount = "0.25",
            currency = "USD",
            type = "Expense",
            balanceBefore = "3000.00",
            balanceAfter = "2999.75"
        )

        assertEquals("3000.00", RecordBalanceRollback.restoredBalance("2999.75", record, "EGP"))
    }
}
