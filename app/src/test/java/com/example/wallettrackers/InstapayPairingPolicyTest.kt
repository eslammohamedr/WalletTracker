package com.example.wallettrackers

import com.example.wallettrackers.util.InstapayPairingPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstapayPairingPolicyTest {
    @Test
    fun doesNotPairAnInwardTransferWithAnOutgoingTransferOnTheSameAccount() {
        assertFalse(InstapayPairingPolicy.canPair("second-bank", "EGP", "second-bank", "EGP"))
    }

    @Test
    fun allowsOppositeSidesOnDistinctAccountsWithMatchingCurrency() {
        assertTrue(InstapayPairingPolicy.canPair("main-bank", "EGP", "second-bank", "EGP"))
    }

    @Test
    fun rejectsMissingAccountIdentityAndCrossCurrencyPairing() {
        assertFalse(InstapayPairingPolicy.canPair("", "EGP", "second-bank", "EGP"))
        assertFalse(InstapayPairingPolicy.canPair("main-bank", "USD", "second-bank", "EGP"))
    }
}
