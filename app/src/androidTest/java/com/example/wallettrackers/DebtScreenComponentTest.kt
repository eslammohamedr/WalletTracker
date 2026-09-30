package com.example.wallettrackers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.wallettrackers.model.Debt
import com.example.wallettrackers.screens.DebtDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class DebtScreenComponentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun debtAmountRejectsZeroNegativeNonFiniteAndOverflowValues() {
        var savedDebt: Debt? = null
        rule.setContent {
            DebtDialog(debt = null, onDismiss = {}, onConfirm = { savedDebt = it })
        }
        rule.onNodeWithText("Person Name").performTextInput("Alice")

        listOf("0", "-2", "NaN", "Infinity", "1e309").forEach { invalidAmount ->
            rule.onNodeWithText("Amount").performTextClearance()
            rule.onNodeWithText("Amount").performTextInput(invalidAmount)
            rule.onNodeWithText("Save").assertIsNotEnabled()
            rule.onNodeWithText("Enter an amount greater than zero").assertIsDisplayed()
        }

        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput("12.50")
        rule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertNotNull(savedDebt)
        assertEquals(12.5, savedDebt!!.amount, 0.0)
        assertEquals("Alice", savedDebt!!.personName)
    }
}
