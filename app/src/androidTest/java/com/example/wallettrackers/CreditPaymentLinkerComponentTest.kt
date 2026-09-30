package com.example.wallettrackers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.util.CreditPaymentLinker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreditPaymentLinkerComponentTest {
    @Test
    fun pendingRecordSelectsCorrectCardWhenSuffixIsShared() {
        val firstCard = Account(id = "first", accountType = "Credit Card", last4Digits = "3333")
        val secondCard = Account(id = "second", accountType = "Credit Card", last4Digits = "3333")

        val resolved = CreditPaymentLinker.findCreditAccount(
            accounts = listOf(firstCard, secondCard),
            pendingCreditDigits = "3333",
            smsCreditDigits = "",
            partialRecordAccountId = secondCard.id
        )

        assertEquals(secondCard.id, resolved?.id)
    }

    @Test
    fun ambiguousSharedSuffixWithoutCardIdentityDoesNotPickFirst() {
        val firstCard = Account(id = "first", accountType = "Credit Card", last4Digits = "3333")
        val secondCard = Account(id = "second", accountType = "Credit Card", last4Digits = "3333")

        assertNull(
            CreditPaymentLinker.findCreditAccount(
                accounts = listOf(firstCard, secondCard),
                pendingCreditDigits = "3333",
                smsCreditDigits = "",
                partialRecordAccountId = ""
            )
        )
    }
}
