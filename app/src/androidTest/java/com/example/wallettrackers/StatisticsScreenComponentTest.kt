package com.example.wallettrackers

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.remote.ExchangeRateApi
import com.example.wallettrackers.remote.ExchangeRateResponse
import com.example.wallettrackers.screens.BalanceTabContent
import com.example.wallettrackers.screens.CurrencyConverterScreen
import com.example.wallettrackers.screens.SpendingTabContent
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

@RunWith(AndroidJUnit4::class)
class StatisticsScreenComponentTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun emptySpendingSummaryUsesDefaultCurrencyInsteadOfMixed() {
        rule.setContent {
            SpendingTabContent(records = emptyList())
        }

        rule.onNodeWithText("+0.00 EGP").assertIsDisplayed()
    }

    @Test
    fun missingGoldRateIsNotShownAsZeroValue() {
        val accounts = listOf(
            Account(id = "main", name = "MainBank", accountType = "Debit", amount = "10000.00", currency = "EGP"),
            Account(id = "usd", name = "USDBank", accountType = "Debit", amount = "1000.00", currency = "USD"),
            Account(id = "eur", name = "EURBank", accountType = "Debit", amount = "500.00", currency = "EUR"),
            Account(id = "gold", name = "GoldWallet", accountType = "Gold", amount = "10.00", currency = "XAU")
        )

        rule.setContent {
            BalanceTabContent(accounts = accounts, usdRate = 50.0, eurRate = 53.0, goldPriceEgpPerGram = null)
        }

        rule.onNodeWithText("Gold price unavailable; total excludes Gold").assertIsDisplayed()
        rule.onNodeWithText("Price unavailable").assertIsDisplayed()
    }

    @Test
    fun availableGoldRateContributesConvertedValueToNetWorth() {
        val accounts = listOf(
            Account(id = "main", name = "MainBank", accountType = "Debit", amount = "10000.00", currency = "EGP"),
            Account(id = "usd", name = "USDBank", accountType = "Debit", amount = "1000.00", currency = "USD"),
            Account(id = "eur", name = "EURBank", accountType = "Debit", amount = "500.00", currency = "EUR"),
            Account(id = "gold", name = "GoldWallet", accountType = "Gold", amount = "10.00", currency = "XAU"),
            Account(id = "card", name = "TestCard", accountType = "Credit Card", amount = "3000.00", currency = "EGP")
        )

        rule.setContent {
            BalanceTabContent(accounts = accounts, usdRate = 50.0, eurRate = 53.0, goldPriceEgpPerGram = 200.0)
        }

        rule.onNodeWithText("88,500.00", substring = true).assertIsDisplayed()
        rule.onNode(hasScrollAction()).performScrollToNode(hasText("GoldWallet"))
        rule.onNodeWithText("2,000.00 EGP").assertIsDisplayed()
    }

    @Test
    fun converterLabelsOldQuotesAsStale() {
        rule.setContent {
            CurrencyConverterScreen(onBack = {}, rateApi = FakeExchangeRateApi())
        }

        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText("Rates as of 2020-01-01 · stale").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Rates as of 2020-01-01 · stale").assertIsDisplayed()
    }

    @Test
    fun failedRefreshKeepsLastRatesAndShowsTheirAge() {
        val api = FakeExchangeRateApi()
        rule.setContent {
            CurrencyConverterScreen(onBack = {}, rateApi = api)
        }

        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText("Rates as of 2020-01-01 · stale").fetchSemanticsNode() }.isSuccess
        }
        api.failRequests = true
        rule.onNodeWithContentDescription("Refresh").performClick()
        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithText("Failed to refresh rates. Showing previously fetched rates.").fetchSemanticsNode()
            }.isSuccess
        }

        rule.onNodeWithText("Failed to refresh rates. Showing previously fetched rates.").assertIsDisplayed()
        rule.onNodeWithText("Rates as of 2020-01-01 · stale").assertIsDisplayed()
        rule.onNodeWithText("50.00").assertIsDisplayed()
        rule.onNodeWithText("53.00").assertIsDisplayed()
    }

    @Test
    fun firstLaunchOfflineDoesNotFabricateRatesOrConversion() {
        val api = FakeExchangeRateApi().apply { failRequests = true }
        rule.setContent {
            CurrencyConverterScreen(onBack = {}, rateApi = api)
        }

        rule.waitUntil(5_000) {
            runCatching {
                rule.onNodeWithText("Failed to fetch rates. Check your connection.").fetchSemanticsNode()
            }.isSuccess
        }

        rule.onNodeWithText("Failed to fetch rates. Check your connection.").assertIsDisplayed()
        rule.onNodeWithText("Quote date unavailable").assertIsDisplayed()
        rule.onNodeWithText("Conversion unavailable until USD and EUR rates load").assertIsDisplayed()
        listOf("USD equivalent:", "EUR equivalent:", "US Dollar", "Euro").forEach { absentText ->
            check(runCatching { rule.onNodeWithText(absentText, substring = true).fetchSemanticsNode() }.isFailure)
        }
    }

    @Test
    fun converterCalculatesEgpUsdAndEurValuesFromFetchedRates() {
        rule.setContent {
            CurrencyConverterScreen(onBack = {}, rateApi = FakeExchangeRateApi())
        }

        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText("Rates as of 2020-01-01 \u00b7 stale").fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput("100")
        rule.onNodeWithText("100.00 EGP").assertIsDisplayed()
        rule.onNodeWithText("USD equivalent: 2.00 USD").assertIsDisplayed()
        rule.onNodeWithText("EUR equivalent: 1.89 EUR").assertIsDisplayed()

        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput("0")
        rule.onNodeWithText("0.00 EGP").assertIsDisplayed()
        rule.onNodeWithText("USD equivalent: 0.00 USD").assertIsDisplayed()
        rule.onNodeWithText("EUR equivalent: 0.00 EUR").assertIsDisplayed()

        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput("9".repeat(400))
        rule.onNodeWithText("Amount is outside the supported conversion range").assertIsDisplayed()

        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput("2")
        rule.onNodeWithTag("converterSourceCurrency").performClick()
        rule.onNodeWithText("USD").performClick()

        rule.onNodeWithText("100.00 EGP").assertIsDisplayed()
        rule.onNodeWithText("USD equivalent: 2.00 USD").assertIsDisplayed()
        rule.onNodeWithText("EUR equivalent: 1.89 EUR").assertIsDisplayed()
    }

    @Test
    fun converterRejectsInvalidAmountText() {
        rule.setContent {
            CurrencyConverterScreen(onBack = {}, rateApi = FakeExchangeRateApi())
        }

        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput(".")
        rule.onNodeWithText("Enter a valid non-negative amount").assertIsDisplayed()
        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput("-1")
        rule.onNodeWithText("Enter a valid non-negative amount").assertIsDisplayed()
        rule.onNodeWithText("Amount").performTextClearance()
        rule.onNodeWithText("Amount").performTextInput("1..3")
        rule.onNodeWithText("Enter a valid non-negative amount").assertIsDisplayed()
    }

    private class FakeExchangeRateApi : ExchangeRateApi {
        var failRequests = false

        override suspend fun getLatestRates(base: String): ExchangeRateResponse {
            if (failRequests) throw IOException("Offline test fixture")
            val rate = if (base == "USD") 50.0 else 53.0
            return ExchangeRateResponse(base = base, date = "2020-01-01", rates = mapOf("EGP" to rate))
        }

        override suspend fun getGoldPriceUSD(): Double? = null
    }
}
