package com.example.wallettrackers

import com.example.wallettrackers.viewmodel.OnboardingGroupKey
import com.example.wallettrackers.viewmodel.canonicalOnboardingGroupKey
import com.example.wallettrackers.viewmodel.onboardingAccountId
import com.example.wallettrackers.viewmodel.onboardingDuplicateKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OnboardingIdentityTest {
    @Test
    fun sameSuffixAtDifferentBanksRemainsDifferentGroup() {
        val hsbc = OnboardingGroupKey("hsbc", "2222")

        val nbe = canonicalOnboardingGroupKey(listOf(hsbc), "nbe", "2222")

        assertEquals(OnboardingGroupKey("nbe", "2222"), nbe)
        assertNotEquals(hsbc, nbe)
    }

    @Test
    fun suffixPromotionIsLimitedToSameBank() {
        val hsbc = OnboardingGroupKey("hsbc", "001")
        val nbe = OnboardingGroupKey("nbe", "001")

        assertEquals(
            OnboardingGroupKey("hsbc", "6001"),
            canonicalOnboardingGroupKey(listOf(hsbc, nbe), "hsbc", "6001")
        )
        assertEquals(
            OnboardingGroupKey("nbe", "001"),
            canonicalOnboardingGroupKey(listOf(hsbc, nbe), "nbe", "001")
        )
    }

    @Test
    fun onboardingAccountIdIsStableAndScopedByUserBankTypeAndSuffix() {
        val stable = onboardingAccountId("user-a", "hsbc", "Debit", "2222")

        assertEquals(stable, onboardingAccountId("user-a", "hsbc", "Debit", "2222"))
        assertNotEquals(stable, onboardingAccountId("user-b", "hsbc", "Debit", "2222"))
        assertNotEquals(stable, onboardingAccountId("user-a", "nbe", "Debit", "2222"))
        assertNotEquals(stable, onboardingAccountId("user-a", "hsbc", "Credit Card", "2222"))
        assertNotEquals(stable, onboardingAccountId("user-a", "hsbc", "Debit", "3333"))
    }

    @Test
    fun duplicateDetectionIdentityIncludesBankAndAccountType() {
        assertNotEquals(
            onboardingDuplicateKey("HSBC", "Debit", "2222"),
            onboardingDuplicateKey("NBE", "Debit", "2222")
        )
        assertNotEquals(
            onboardingDuplicateKey("HSBC", "Debit", "2222"),
            onboardingDuplicateKey("HSBC", "Credit Card", "2222")
        )
    }
}
