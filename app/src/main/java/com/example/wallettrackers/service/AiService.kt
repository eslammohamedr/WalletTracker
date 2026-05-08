package com.example.wallettrackers.service

import android.util.Log
import com.example.wallettrackers.model.Categories
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExtractedTransaction(
    val amount: String,
    val category: String,
    val type: String,
    val isBankRelated: Boolean,
    val last4Digits: String? = null,
    val isStatement: Boolean = false,
    val dueDate: String? = null,
    val comment: String = ""
)

@Serializable private data class ChatMessage(val role: String, val content: String)
@Serializable private data class ChatRequest(val model: String, val max_tokens: Int, val messages: List<ChatMessage>)
@Serializable private data class ChatChoice(val message: ChatMessage)
@Serializable private data class ChatResponse(val choices: List<ChatChoice>)

class AiService(
    private val groqApiKey: String = "",
    private val openRouterApiKey: String = ""
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val http = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; isLenient = true })
        }
    }

    private val availableCategories = Categories.list
        .flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }
        .distinct().joinToString(", ")

    private fun allCategories() = Categories.list
        .flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }

    // ── Shared prompt ─────────────────────────────────────────────────────────

    private fun categoryPrompt(smsBody: String) = """
        Classify this bank SMS into exactly ONE category from the list below.

        Categories: $availableCategories

        Rules (apply in order):
        - Reply with ONLY the exact category name from the list, nothing else — no punctuation, no explanation.

        PAYMENT GATEWAY PREFIXES: If the merchant name starts with FAWRYPF*, PAYMOB*, GEIDEA*, or KASHIER*, look at the text AFTER the * to identify the actual merchant, then apply the rules below.

        - Instapay outward / IPN outward → "Instapay outcome"
        - Instapay inward / IPN inward → "Instapay income"
        - Salary or TT payment → "Salary"
        - Cashback reward → "Gifts"
        - ATM withdrawal → "Others"
        - Credit card deposit / payment to credit card → "Credit"
        - Subscription services (Netflix, Spotify, YouTube, Amazon Prime, Disney+, Yango Play, Steam, PlayStation) → "Subscriptions"
        - Ride-hailing (Uber, Careem, InDrive) → "Uber"
        - Bus / intercity travel (SWVL, Go Bus, GoBus, East Delta, West Delta) → "Travel to another city"
        - Airlines / flights (Air Cairo, EgyptAir, FlyEgypt, AirArabia) → "Travel abroad"
        - Supermarkets / groceries (Carrefour, Metro, Kheir Zaman, Lulu, Panda, BEET ELGOMLA, Seoudi) → "Groceries"
        - Food delivery apps (Talabat, Hungerstation, Elmenus, Waffarha, Zyda) → "Food Delivery"
        - Restaurants / fast food (KFC, McDonald's, Pizza Hut, Domino's, restaurant, burger, pizza) → "Restaurants"
        - Café / coffee / bakery (Café, Coffee, Dunkin, Cinnabon, Costa, Beano, Starbucks) → "Cafe"
        - Online shopping (Noon, Amazon non-Prime, BALBAA) → "Shopping"
        - Clothing (DEFACTO, LC WAIKIKI, H&M, Zara, SEVEN SECRETS) → "Clothes"
        - Electronics (Flash Technologies, 2B, B.TECH, Extra) → "Electronics"
        - Games (EA, Steam games, PlayStation Store, Xbox) → "Games"
        - Courses (Udemy, Coursera, edX, LinkedIn Learning) → "Courses"
        - School / university fees → "School fees"
        - Hospital / clinic → "Hospital"
        - Pharmacy (TAY PHARMACIES, El Ezaby, Seif Pharmacy) → "Pharmacy"
        - Medical labs (ALMOKHTBR, EL BORG, lab, analysis) → "Lab tests"
        - Telecom / mobile / internet (Vodafone, Orange, Etisalat, WE, Telecom Egypt) → "Mobile"
        - Fuel / petrol / gas station → "Fuel"

        SMS: "$smsBody"
    """.trimIndent()

    private fun analyzePrompt(smsBody: String) = """
        You are a financial assistant. Analyze this bank SMS and return ONLY raw JSON — no markdown.

        Extract:
        - amount: numeric string, no commas
        - category: one of [$availableCategories]
        - type: "Income" | "Expense" | "Statement" | "CardPayment" | "AtmWithdrawal"
        - isBankRelated: true for bank transactions, false for OTP/promo
        - last4Digits: last 3-4 digits of account/card, or null
        - isStatement: true if credit card bill
        - dueDate: DD/MM/YYYY for statements, else null
        - comment: merchant name, recipient (for Instapay), or purpose

        Rules:
        - Income: credited, received, deposit to debit account, salary, IPN inward, cashback
        - Expense: debited, paid, transfer out, IPN outward
        - CardPayment: payment TO a credit card
        - AtmWithdrawal: ATM cash withdrawal
        - Instapay out → "Instapay outcome", Instapay in → "Instapay income"
        - Cashback → "Gifts", ATM → "Others", CardPayment → "Credit"

        SMS: "$smsBody"
    """.trimIndent()

    private fun suggestionPrompt(smsBody: String) = """
        A bank SMS was classified as "Others" because it didn't match any known category.

        Existing categories: $availableCategories

        Look at this SMS and answer ONE of the following:
        A) Suggest a short new category name (1-3 words) that would describe this transaction better than "Others". Be specific (e.g. "Car Insurance", "Government Fees", "Charity Donation").
        B) If the SMS is truly not a financial transaction or you have no idea what it is, reply exactly: "Unknown"

        Reply with ONLY the category suggestion or "Unknown" — no explanation, no punctuation.

        SMS: "$smsBody"
    """.trimIndent()

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private suspend fun chatCompletion(
        url: String, authHeader: String, model: String,
        prompt: String, extraHeaders: Map<String, String> = emptyMap()
    ): String {
        val raw = http.post(url) {
            header("Authorization", authHeader)
            extraHeaders.forEach { (k, v) -> header(k, v) }
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(model, 100, listOf(ChatMessage("user", prompt))))
        }.bodyAsText()
        if (raw.contains("\"error\"")) throw Exception(extractApiError(raw))
        return json.decodeFromString<ChatResponse>(raw).choices.firstOrNull()?.message?.content?.trim()
            ?: throw Exception("Empty response")
    }

    private fun extractApiError(raw: String): String =
        Regex(""""message"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1) ?: raw.take(300)

    private fun matchCategory(raw: String): String? {
        val cleaned = raw.trim().removeSurrounding("\"")
        return allCategories().find { it.equals(cleaned, ignoreCase = true) }
            ?: cleaned.takeIf { it.isNotBlank() }
    }

    // ── Per-provider calls ────────────────────────────────────────────────────

    private suspend fun categoryViaGroq(smsBody: String): String? =
        matchCategory(chatCompletion(
            url = "https://api.groq.com/openai/v1/chat/completions",
            authHeader = "Bearer $groqApiKey",
            model = "llama-3.3-70b-versatile",
            prompt = categoryPrompt(smsBody)
        ))

    private suspend fun categoryViaOpenRouter(smsBody: String): String? =
        matchCategory(chatCompletion(
            url = "https://openrouter.ai/api/v1/chat/completions",
            authHeader = "Bearer $openRouterApiKey",
            model = "google/gemma-3-27b-it:free",
            prompt = categoryPrompt(smsBody),
            extraHeaders = mapOf("HTTP-Referer" to "https://wallettrackers.app")
        ))

    // ── Public inferCategory: Groq → OpenRouter ───────────────────────────────

    suspend fun inferCategory(smsBody: String): String? {
        var result: String? = null

        if (groqApiKey.isNotBlank()) {
            try {
                val r = categoryViaGroq(smsBody)
                if (r != null) result = r
            } catch (e: Exception) { Log.w("AiService", "Groq inferCategory failed: ${e.message}") }
        }

        if (result == null && openRouterApiKey.isNotBlank()) {
            try {
                result = categoryViaOpenRouter(smsBody)
            } catch (e: Exception) { Log.w("AiService", "OpenRouter inferCategory failed: ${e.message}") }
        }

        // When AI can't find a better match, ask what should be added
        if (result == null || result.equals("Others", ignoreCase = true)) {
            val suggestion = suggestNewCategory(smsBody)
            if (suggestion != null) Log.i("AiService", "Category suggestion for unknown SMS: $suggestion")
        }

        return result
    }

    /** Ask AI what category should be added when a SMS is classified as Others. */
    suspend fun suggestNewCategory(smsBody: String): String? {
        val primaryKey = groqApiKey.takeIf { it.isNotBlank() } ?: openRouterApiKey.takeIf { it.isNotBlank() } ?: return null
        val isGroq = groqApiKey.isNotBlank()
        return try {
            chatCompletion(
                url = if (isGroq) "https://api.groq.com/openai/v1/chat/completions"
                      else "https://openrouter.ai/api/v1/chat/completions",
                authHeader = "Bearer $primaryKey",
                model = if (isGroq) "llama-3.3-70b-versatile" else "google/gemma-3-27b-it:free",
                prompt = suggestionPrompt(smsBody),
                extraHeaders = if (isGroq) emptyMap() else mapOf("HTTP-Referer" to "https://wallettrackers.app")
            ).trim()
        } catch (e: Exception) {
            Log.w("AiService", "suggestNewCategory failed: ${e.message}")
            null
        }
    }

    // ── analyzeSms (used as last-resort fallback in SmsReceiver) ─────────────

    suspend fun analyzeSms(smsBody: String): ExtractedTransaction? {
        val providers = buildList {
            if (groqApiKey.isNotBlank()) add("groq")
            if (openRouterApiKey.isNotBlank()) add("openrouter")
        }
        for (provider in providers) {
            try {
                val raw = when (provider) {
                    "groq" -> chatCompletion(
                        "https://api.groq.com/openai/v1/chat/completions",
                        "Bearer $groqApiKey", "llama-3.3-70b-versatile", analyzePrompt(smsBody)
                    )
                    else -> chatCompletion(
                        "https://openrouter.ai/api/v1/chat/completions",
                        "Bearer $openRouterApiKey", "google/gemma-3-27b-it:free", analyzePrompt(smsBody),
                        mapOf("HTTP-Referer" to "https://wallettrackers.app")
                    )
                }
                var cleaned = raw
                if (cleaned.startsWith("```")) {
                    cleaned = cleaned.lines().filter { !it.trim().startsWith("```") }.joinToString("\n")
                }
                return json.decodeFromString<ExtractedTransaction>(cleaned)
            } catch (e: Exception) {
                Log.w("AiService", "$provider analyzeSms failed: ${e.message}")
            }
        }
        return null
    }

    // ── Debug: show raw response from every provider ──────────────────────────

    suspend fun getDebugAnalysis(smsBody: String): String {
        val sb = StringBuilder()

        suspend fun section(label: String, block: suspend () -> String) {
            sb.append("=== $label ===\n")
            try { sb.append(block()) } catch (e: Exception) { sb.append("ERROR: ${e.message}") }
            sb.append("\n\n")
        }

        if (groqApiKey.isNotBlank()) {
            section("Groq (llama-3.3-70b) — inferCategory") {
                chatCompletion(
                    "https://api.groq.com/openai/v1/chat/completions",
                    "Bearer $groqApiKey", "llama-3.3-70b-versatile", categoryPrompt(smsBody)
                )
            }
            section("Groq (llama-3.3-70b) — analyzeSms") {
                chatCompletion(
                    "https://api.groq.com/openai/v1/chat/completions",
                    "Bearer $groqApiKey", "llama-3.3-70b-versatile", analyzePrompt(smsBody)
                )
            }
        }

        if (openRouterApiKey.isNotBlank()) {
            section("OpenRouter (gemma-3-27b) — inferCategory") {
                chatCompletion(
                    "https://openrouter.ai/api/v1/chat/completions",
                    "Bearer $openRouterApiKey", "google/gemma-3-27b-it:free", categoryPrompt(smsBody),
                    mapOf("HTTP-Referer" to "https://wallettrackers.app")
                )
            }
        }

        section("💡 Suggested new category (if Others)") {
            suggestNewCategory(smsBody) ?: "(no suggestion available)"
        }

        return sb.trimEnd().toString()
    }
}
