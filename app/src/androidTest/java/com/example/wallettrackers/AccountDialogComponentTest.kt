package com.example.wallettrackers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.screens.AccountDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class AccountDialogComponentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun accountDialogRejectsDuplicateNamesAndInvalidCreditLimits() {
        var savedAccount: Account? = null
        val existingAccount = Account(id = "existing", name = "MainBank", accountType = "Debit", amount = "1000", last4Digits = "1111")
        rule.setContent {
            AccountDialog(
                existingAccounts = listOf(existingAccount),
                onDismiss = {},
                onConfirm = { savedAccount = it },
                title = "Add Account",
                confirmButtonText = "Add"
            )
        }

        rule.onNodeWithText("Account Name").performTextInput(" mainbank ")
        rule.onNodeWithText("Current Balance").performTextInput("100")
        rule.onNodeWithText("Last 4 Digits").performTextInput("2222")
        rule.onNodeWithText("An account with this name already exists").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Account Name").performTextClearance()
        rule.onNodeWithText("Account Name").performTextInput("Travel Card")
        rule.onNodeWithText("Last 4 Digits").performTextClearance()
        rule.onNodeWithText("Account Type").performClick()
        rule.onNodeWithText("Credit Card").performClick()
        rule.onNodeWithText("Last 4 Digits").performTextInput("3333")
        rule.onNodeWithText("Credit Limit").performTextInput("5000")
        rule.onNodeWithText("Available Credit").performTextInput("NaN")
        rule.onNodeWithText("Enter a finite account amount").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Available Credit").performTextClearance()
        rule.onNodeWithText("Available Credit").performTextInput("5001")
        rule.onNodeWithText("Available credit must be between zero and the credit limit").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Available Credit").performTextClearance()
        rule.onNodeWithText("Available Credit").performTextInput("-0.01")
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Available Credit").performTextClearance()
        rule.onNodeWithText("Available Credit").performTextInput("0")
        rule.onNodeWithText("Add").assertIsEnabled()

        rule.onNodeWithText("Available Credit").performTextClearance()
        rule.onNodeWithText("Available Credit").performTextInput("5000")
        rule.onNodeWithText("Add").assertIsEnabled()

        rule.onNodeWithText("Available Credit").performTextClearance()
        rule.onNodeWithText("Available Credit").performTextInput("3000")
        rule.onNodeWithText("Credit Limit").performTextClearance()
        rule.onNodeWithText("Credit Limit").performTextInput("0")
        rule.onNodeWithText("Credit limit must be a finite amount greater than zero").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Credit Limit").performTextClearance()
        rule.onNodeWithText("Credit Limit").performTextInput("5000")
        rule.onNodeWithText("Add").assertIsEnabled().performClick()

        assertNotNull(savedAccount)
        assertEquals("Travel Card", savedAccount!!.name)
        assertEquals("Credit Card", savedAccount!!.accountType)
        assertEquals("3333", savedAccount!!.last4Digits)
        assertEquals(3000.0, savedAccount!!.amount.toDouble(), 0.0)
        assertEquals(5000.0, savedAccount!!.creditLimit!!, 0.0)
    }
}
