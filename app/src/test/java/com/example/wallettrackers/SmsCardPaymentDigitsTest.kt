package com.example.wallettrackers

import com.example.wallettrackers.util.SmsCardPaymentDigits
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsCardPaymentDigitsTest {
    @Test
    fun debitFirstPaymentSeparatesSourceAndCardSuffixes() {
        val body = "Your bank account ****2222 was debited EGP 613.37 for credit card payment to card ****3333. Available balance EGP 4386.63."

        val parsed = SmsCardPaymentDigits.parse(body)

        assertEquals("2222", parsed.sourceDigits)
        assertEquals("3333", parsed.creditCardDigits)
        assertEquals("3333", parsed.resolveCreditCardDigits("2222"))
    }

    @Test
    fun doesNotTreatSourceSuffixAsCardWhenTargetSuffixIsOmitted() {
        val parsed = SmsCardPaymentDigits.parse("Account ****2222 debited EGP 613.37 for credit card payment")

        assertEquals("2222", parsed.sourceDigits)
        assertEquals(null, parsed.creditCardDigits)
        assertEquals("", parsed.resolveCreditCardDigits("2222"))
    }
}
