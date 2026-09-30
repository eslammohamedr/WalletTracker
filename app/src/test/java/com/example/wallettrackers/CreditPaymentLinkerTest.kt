package com.example.wallettrackers

import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.util.CreditPaymentLinker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CreditPaymentLinkerTest {
    @Test
    fun `pending credit card identity wins over source digits parsed from debit SMS`() {
        val source = Account(id = "debit", name = "SecondBank", accountType = "Debit", last4Digits = "2222")
        val card = Account(id = "card", name = "TestCard", accountType = "Credit Card", last4Digits = "3333")

        val resolved = CreditPaymentLinker.findCreditAccount(
            accounts = listOf(source, card),
            pendingCreditDigits = "3333",
            smsCreditDigits = "2222",
            partialRecordAccountId = ""
        )

        assertEquals(card.id, resolved?.id)
    }

    @Test
    fun `pending credit card identity wins over unrelated partial record account`() {
        val source = Account(id = "debit", name = "SecondBank", accountType = "Debit", last4Digits = "2222")
        val card = Account(id = "card", name = "TestCard", accountType = "Credit Card", last4Digits = "3333")
        val otherCard = Account(id = "other-card", name = "OtherCard", accountType = "Credit Card", last4Digits = "4444")

        val resolved = CreditPaymentLinker.findCreditAccount(
            accounts = listOf(source, card, otherCard),
            pendingCreditDigits = "3333",
            smsCreditDigits = "2222",
            partialRecordAccountId = otherCard.id
        )

        assertEquals(card.id, resolved?.id)
    }

    @Test
    fun `partial record identifies correct card when duplicate suffixes exist`() {
        val source = Account(id = "debit", name = "SecondBank", accountType = "Debit", last4Digits = "2222")
        val firstCard = Account(id = "card-a", name = "FirstCard", accountType = "Credit Card", last4Digits = "3333")
        val secondCard = Account(id = "card-b", name = "SecondCard", accountType = "Credit Card", last4Digits = "3333")

        val resolved = CreditPaymentLinker.findCreditAccount(
            accounts = listOf(source, firstCard, secondCard),
            pendingCreditDigits = "3333",
            smsCreditDigits = "2222",
            partialRecordAccountId = secondCard.id
        )

        assertEquals(secondCard.id, resolved?.id)
    }

    @Test
    fun `ambiguous duplicate card suffix without matching partial identity stays unresolved`() {
        val firstCard = Account(id = "card-a", name = "FirstCard", accountType = "Credit Card", last4Digits = "3333")
        val secondCard = Account(id = "card-b", name = "SecondCard", accountType = "Credit Card", last4Digits = "3333")
        val unrelatedCard = Account(id = "card-c", name = "OtherCard", accountType = "Credit Card", last4Digits = "4444")

        val resolved = CreditPaymentLinker.findCreditAccount(
            accounts = listOf(firstCard, secondCard, unrelatedCard),
            pendingCreditDigits = "3333",
            smsCreditDigits = "",
            partialRecordAccountId = unrelatedCard.id
        )

        assertNull(resolved)
    }

    @Test
    fun `completed partial stores exact account names used for transfer rollback`() {
        val source = Account(id = "debit", name = "SecondBank", accountType = "Debit", currency = "EGP")
        val card = Account(id = "card", name = "TestCard", accountType = "Credit Card")
        val partial = Record(id = "payment", accountId = card.id, accountName = card.name, amount = "600.00")

        val completed = CreditPaymentLinker.completePartialRecord(partial, source, card)

        assertEquals(source.id, completed.accountId)
        assertEquals("SecondBank -> TestCard", completed.accountName)
        assertEquals(source.currency, completed.currency)
        assertEquals("600.00", completed.transferDestinationAmount)
    }

    @Test
    fun `debit-first fee difference adjusts card amount once and records destination amount`() {
        val debitFirstRecord = Record(
            id = "payment",
            amount = "105.00",
            category = "Credit Payment",
            accountName = "SecondBank -> TestCard"
        )

        val firstReconciliation = CreditPaymentLinker.reconcileCompletedPayment(debitFirstRecord, 100.0)
        val duplicateReconciliation = CreditPaymentLinker.reconcileCompletedPayment(firstReconciliation.record, 100.0)

        assertEquals(-5.0, firstReconciliation.cardBalanceAdjustment, 0.0)
        assertEquals("100.00", firstReconciliation.record.transferDestinationAmount)
        assertEquals(0.0, duplicateReconciliation.cardBalanceAdjustment, 0.0)
    }

    @Test
    fun `printed card balance suppresses fee adjustment already reflected by SMS`() {
        val debitFirstRecord = Record(
            id = "payment",
            amount = "105.00",
            category = "Credit Payment",
            accountName = "SecondBank -> TestCard"
        )

        val reconciliation = CreditPaymentLinker.reconcileCompletedPayment(
            debitFirstRecord,
            creditedAmount = 100.0,
            cardBalanceAlreadyPrinted = true
        )

        assertEquals(0.0, reconciliation.cardBalanceAdjustment, 0.0)
        assertEquals("100.00", reconciliation.record.transferDestinationAmount)
    }

    @Test
    fun `printed Banque Misr receipt balance replaces provisional transfer destination amount`() {
        val debitFirstRecord = Record(
            id = "payment",
            amount = "6630.00",
            category = "Credit Payment",
            accountName = "HSBC Main -> Banque Misr Card 7000",
            transferDestinationAmount = "6630.00"
        )

        val reconciliation = CreditPaymentLinker.reconcileCompletedPayment(
            debitFirstRecord,
            creditedAmount = 6700.0,
            cardBalanceAlreadyPrinted = true
        )

        assertEquals("6700.00", reconciliation.record.transferDestinationAmount)
        assertEquals(0.0, reconciliation.cardBalanceAdjustment, 0.0)
    }
}
