package com.example.wallettrackers

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.screens.BudgetCard
import com.example.wallettrackers.screens.BudgetDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose UI tests for BudgetCard and BudgetDialog.
 * Run with: ./gradlew connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class BudgetScreenComponentTest {

    @get:Rule
    val rule = createComposeRule()

    // ── BudgetCard ───────────────────────────────────────────────────────

    @Test
    fun budgetCard_showsCategoryName() {
        rule.setContent {
            BudgetCard(
                budget  = Budget(category = "Groceries", monthlyLimit = 1000.0, currency = "EGP"),
                spent   = 450.0,
                onEdit  = {},
                onDelete = {}
            )
        }
        rule.onNodeWithText("Groceries").assertIsDisplayed()
    }

    @Test
    fun budgetCard_showsSpentAmount() {
        rule.setContent {
            BudgetCard(
                budget   = Budget(category = "Groceries", monthlyLimit = 1000.0, currency = "EGP"),
                spent    = 450.0,
                onEdit   = {},
                onDelete = {}
            )
        }
        rule.onNodeWithText(text = "450", substring = true).assertIsDisplayed()
    }

    @Test
    fun budgetCard_showsRemainingWhenUnderBudget() {
        rule.setContent {
            BudgetCard(
                budget   = Budget(category = "Groceries", monthlyLimit = 1000.0, currency = "EGP"),
                spent    = 400.0,
                onEdit   = {},
                onDelete = {}
            )
        }
        rule.onNodeWithText(text = "Left:", substring = true).assertIsDisplayed()
    }

    @Test
    fun budgetCard_showsOverBudgetWhenExceeded() {
        rule.setContent {
            BudgetCard(
                budget   = Budget(category = "Groceries", monthlyLimit = 500.0, currency = "EGP"),
                spent    = 750.0,
                onEdit   = {},
                onDelete = {}
            )
        }
        rule.onNodeWithText(text = "Over by", substring = true).assertIsDisplayed()
    }

    @Test
    fun budgetCard_showsLimitLine() {
        rule.setContent {
            BudgetCard(
                budget   = Budget(category = "Groceries", monthlyLimit = 1000.0, currency = "EGP"),
                spent    = 200.0,
                onEdit   = {},
                onDelete = {}
            )
        }
        rule.onNodeWithText(text = "Limit:", substring = true).assertIsDisplayed()
    }

    @Test
    fun budgetCard_editButtonCallsOnEdit() {
        var editCalled = false
        rule.setContent {
            BudgetCard(
                budget   = Budget(category = "Groceries", monthlyLimit = 1000.0, currency = "EGP"),
                spent    = 200.0,
                onEdit   = { editCalled = true },
                onDelete = {}
            )
        }
        rule.onNodeWithContentDescription("Edit").performClick()
        assert(editCalled)
    }

    @Test
    fun budgetCard_deleteButtonCallsOnDelete() {
        var deleteCalled = false
        rule.setContent {
            BudgetCard(
                budget   = Budget(category = "Groceries", monthlyLimit = 1000.0, currency = "EGP"),
                spent    = 200.0,
                onEdit   = {},
                onDelete = { deleteCalled = true }
            )
        }
        rule.onNodeWithContentDescription("Delete").performClick()
        assert(deleteCalled)
    }

    // ── BudgetDialog ─────────────────────────────────────────────────────

    @Test
    fun budgetDialog_showsAddTitleWhenNoBudget() {
        rule.setContent {
            BudgetDialog(
                budget        = null,
                allCategories = listOf("Groceries", "Uber"),
                onDismiss     = {},
                onConfirm     = {}
            )
        }
        rule.onNodeWithText("Add Budget").assertIsDisplayed()
    }

    @Test
    fun budgetDialog_showsEditTitleWhenBudgetProvided() {
        rule.setContent {
            BudgetDialog(
                budget        = Budget(category = "Groceries", monthlyLimit = 500.0, currency = "EGP"),
                allCategories = listOf("Groceries", "Uber"),
                onDismiss     = {},
                onConfirm     = {}
            )
        }
        rule.onNodeWithText("Edit Budget").assertIsDisplayed()
    }

    @Test
    fun budgetDialog_saveButtonDisabledWhenCategoryEmpty() {
        rule.setContent {
            BudgetDialog(
                budget        = null,
                allCategories = listOf("Groceries"),
                onDismiss     = {},
                onConfirm     = {}
            )
        }
        rule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun budgetDialog_cancelButtonCallsOnDismiss() {
        var dismissed = false
        rule.setContent {
            BudgetDialog(
                budget        = null,
                allCategories = listOf("Groceries"),
                onDismiss     = { dismissed = true },
                onConfirm     = {}
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assert(dismissed)
    }
}
