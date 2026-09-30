package com.example.wallettrackers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.example.wallettrackers.model.CustomSubCategory
import com.example.wallettrackers.screens.SubCategoriesScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SubCategoriesScreenComponentTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun subcategoryCreationRejectsBlankAndCaseInsensitiveDuplicates() {
        val createdNames = mutableListOf<String>()
        rule.setContent {
            SubCategoriesScreen(
                categoryName = "Food & Drinks",
                customSubCategories = listOf(
                    CustomSubCategory(id = "custom-1", parentCategory = "Food & Drinks", name = "Coffee Run")
                ),
                onBack = {},
                onSubCategoryClick = {},
                onAddSubCategory = { createdNames += it }
            )
        }

        rule.onNodeWithContentDescription("Add subcategory").performClick()
        rule.onNodeWithText("Add").assertIsNotEnabled()
        rule.onNodeWithText("Subcategory name").performTextInput("   ")
        rule.onNodeWithText("Subcategory name is required").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Subcategory name").performTextClearance()
        rule.onNodeWithText("Subcategory name").performTextInput("groceries")
        rule.onNodeWithText("A subcategory with this name already exists").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Subcategory name").performTextClearance()
        rule.onNodeWithText("Subcategory name").performTextInput("coffee run")
        rule.onNodeWithText("A subcategory with this name already exists").assertIsDisplayed()
        rule.onNodeWithText("Add").assertIsNotEnabled()

        rule.onNodeWithText("Subcategory name").performTextClearance()
        rule.onNodeWithText("Subcategory name").performTextInput(" Late Night Snacks ")
        rule.onNodeWithText("Add").performClick()

        assertEquals(listOf("Late Night Snacks"), createdNames)
    }
}
