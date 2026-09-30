package com.example.wallettrackers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.util.SmsAccountMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsAccountMatcherComponentTest {
    private val accounts = listOf(
        Account(id = "hsbc", name = "HSBC ****1234", accountType = "Debit", last4Digits = "1234"),
        Account(id = "cib", name = "CIB ****1234", accountType = "Debit", last4Digits = "1234")
    )

    @Test
    fun sameSuffixUsesBankSenderToSelectTheRightAccount() {
        assertEquals("hsbc", SmsAccountMatcher.match(accounts, "1234", sender = "HSBC")?.id)
        assertEquals("cib", SmsAccountMatcher.match(accounts, "1234", sender = "CIB")?.id)
        assertEquals("hsbc", SmsAccountMatcher.match(accounts, "1234", smsBody = "HSBC card ****1234")?.id)
    }

    @Test
    fun sameSuffixWithoutUniqueBankEvidenceStaysUnlinked() {
        assertNull(SmsAccountMatcher.match(accounts, "1234", sender = "BANKALERT"))
        assertNull(SmsAccountMatcher.match(accounts, "1234"))
        assertTrue(SmsAccountMatcher.hasAmbiguousMatch(accounts, "1234"))
    }

    @Test
    fun uniqueSuffixStillMatchesWithoutBankIdentity() {
        assertEquals("hsbc", SmsAccountMatcher.match(accounts.take(1), "1234")?.id)
        assertNull(SmsAccountMatcher.match(accounts, "9999"))
    }

    @Test
    fun partialMaskedAccountSuffixMatchesOnlyOneCompatibleAccount() {
        val hsbcAccount = Account(id = "hsbc-3001", name = "HSBC ****3001", accountType = "Debit", last4Digits = "3001")
        val otherAccount = Account(id = "other-3002", name = "Other ****3002", accountType = "Debit", last4Digits = "3002")

        assertEquals("hsbc-3001", SmsAccountMatcher.match(listOf(hsbcAccount, otherAccount), "001")?.id)
        assertNull(SmsAccountMatcher.match(listOf(
            hsbcAccount,
            hsbcAccount.copy(id = "hsbc-9001", name = "HSBC ****9001", last4Digits = "9001")
        ), "001"))
    }

    @Test
    fun explicitCreditCardMessageDoesNotMatchDebitAccountWithSameSuffix() {
        val sameInstitutionAccounts = listOf(
            Account(id = "debit", name = "HSBC Account", accountType = "Debit", last4Digits = "5555"),
            Account(id = "credit", name = "HSBC Credit Card", accountType = "Credit Card", last4Digits = "5555")
        )

        assertEquals(
            "credit",
            SmsAccountMatcher.match(
                sameInstitutionAccounts,
                "5555",
                smsBody = "Your Credit Card ending with ****5555 has been used",
                sender = "HSBC"
            )?.id
        )
    }

    @Test
    fun sourceAccountStillMatchesWhenPaymentSmsAlsoNamesDestinationCreditCard() {
        val source = Account(id = "source", name = "SecondBank", accountType = "Debit", last4Digits = "2222")
        val destination = Account(id = "card", name = "TestCard", accountType = "Credit Card", last4Digits = "3333")
        val body = "Your bank account ****2222 was debited EGP 20.00 for credit card payment to card ****3333"

        assertEquals("source", SmsAccountMatcher.match(listOf(source), "2222", body, "HSBC")?.id)
        assertEquals("card", SmsAccountMatcher.match(listOf(source, destination), "3333", body, "HSBC")?.id)
    }

    @Test
    fun printedBalanceFallbackRequiresOneExactEgpCandidate() {
        val candidates = listOf(
            Account(id = "main", name = "MainBank", accountType = "Debit", amount = "1000.00", currency = "EGP"),
            Account(id = "foreign", name = "UsdBank", accountType = "Debit", amount = "1000.00", currency = "USD")
        )
        assertEquals("main", SmsAccountMatcher.matchByPrintedBalance(candidates, 25.0, 975.0)?.id)
        assertNull(SmsAccountMatcher.matchByPrintedBalance(candidates, 25.0, 970.0))
        assertNull(SmsAccountMatcher.matchByPrintedBalance(candidates + candidates.first().copy(id = "second"), 25.0, 975.0))
    }
}
