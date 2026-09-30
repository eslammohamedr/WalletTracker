package com.example.wallettrackers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.CreditPaymentMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Date

@RunWith(AndroidJUnit4::class)
class CreditPaymentMatcherComponentTest {
    @Test
    fun rejectsUnrelatedExpensePreviouslyWithinSmsCenterTolerance() {
        assertFalse(CreditPaymentMatcher.amountsMatch("0.21", "100.21"))
    }

    @Test
    fun keepsTheDocumentedSmallFeeTolerance() {
        assertTrue(CreditPaymentMatcher.amountsMatch("250.00", "255.10"))
        assertFalse(CreditPaymentMatcher.amountsMatch("250.00", "255.11"))
    }

    @Test
    fun fallbackPairsOnlyOneRecentInstapayRecord() {
        val eventTime = 1_000_000L
        val instapay = Record(
            id = "instapay",
            amount = "100.00",
            category = "Instapay outcome",
            type = "Expense",
            accountName = "MainBank",
            timestamp = Date(eventTime)
        )
        val unrelated = instapay.copy(id = "purchase", category = "Groceries")

        assertTrue(CreditPaymentMatcher.findMatchingDebitExpense(listOf(instapay), "100.00", eventTime) == instapay)
        assertTrue(CreditPaymentMatcher.findMatchingDebitExpense(listOf(unrelated), "100.00", eventTime) == null)
        assertTrue(CreditPaymentMatcher.findMatchingDebitExpense(listOf(instapay, instapay.copy(id = "second")), "100.00", eventTime) == null)
    }

    @Test
    fun banqueMisrReceiptOnlyReconcilesAnUnambiguousRecentCardTransfer() {
        val eventTime = 1_000_000L
        val transfer = Record(
            id = "debit-first",
            amount = "6630.00",
            category = "Credit Payment",
            type = "Expense",
            accountName = "HSBC Main -> Banque Misr Card 7000",
            timestamp = Date(eventTime - 30_000L)
        )
        val unrelatedOlderTransfer = transfer.copy(id = "older", timestamp = Date(eventTime - 11 * 60_000L))

        assertEquals(
            transfer,
            CreditPaymentMatcher.findCompletedPayment(
                listOf(transfer, unrelatedOlderTransfer),
                "6700.00",
                "Banque Misr Card 7000",
                eventTime
            )
        )
        assertTrue(CreditPaymentMatcher.findCompletedPayment(
            listOf(transfer, transfer.copy(id = "ambiguous", amount = "6699.00")),
            "6700.00",
            "Banque Misr Card 7000",
            eventTime
        ) == null)
    }
}
