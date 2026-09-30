package com.example.wallettrackers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.screens.RecordDialog
import com.example.wallettrackers.screens.CategoriesScreen
import com.example.wallettrackers.screens.SubCategoriesScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordDialogComponentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun categoryNavigationReceivesCurrentAmountDraft() {
        val account = Account(
            id = "main", name = "MainBank", accountType = "Debit",
            last4Digits = "1111", amount = "9999.77", currency = "EGP"
        )
        val original = Record(
            id = "record-1", accountId = account.id, accountName = account.name,
            category = "Groceries", amount = "0.23", currency = "EGP", type = "Expense"
        )
        var capturedDraft: Record? = null
        rule.setContent {
            RecordDialog(
                record = original,
                accounts = listOf(account),
                onDismiss = {},
                onConfirm = {},
                onCategoryClick = {},
                onBeforeCategoryClick = { capturedDraft = it },
                title = "Edit Record",
                confirmButtonText = "Update"
            )
        }

        rule.onNodeWithTag("record_amount_input").performTextClearance()
        rule.onNodeWithTag("record_amount_input").performTextInput("0.41")
        rule.onNodeWithText("Groceries").assertIsDisplayed().performClick()

        assertEquals("0.41", capturedDraft?.amount)
        assertEquals("Groceries", capturedDraft?.category)
        assertEquals("record-1", capturedDraft?.id)
    }

    @Test
    fun editingAmountSurvivesCategoriesAndSubcategoryNavigation() {
        val account = Account(
            id = "main", name = "MainBank", accountType = "Debit",
            last4Digits = "1111", amount = "9999.77", currency = "EGP"
        )
        val original = Record(
            id = "record-2", accountId = account.id, accountName = account.name,
            category = "Groceries", amount = "0.23", currency = "EGP", type = "Expense"
        )
        var editingRecord by mutableStateOf(original)
        var screen by mutableStateOf("edit")
        var selectedMainCategory by mutableStateOf("")
        var confirmedRecord: Record? = null

        rule.setContent {
            when (screen) {
                "edit" -> RecordDialog(
                    record = editingRecord,
                    accounts = listOf(account),
                    onDismiss = {},
                    onConfirm = { confirmedRecord = it },
                    onCategoryClick = { screen = "categories" },
                    onBeforeCategoryClick = { editingRecord = it },
                    title = "Edit Record",
                    confirmButtonText = "Update"
                )
                "categories" -> CategoriesScreen(
                    onCategoryClick = {
                        selectedMainCategory = it.name
                        screen = "subcategories"
                    },
                    onBack = { screen = "edit" }
                )
                else -> SubCategoriesScreen(
                    categoryName = selectedMainCategory,
                    customSubCategories = emptyList(),
                    onBack = { screen = "categories" },
                    onSubCategoryClick = { subCategory ->
                        editingRecord = editingRecord.copy(category = subCategory)
                        screen = "edit"
                    },
                    onAddSubCategory = {},
                    onDeleteSubCategory = {}
                )
            }
        }

        rule.onNodeWithTag("record_amount_input").performTextClearance()
        rule.onNodeWithTag("record_amount_input").performTextInput("0.41")
        rule.onNodeWithText("Groceries").performClick()
        rule.onNodeWithText("Food & Drinks").performClick()
        rule.onNodeWithText("Restaurants").performClick()
        rule.onNodeWithText("Update").performClick()

        assertEquals("0.41", confirmedRecord?.amount)
        assertEquals("Restaurants", confirmedRecord?.category)
        assertEquals("record-2", confirmedRecord?.id)
    }

    @Test
    fun editingIncomeToExpenseRequiresAnExpenseCategoryAndSubmitsTypeChange() {
        val account = Account(
            id = "main", name = "MainBank", accountType = "Debit",
            last4Digits = "1111", amount = "1000.23", currency = "EGP"
        )
        val original = Record(
            id = "record-type-edit", accountId = account.id, accountName = account.name,
            category = "Salary", amount = "0.23", currency = "EGP", type = "Income"
        )
        var editingRecord by mutableStateOf(original)
        var screen by mutableStateOf("edit")
        var selectedMainCategory by mutableStateOf("")
        var confirmedRecord: Record? = null

        rule.setContent {
            when (screen) {
                "edit" -> RecordDialog(
                    record = editingRecord,
                    accounts = listOf(account),
                    onDismiss = {},
                    onConfirm = { confirmedRecord = it },
                    onCategoryClick = { screen = "categories" },
                    onBeforeCategoryClick = { editingRecord = it },
                    title = "Edit Record",
                    confirmButtonText = "Update"
                )
                "categories" -> CategoriesScreen(
                    onCategoryClick = {
                        selectedMainCategory = it.name
                        screen = "subcategories"
                    },
                    onBack = { screen = "edit" }
                )
                else -> SubCategoriesScreen(
                    categoryName = selectedMainCategory,
                    customSubCategories = emptyList(),
                    onBack = { screen = "categories" },
                    onSubCategoryClick = { subCategory ->
                        editingRecord = editingRecord.copy(category = subCategory)
                        screen = "edit"
                    },
                    onAddSubCategory = {},
                    onDeleteSubCategory = {}
                )
            }
        }

        rule.onNodeWithTag("record_type_expense").assertIsDisplayed().performClick()
        rule.onNodeWithText("Update").assertIsNotEnabled()
        rule.onNodeWithText("Select Category").performClick()
        rule.onNodeWithText("Food & Drinks").performClick()
        rule.onNodeWithText("Groceries").performClick()
        rule.onNodeWithText("Update").performClick()

        assertEquals("Expense", confirmedRecord?.type)
        assertEquals("Groceries", confirmedRecord?.category)
        assertEquals("0.23", confirmedRecord?.amount)
    }
}
