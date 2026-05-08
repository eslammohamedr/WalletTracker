package com.example.wallettrackers.service

import android.util.Log
import com.example.wallettrackers.model.Categories
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import io.ktor.client.*
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

@Serializable
data class TypeAndCategory(val type: String = "", val category: String = "")

@Serializable private data class ChatMessage(val role: String, val content: String? = null)
@Serializable private data class ChatRequest(val model: String, val max_tokens: Int, val messages: List<ChatMessage>)
@Serializable private data class CerebrasRequest(val model: String, val max_completion_tokens: Int, val messages: List<ChatMessage>, val stream: Boolean = false)
@Serializable private data class ChatChoice(val message: ChatMessage)
@Serializable private data class ChatResponse(val choices: List<ChatChoice>)

class AiService(
    private val groqApiKey: String = "",
    private val cerebrasApiKey: String = "",
    private val geminiApiKey: String = ""
) {
    companion object {
        private const val GROQ_MODEL     = "llama-3.3-70b-versatile"
        private const val CEREBRAS_MODEL = "llama3.1-8b"
        private const val GEMINI_MODEL   = "gemini-2.0-flash"
    }

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

    private val geminiModel: GenerativeModel? by lazy {
        if (geminiApiKey.isBlank()) null
        else GenerativeModel(
            modelName = GEMINI_MODEL,
            apiKey = geminiApiKey,
            generationConfig = generationConfig { maxOutputTokens = 300 }
        )
    }

    private val availableCategories = Categories.list
        .flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }
        .distinct().joinToString(", ")

    private fun allCategories() = Categories.list
        .flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }

    // ── Prompts ───────────────────────────────────────────────────────────────

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
        A bank SMS was classified as "Others" because it didn't match any known spending category.

        Existing categories: $availableCategories

        Your task: suggest the best category for this transaction. Rules:
        1. If the SMS matches an existing category above, reply with that exact name.
        2. If you can identify the spending purpose (e.g. "Car Insurance", "Government Fees", "Gym", "Parking"), suggest it in 1-3 words.
        3. If the transaction goes through a payment gateway (Fawry, Paymob, Geidea) and you cannot tell what was paid for, reply: "Bill Payment"
        4. Only reply "Unknown" if the SMS is clearly not a financial transaction.

        Do NOT suggest payment methods (Credit Card, Fawry, Bank Transfer) as the category — focus on what was purchased.
        Reply with ONLY the category name — no explanation, no punctuation.

        SMS: "$smsBody"
    """.trimIndent()

    private fun typeAndCategoryPrompt(smsBody: String, detectedType: String) = """
        A keyword parser extracted type "$detectedType" from this bank SMS.
        Verify the type and assign the correct category. Return ONLY raw JSON (no markdown):
        {"type": "...", "category": "..."}

        Type values: "Income" | "Expense" | "Statement" | "CardPayment" | "AtmWithdrawal"
        Type rules:
        - Income: credited, received, deposit, salary, cashback, IPN inward/received
        - Expense: debited, paid, purchase, IPN outward/sent
        - Statement: credit card bill with due date or total due
        - CardPayment: payment TO a credit card
        - AtmWithdrawal: ATM or @ATM cash withdrawal

        Categories: $availableCategories
        Category rules:
        - Instapay outward/sent → "Instapay outcome" | inward/received → "Instapay income"
        - Salary / TT payment → "Salary" | Cashback → "Gifts" | CardPayment → "Credit"
        - ATM → "Others" | Statement → "Credit Card"
        - FAWRYPF*/PAYMOB*/GEIDEA*/KASHIER* — look at text after * for actual merchant
        - Grocery (Carrefour, Metro, Kheir Zaman, Lulu, Seoudi, Aswak) → "Groceries"
        - Ride (Uber, Careem, InDrive) → "Uber" | Delivery (Talabat, Elmenus) → "Food Delivery"
        - Subscriptions (Netflix, Spotify, YouTube, Disney+, Steam) → "Subscriptions"
        - Telecom (Vodafone, Orange, WE, Etisalat) → "Mobile"
        - Pharmacy (El Ezaby, Seif, TAY) → "Pharmacy" | Hospital/clinic → "Hospital"
        - Fuel/petrol → "Fuel" | Flights → "Travel abroad" | Bus/SWVL → "Travel to another city"
        - Clothing (H&M, Zara, DEFACTO) → "Clothes" | Electronics (B.TECH, 2B) → "Electronics"
        - Restaurants (KFC, McDonald's, pizza) → "Restaurants" | Café/coffee → "Cafe"
        - Online shopping (Noon, Amazon) → "Shopping" | Courses (Udemy) → "Courses"

        SMS: "$smsBody"
    """.trimIndent()

    // ── OpenAI-compatible HTTP helper (Groq, Cerebras) ───────────────────────

    private suspend fun openAiCompletion(url: String, apiKey: String, model: String, prompt: String): String {
        val resp = http.post(url) {
            header("Authorization", "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(ChatRequest(model, 200, listOf(ChatMessage("user", prompt))))
        }
        val raw = resp.bodyAsText()
        if (!resp.status.isSuccess() || raw.contains("\"error\"")) {
            val msg = Regex(""""(?:message|error)"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
                ?: raw.take(300)
            Log.w("AiService", "$model ${resp.status.value} error: $msg")
            throw Exception("$model (${resp.status.value}): $msg")
        }
        return json.decodeFromString<ChatResponse>(raw).choices.firstOrNull()?.message?.content
            ?: throw Exception("Empty response from $model")
    }

    private suspend fun groqCompletion(prompt: String) = openAiCompletion(
        "https://api.groq.com/openai/v1/chat/completions", groqApiKey, GROQ_MODEL, prompt
    )

    private suspend fun cerebrasCompletion(prompt: String): String {
        val resp = http.post("https://api.cerebras.ai/v1/chat/completions") {
            header("Authorization", "Bearer $cerebrasApiKey")
            contentType(ContentType.Application.Json)
            setBody(CerebrasRequest(CEREBRAS_MODEL, 200, listOf(ChatMessage("user", prompt))))
        }
        val raw = resp.bodyAsText()
        if (!resp.status.isSuccess() || raw.contains("\"error\"")) {
            val msg = Regex(""""(?:message|error)"\s*:\s*"([^"]+)"""").find(raw)?.groupValues?.get(1)
                ?: raw.take(300)
            Log.w("AiService", "Cerebras ${resp.status.value} error: $msg")
            throw Exception("Cerebras (${resp.status.value}): $msg")
        }
        return json.decodeFromString<ChatResponse>(raw).choices.firstOrNull()?.message?.content
            ?: throw Exception("Empty response from Cerebras")
    }

    // ── Gemini helper ─────────────────────────────────────────────────────────

    private suspend fun geminiCompletion(prompt: String): String {
        val model = geminiModel ?: throw Exception("Gemini not configured")
        val response = model.generateContent(prompt)
        return response.text?.trim() ?: throw Exception("Empty Gemini response")
    }

    // ── Category matching ─────────────────────────────────────────────────────

    private fun matchCategory(raw: String): String? {
        val cleaned = raw.trim().removeSurrounding("\"")
        return allCategories().find { it.equals(cleaned, ignoreCase = true) }
            ?: cleaned.takeIf { it.isNotBlank() }
    }

    // ── inferCategory: Groq → Cerebras → Gemini ──────────────────────────────

    suspend fun inferCategory(smsBody: String): String? {
        var result: String? = null

        if (groqApiKey.isNotBlank()) {
            try {
                result = matchCategory(groqCompletion(categoryPrompt(smsBody)))
            } catch (e: Exception) { Log.w("AiService", "Groq inferCategory failed: ${e.message}") }
        }

        if (result == null && cerebrasApiKey.isNotBlank()) {
            try {
                result = matchCategory(cerebrasCompletion(categoryPrompt(smsBody)))
            } catch (e: Exception) { Log.w("AiService", "Cerebras inferCategory failed: ${e.message}") }
        }

        if (result == null && geminiApiKey.isNotBlank()) {
            try {
                result = matchCategory(geminiCompletion(categoryPrompt(smsBody)))
            } catch (e: Exception) { Log.w("AiService", "Gemini inferCategory failed: ${e.message}") }
        }

        if (result == null || result.equals("Others", ignoreCase = true)) {
            val suggestion = suggestNewCategory(smsBody)
            if (suggestion != null) Log.i("AiService", "Category suggestion for unknown SMS: $suggestion")
        }

        return result
    }

    suspend fun inferTypeAndCategory(smsBody: String, detectedType: String): TypeAndCategory? {
        val prompt = typeAndCategoryPrompt(smsBody, detectedType)
        suspend fun parse(raw: String): TypeAndCategory? {
            var cleaned = raw
            if (cleaned.startsWith("```"))
                cleaned = cleaned.lines().filter { !it.trim().startsWith("```") }.joinToString("\n")
            return try { json.decodeFromString(cleaned) } catch (e: Exception) { null }
        }
        if (groqApiKey.isNotBlank()) {
            try { parse(groqCompletion(prompt))?.let { return it } }
            catch (e: Exception) { Log.w("AiService", "Groq inferTypeAndCategory failed: ${e.message}") }
        }
        if (cerebrasApiKey.isNotBlank()) {
            try { parse(cerebrasCompletion(prompt))?.let { return it } }
            catch (e: Exception) { Log.w("AiService", "Cerebras inferTypeAndCategory failed: ${e.message}") }
        }
        if (geminiApiKey.isNotBlank()) {
            try { parse(geminiCompletion(prompt))?.let { return it } }
            catch (e: Exception) { Log.w("AiService", "Gemini inferTypeAndCategory failed: ${e.message}") }
        }
        return null
    }

    suspend fun suggestNewCategory(smsBody: String): String? {
        var result: String? = null
        if (groqApiKey.isNotBlank()) {
            try { result = groqCompletion(suggestionPrompt(smsBody)).trim() }
            catch (e: Exception) { Log.w("AiService", "suggestNewCategory Groq failed: ${e.message}") }
        }
        if (result == null && cerebrasApiKey.isNotBlank()) {
            try { result = cerebrasCompletion(suggestionPrompt(smsBody)).trim() }
            catch (e: Exception) { Log.w("AiService", "suggestNewCategory Cerebras failed: ${e.message}") }
        }
        if (result == null && geminiApiKey.isNotBlank()) {
            try { result = geminiCompletion(suggestionPrompt(smsBody)).trim() }
            catch (e: Exception) { Log.w("AiService", "suggestNewCategory Gemini failed: ${e.message}") }
        }
        return result
    }

    // ── analyzeSms: Groq → Cerebras → Gemini ─────────────────────────────────

    suspend fun analyzeSms(smsBody: String): ExtractedTransaction? {
        suspend fun parseRaw(raw: String): ExtractedTransaction {
            var cleaned = raw
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.lines().filter { !it.trim().startsWith("```") }.joinToString("\n")
            }
            return json.decodeFromString(cleaned)
        }

        if (groqApiKey.isNotBlank()) {
            try { return parseRaw(groqCompletion(analyzePrompt(smsBody))) }
            catch (e: Exception) { Log.w("AiService", "Groq analyzeSms failed: ${e.message}") }
        }

        if (cerebrasApiKey.isNotBlank()) {
            try { return parseRaw(cerebrasCompletion(analyzePrompt(smsBody))) }
            catch (e: Exception) { Log.w("AiService", "Cerebras analyzeSms failed: ${e.message}") }
        }

        if (geminiApiKey.isNotBlank()) {
            try { return parseRaw(geminiCompletion(analyzePrompt(smsBody))) }
            catch (e: Exception) { Log.w("AiService", "Gemini analyzeSms failed: ${e.message}") }
        }

        return null
    }

    // ── Debug: raw response from each provider ────────────────────────────────

    suspend fun getDebugAnalysis(smsBody: String): String {
        val sb = StringBuilder()

        suspend fun section(label: String, block: suspend () -> String) {
            sb.append("=== $label ===\n")
            try { sb.append(block()) } catch (e: Exception) { sb.append("ERROR: ${e.message}") }
            sb.append("\n\n")
        }

        if (groqApiKey.isNotBlank()) {
            section("Groq ($GROQ_MODEL) — inferCategory") { groqCompletion(categoryPrompt(smsBody)) }
            section("Groq ($GROQ_MODEL) — analyzeSms")   { groqCompletion(analyzePrompt(smsBody)) }
        }

        if (cerebrasApiKey.isNotBlank()) {
            section("Cerebras ($CEREBRAS_MODEL) — inferCategory") { cerebrasCompletion(categoryPrompt(smsBody)) }
            section("Cerebras ($CEREBRAS_MODEL) — analyzeSms")   { cerebrasCompletion(analyzePrompt(smsBody)) }
        }

        if (geminiApiKey.isNotBlank()) {
            section("Gemini ($GEMINI_MODEL) — inferCategory") { geminiCompletion(categoryPrompt(smsBody)) }
            section("Gemini ($GEMINI_MODEL) — analyzeSms")   { geminiCompletion(analyzePrompt(smsBody)) }
        }

        section("💡 Suggested new category (if Others)") {
            suggestNewCategory(smsBody) ?: "(no suggestion available)"
        }

        return sb.trimEnd().toString()
    }
}
