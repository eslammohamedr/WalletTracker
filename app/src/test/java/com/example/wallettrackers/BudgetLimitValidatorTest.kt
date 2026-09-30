package com.example.wallettrackers

import com.example.wallettrackers.util.isValidBudgetLimit
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetLimitValidatorTest {
    @Test
    fun `rejects zero and negative limits`() {
        assertFalse(isValidBudgetLimit("0"))
        assertFalse(isValidBudgetLimit("-1"))
    }

    @Test
    fun `rejects blank malformed and non-finite limits`() {
        assertFalse(isValidBudgetLimit(""))
        assertFalse(isValidBudgetLimit("   "))
        assertFalse(isValidBudgetLimit("not a number"))
        assertFalse(isValidBudgetLimit("NaN"))
        assertFalse(isValidBudgetLimit("Infinity"))
    }

    @Test
    fun `accepts positive finite limits`() {
        assertTrue(isValidBudgetLimit("0.01"))
        assertTrue(isValidBudgetLimit("500"))
    }
}
