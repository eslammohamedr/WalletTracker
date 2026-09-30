package com.example.wallettrackers

import com.example.wallettrackers.util.CreditPaymentMatcher
import com.example.wallettrackers.model.Record
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class CreditPaymentMatcherTest {
    @Test
    fun `rejects unrelated 500 and 600 payment amounts`() {
        assertFalse(CreditPaymentMatcher.amountsMatch("500.00", "600.00"))
    }

    @Test
    fun `rejects SMS Center false match within old one hundred unit tolerance`() {
        assertFalse(CreditPaymentMatcher.amountsMatch("0.21", "100.21"))
    }

    @Test
    fun `debit fallback requires a unique recent Instapay expense`() {
        val eventTime = 1_000_000L
        val candidate = Record(
            id = "instapay",
            amount = "100.00",
            category = "Instapay outcome",
            type = "Expense",
            accountName = "MainBank",
            timestamp = Date(eventTime - 60_000L)
        )
        val unrelated = candidate.copy(id = "purchase", category = "Groceries")
        val duplicate = candidate.copy(id = "instapay-duplicate")

        assertEquals(candidate, CreditPaymentMatcher.findMatchingDebitExpense(listOf(candidate), "100.00", eventTime))
        assertNull(CreditPaymentMatcher.findMatchingDebitExpense(listOf(unrelated), "100.00", eventTime))
        assertNull(CreditPaymentMatcher.findMatchingDebitExpense(listOf(candidate, duplicate), "100.00", eventTime))
    }

    @Test
    fun `debit fallback rejects matching Instapay expense outside ten minute window`() {
        val eventTime = 1_000_000L
        val staleCandidate = Record(
            id = "stale-instapay",
            amount = "100.00",
            category = "Instapay outcome",
            type = "Expense",
            accountName = "MainBank",
            timestamp = Date(eventTime - CreditPaymentMatcher.MAX_DEBIT_PAIR_WINDOW_MILLIS - 1)
        )

        assertNull(CreditPaymentMatcher.findMatchingDebitExpense(listOf(staleCandidate), "100.00", eventTime))
    }

    @Test
    fun `accepts equal payment amounts`() {
        assertTrue(CreditPaymentMatcher.amountsMatch("600.00", "600.00"))
    }

    @Test
    fun `accepts small payment fee within five currency units`() {
        assertTrue(CreditPaymentMatcher.amountsMatch(600.0, 605.0))
    }

    @Test
    fun `accepts payment fee within two percent`() {
        assertTrue(CreditPaymentMatcher.amountsMatch(1000.0, 1020.0))
    }

    @Test
    fun `rejects fee just outside supported tolerance`() {
        assertFalse(CreditPaymentMatcher.amountsMatch(1000.0, 1021.0))
    }

    @Test
    fun `accepts the last cent inside the percentage fee tolerance`() {
        assertTrue(CreditPaymentMatcher.amountsMatch(250.0, 255.10))
    }

    @Test
    fun `rejects the first cent outside the percentage fee tolerance`() {
        assertFalse(CreditPaymentMatcher.amountsMatch(250.0, 255.11))
    }

    @Test
    fun `selects same amount pending payment by explicit destination card`() {
        val first = CreditPaymentMatcher.PendingPayment("first", "credit-sms-1", "3333", "47.29", 10L)
        val second = CreditPaymentMatcher.PendingPayment("second", "credit-sms-2", "4444", "47.29", 20L)

        assertEquals(first, CreditPaymentMatcher.selectPendingPayment(listOf(first, second), "47.29", "3333"))
        assertEquals(second, CreditPaymentMatcher.selectPendingPayment(listOf(first, second), "47.29", "4444"))
    }

    @Test
    fun `does not arbitrarily select among same amount pending cards without destination`() {
        val first = CreditPaymentMatcher.PendingPayment("first", "credit-sms-1", "3333", "47.29", 10L)
        val second = CreditPaymentMatcher.PendingPayment("second", "credit-sms-2", "4444", "47.29", 20L)

        assertNull(CreditPaymentMatcher.selectPendingPayment(listOf(first, second), "47.29", ""))
    }

    @Test
    fun `completed payment lookup ignores partial and different card records`() {
        val now = 1_000_000L
        val partial = Record(category = "Credit Payment", amount = "47.29", accountName = "TestCard", timestamp = Date(now))
        val otherCard = Record(category = "Credit Payment", amount = "47.29", accountName = "SecondBank -> SecondCard", timestamp = Date(now))
        val matchingCard = Record(category = "Credit Payment", amount = "47.29", accountName = "SecondBank -> TestCard", timestamp = Date(now))

        assertEquals(matchingCard, CreditPaymentMatcher.findCompletedPayment(
            listOf(partial, otherCard, matchingCard), "47.29", "TestCard", now
        ))
    }

    @Test
    fun `completed payment lookup ignores stale or ambiguous close matches`() {
        val eventTime = 1_000_000L
        val cardPayment = Record(
            id = "payment",
            category = "Credit Payment",
            amount = "6630.00",
            accountName = "HSBC Main -> Banque Misr Card 7000",
            timestamp = Date(eventTime - 60_000L)
        )
        val stale = cardPayment.copy(id = "stale", timestamp = Date(eventTime - 11 * 60_000L))

        assertEquals(
            cardPayment,
            CreditPaymentMatcher.findCompletedPayment(listOf(cardPayment), "6700.00", "Banque Misr Card 7000", eventTime)
        )
        assertNull(CreditPaymentMatcher.findCompletedPayment(
            listOf(cardPayment, cardPayment.copy(id = "ambiguous", amount = "6699.00")),
            "6700.00",
            "Banque Misr Card 7000",
            eventTime
        ))
        assertNull(CreditPaymentMatcher.findCompletedPayment(
            listOf(stale), "6700.00", "Banque Misr Card 7000", eventTime
        ))
    }
}
