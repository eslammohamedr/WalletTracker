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
data class ReceiptLineItem(
    val name: String = "",
    val quantity: Int = 1,
    val unitPrice: Double = 0.0,
    val totalPrice: Double = 0.0
)

@Serializable
data class ParsedReceipt(
    val merchant: String = "",
    val items: List<ReceiptLineItem> = emptyList(),
    val subtotal: Double = 0.0,
    val tax: Double = 0.0,
    val serviceCharge: Double = 0.0,
    val discount: Double = 0.0,
    val total: Double = 0.0,
    val currency: String = "EGP"
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
        private const val GEMINI_MODEL   = "gemini-2.5-flash"
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
            generationConfig = generationConfig { maxOutputTokens = 8192 }
        )
    }

    private val availableCategories = Categories.list
        .filter { it.name != "Credit" }
        .flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }
        .distinct().joinToString(", ")

    private fun allCategories() = Categories.list
        .filter { it.name != "Credit" }
        .flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }

    // ── Prompts ───────────────────────────────────────────────────────────────

    private fun categoryPrompt(smsBody: String) = """
        Classify this bank SMS into exactly ONE category from the list below.

        Categories: $availableCategories

        Rules (apply in order):
        - Reply with ONLY the exact category name from the list, nothing else — no punctuation, no explanation.

        PAYMENT GATEWAY PREFIXES: If the merchant name starts with FAWRYPF*, MYFAWRY*, PAYMOB*, GEIDEA*, or KASHIER*, extract the text AFTER the * and use EGYPT MERCHANT KNOWLEDGE below to identify it. If still unidentifiable, return "Others".

        EGYPT MERCHANT KNOWLEDGE — use your knowledge of businesses and services operating in Egypt to identify the merchant from its name or code, then map to the correct category:
        - Egyptian water utilities (CAIRO_WATER, CAIROWATER, NWCO, NAWAH, DELTA_WATER, BEHEIRA_WATER, ALEX_WATER, جنوب القاهرة, شمال القاهرة, مياه) → "Water"
        - Egyptian electricity companies (EEHC, CAIRO_ELEC, UPPER_EGYPT_ELEC, ALEX_ELEC, DELTA_ELEC, كهرباء) → "Electricity"
        - Egyptian natural gas companies (MOBCO, GASCO, NATGAS, EGYPT_GAS, MISR_GAS, غاز) → "Gas"
        - Egyptian internet / landline providers (TE_DATA, TEDATA, WE, TELECOM_EGYPT, LINK, NOOR) → "Internet"
        - Egyptian telecom / mobile recharge (VODAFONE, ORANGE, ETISALAT, E&, WE_MOBILE) → "Mobile"
        - Egyptian insurance companies (MISR_INSURANCE, AXA, ALLIANZ, MetLife, SUEZ_CANAL_INS, تأمين) → "Others"
        - Egyptian government / tax / traffic fees (TRAFFIC, DMV, TAX_AUTHORITY, NOOR_SYSTEM, Nafeza, نافذة) → "Others"
        - Egyptian toll roads / e-tag (MENA_ETOLL, ETAG, MENATOLL) → "Fuel"
        - Egyptian hospitals / clinics / medical centers → "Hospital"
        - Egyptian pharmacies (SEIF, EL_EZABY, ISAAF, TAY) → "Pharmacy"
        - Egyptian schools / universities / education platforms (BIS, CAC, AUC, GUC, NILE_UNIV) → "School fees"
        - Egyptian gyms / fitness clubs (GOLD_GYM, CLUB, FITNESS) → "Sport & fitness"
        - Egyptian supermarkets / grocery chains (CARREFOUR, METRO, KHEIR_ZAMAN, SEOUDI, BEET_ELGOMLA, KAZYON) → "Groceries"
        - Egyptian fast food / restaurants (KFC, MCDONALDS, PIZZA_HUT, HARDEES, POPEYES, CILANTRO) → "Restaurants"
        - Egyptian ride-hailing / delivery (UBER, CAREEM, INDRIVE, HALAN, TALABAT, ELMENUS) → use the matching rule below

        - Instapay outward / IPN outward → "Instapay outcome"
        - Instapay inward / IPN inward → "Instapay income"
        - Salary or TT payment → "Salary"
        - Cashback reward → "Gifts"
        - ATM withdrawal → "Others"
        - Subscription services (Netflix, Spotify, YouTube, Amazon Prime, Disney+, Yango Play, Steam, PlayStation) → "Subscriptions"
        - Ride-hailing (Uber, Careem, InDrive, Halan) → "Uber"
        - Bus / intercity travel (SWVL, Go Bus, GoBus, East Delta, West Delta) → "Travel to another city"
        - Airlines / flights (Air Cairo, EgyptAir, FlyEgypt, AirArabia) → "Travel abroad"
        - Supermarkets / groceries (Carrefour, Metro, Kheir Zaman, Lulu, Panda, BEET ELGOMLA, Seoudi, Kazyon) → "Groceries"
        - Food delivery apps (Talabat, Hungerstation, Elmenus, Waffarha, Zyda) → "Food Delivery"
        - Restaurants / fast food (KFC, McDonald's, Pizza Hut, Domino's, Hardees, restaurant, burger, pizza) → "Restaurants"
        - Café / coffee / bakery (Café, Coffee, Dunkin, Cinnabon, Costa, Beano, Starbucks, Cilantro) → "Cafe"
        - Online shopping (Noon, Amazon non-Prime, BALBAA, Jumia) → "Shopping"
        - Clothing (DEFACTO, LC WAIKIKI, H&M, Zara, SEVEN SECRETS) → "Clothes"
        - Electronics (Flash Technologies, 2B, B.TECH, Extra) → "Electronics"
        - Games (EA, Steam games, PlayStation Store, Xbox) → "Games"
        - Courses (Udemy, Coursera, edX, LinkedIn Learning) → "Courses"
        - School / university fees → "School fees"
        - Hospital / clinic → "Hospital"
        - Pharmacy (TAY PHARMACIES, El Ezaby, Seif Pharmacy, Isaaf) → "Pharmacy"
        - Medical labs (ALMOKHTBR, EL BORG, lab, analysis) → "Lab tests"
        - Telecom / mobile / internet (Vodafone, Orange, Etisalat, WE, Telecom Egypt, TE Data) → "Mobile"
        - Fuel / petrol / gas station / toll → "Fuel"

        SMS: "$smsBody"
    """.trimIndent()

    private fun analyzePrompt(smsBody: String) = """
        You are a financial assistant. Analyze this bank SMS and return ONLY raw JSON — no markdown.

        Extract:
        - amount: numeric string, no commas
        - category: one of [$availableCategories]
        - type: "Income" | "Expense" ONLY
        - isBankRelated: true for bank transactions, false for OTP/promo
        - last4Digits: last 3-4 digits of account/card, or null
        - isStatement: true if credit card bill
        - dueDate: DD/MM/YYYY for statements, else null
        - comment: merchant name, recipient (for Instapay), or purpose

        Type rules:
        - Income: credited, received, deposit to debit account, salary, IPN inward, cashback
        - Expense: debited, paid, transfer out, IPN outward, ATM withdrawal
        - Instapay out → "Instapay outcome", Instapay in → "Instapay income"
        - Cashback → "Gifts", ATM → "Others"

        PAYMENT GATEWAY PREFIXES (FAWRYPF*, MYFAWRY*, PAYMOB*, GEIDEA*, KASHIER*): extract text after * and use EGYPT MERCHANT KNOWLEDGE to identify it.

        EGYPT MERCHANT KNOWLEDGE — identify the merchant from its name/code in Egyptian context:
        - Water utilities (CAIRO_WATER, CAIROWATER, NWCO, NAWAH, DELTA_WATER, BEHEIRA_WATER, مياه) → "Water"
        - Electricity companies (EEHC, CAIRO_ELEC, UPPER_EGYPT_ELEC, ALEX_ELEC, DELTA_ELEC, كهرباء) → "Electricity"
        - Natural gas companies (MOBCO, GASCO, NATGAS, EGYPT_GAS, MISR_GAS, غاز) → "Gas"
        - Internet/landline (TE_DATA, TEDATA, WE, TELECOM_EGYPT, LINK, NOOR) → "Internet"
        - Mobile/telecom recharge (VODAFONE, ORANGE, ETISALAT, E&) → "Mobile"
        - Toll roads / e-tag (MENA_ETOLL, ETAG, MENATOLL) → "Fuel"
        - Insurance (MISR_INSURANCE, AXA, ALLIANZ, MetLife, تأمين) → "Others"
        - Government / tax / traffic fees (TRAFFIC, TAX_AUTHORITY, NOOR_SYSTEM, Nafeza) → "Others"
        - Gyms / fitness (GOLD_GYM, CLUB, FITNESS) → "Sport & fitness"
        - Egyptian grocery chains (CARREFOUR, METRO, KHEIR_ZAMAN, SEOUDI, BEET_ELGOMLA, KAZYON) → "Groceries"
        - Egyptian pharmacies (SEIF, EL_EZABY, ISAAF, TAY) → "Pharmacy"
        - Schools / universities (BIS, CAC, AUC, GUC, NILE_UNIV) → "School fees"
        - Ride-hailing / delivery (UBER, CAREEM, INDRIVE, HALAN, TALABAT, ELMENUS) → "Uber" or "Food Delivery"

        SMS: "$smsBody"
    """.trimIndent()

    private fun suggestionPrompt(smsBody: String) = """
        A bank SMS was classified as "Others" because it didn't match any known spending category.

        Existing categories: $availableCategories

        EGYPT MERCHANT KNOWLEDGE — use your knowledge of businesses operating in Egypt to identify merchant names or codes in the SMS:
        - Water utilities (CAIRO_WATER, CAIROWATER, NWCO, NAWAH, DELTA_WATER, مياه) → "Water"
        - Electricity companies (EEHC, CAIRO_ELEC, UPPER_EGYPT_ELEC, كهرباء) → "Electricity"
        - Natural gas companies (MOBCO, GASCO, NATGAS, غاز) → "Gas"
        - Internet/landline (TE_DATA, TEDATA, WE, TELECOM_EGYPT, LINK, NOOR) → "Internet"
        - Mobile/telecom recharge (VODAFONE, ORANGE, ETISALAT, E&) → "Mobile"
        - Toll roads / e-tag (MENA_ETOLL, ETAG, MENATOLL) → "Fuel"
        - Insurance (MISR_INSURANCE, AXA, ALLIANZ, MetLife, تأمين) → "Insurance"
        - Government / tax / traffic fees (TRAFFIC, TAX_AUTHORITY, NOOR_SYSTEM, Nafeza) → "Government Fees"
        - Gyms / fitness (GOLD_GYM, CLUB, FITNESS) → "Sport & fitness"
        - Any other identifiable Egyptian business → suggest its spending purpose in 1-3 words

        Your task: suggest the best category for this transaction. Rules:
        1. If the SMS matches an existing category above, reply with that exact name.
        2. Use EGYPT MERCHANT KNOWLEDGE to identify the merchant, then suggest the spending purpose in 1-3 words (e.g. "Water Bill", "Electricity Bill", "Car Insurance", "Government Fees", "Gym").
        3. If the transaction goes through a payment gateway (Fawry, myfawry, Paymob, Geidea) and you still cannot identify the merchant, reply: "Bill Payment"
        4. Only reply "Unknown" if the SMS is clearly not a financial transaction.

        Do NOT suggest payment methods (Credit Card, Fawry, Bank Transfer) as the category — focus on what was purchased.
        Reply with ONLY the category name — no explanation, no punctuation.

        SMS: "$smsBody"
    """.trimIndent()

    private fun typeAndCategoryPrompt(smsBody: String, detectedType: String) = """
        A keyword parser detected type "$detectedType" from this bank SMS.
        Confirm if the type is correct (only "Income" or "Expense") and assign the best category.
        Return ONLY raw JSON (no markdown): {"type": "...", "category": "..."}

        Type values: "Income" | "Expense" ONLY
        - Income: credited, received, deposit, salary, cashback, IPN inward/received
        - Expense: debited, paid, purchase, IPN outward/sent

        Categories: $availableCategories
        Category rules:
        - Instapay outward/sent → "Instapay outcome" | inward/received → "Instapay income"
        - Salary / TT payment → "Salary" | Cashback → "Gifts"
        - ATM withdrawal → "Others"
        - FAWRYPF*/MYFAWRY*/PAYMOB*/GEIDEA*/KASHIER* — extract text after *, use EGYPT MERCHANT KNOWLEDGE to identify it; if still unknown, return "Others"

        EGYPT MERCHANT KNOWLEDGE — identify merchant from name/code in Egyptian context:
        - Water utilities (CAIRO_WATER, CAIROWATER, NWCO, NAWAH, DELTA_WATER, مياه) → "Water"
        - Electricity companies (EEHC, CAIRO_ELEC, UPPER_EGYPT_ELEC, كهرباء) → "Electricity"
        - Natural gas (MOBCO, GASCO, NATGAS, غاز) → "Gas"
        - Internet/landline (TE_DATA, TEDATA, WE, TELECOM_EGYPT, LINK, NOOR) → "Internet"
        - Mobile/telecom recharge (VODAFONE, ORANGE, ETISALAT, E&) → "Mobile"
        - Toll roads / e-tag (MENA_ETOLL, ETAG, MENATOLL) → "Fuel"
        - Insurance (MISR_INSURANCE, AXA, ALLIANZ, MetLife) → "Others"
        - Government fees (TRAFFIC, TAX_AUTHORITY, NOOR_SYSTEM, Nafeza) → "Others"
        - Gyms / fitness (GOLD_GYM, CLUB, FITNESS) → "Sport & fitness"
        - Grocery (Carrefour, Metro, Kheir Zaman, Seoudi, KAZYON, BEET_ELGOMLA) → "Groceries"
        - Ride (Uber, Careem, InDrive, Halan) → "Uber" | Delivery (Talabat, Elmenus) → "Food Delivery"
        - Subscriptions (Netflix, Spotify, YouTube, Disney+, Steam) → "Subscriptions"
        - Pharmacy (El Ezaby, Seif, TAY, Isaaf) → "Pharmacy" | Hospital/clinic → "Hospital"
        - Fuel/petrol → "Fuel" | Flights → "Travel abroad" | Bus/SWVL → "Travel to another city"
        - Clothing (H&M, Zara, DEFACTO, LC WAIKIKI) → "Clothes" | Electronics (B.TECH, 2B, Extra) → "Electronics"
        - Restaurants (KFC, McDonald's, Pizza Hut, Hardees) → "Restaurants" | Café (Cilantro, Costa, Starbucks) → "Cafe"
        - Online shopping (Noon, Amazon, Jumia) → "Shopping" | Courses (Udemy, Coursera) → "Courses"
        - Schools / universities (BIS, AUC, GUC, CAC) → "School fees"

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
        return try {
            val response = model.generateContent(prompt)
            response.text?.trim() ?: throw Exception("Empty Gemini response")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw Exception("Gemini unavailable: ${e.message?.take(120)}")
        }
    }

    // ── Category matching ─────────────────────────────────────────────────────

    private fun matchCategory(raw: String): String? {
        val cleaned = raw.trim().removeSurrounding("\"")
        return allCategories().find { it.equals(cleaned, ignoreCase = true) }
            ?: cleaned.takeIf { it.isNotBlank() }
    }

    // ── inferCategory: Groq → Gemini → Cerebras ──────────────────────────────

    suspend fun inferCategory(smsBody: String): String? {
        var result: String? = null

        if (groqApiKey.isNotBlank()) {
            try {
                result = matchCategory(groqCompletion(categoryPrompt(smsBody)))
            } catch (e: Exception) { Log.w("AiService", "Groq inferCategory failed: ${e.message}") }
        }

        if (result == null && geminiApiKey.isNotBlank()) {
            try {
                result = matchCategory(geminiCompletion(categoryPrompt(smsBody)))
            } catch (e: Exception) { Log.w("AiService", "Gemini inferCategory failed: ${e.message}") }
        }

        if (result == null && cerebrasApiKey.isNotBlank()) {
            try {
                result = matchCategory(cerebrasCompletion(categoryPrompt(smsBody)))
            } catch (e: Exception) { Log.w("AiService", "Cerebras inferCategory failed: ${e.message}") }
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
        if (geminiApiKey.isNotBlank()) {
            try { parse(geminiCompletion(prompt))?.let { return it } }
            catch (e: Exception) { Log.w("AiService", "Gemini inferTypeAndCategory failed: ${e.message}") }
        }
        if (cerebrasApiKey.isNotBlank()) {
            try { parse(cerebrasCompletion(prompt))?.let { return it } }
            catch (e: Exception) { Log.w("AiService", "Cerebras inferTypeAndCategory failed: ${e.message}") }
        }
        return null
    }

    suspend fun suggestNewCategory(smsBody: String): String? {
        var result: String? = null
        if (groqApiKey.isNotBlank()) {
            try { result = groqCompletion(suggestionPrompt(smsBody)).trim() }
            catch (e: Exception) { Log.w("AiService", "suggestNewCategory Groq failed: ${e.message}") }
        }
        if (result == null && geminiApiKey.isNotBlank()) {
            try { result = geminiCompletion(suggestionPrompt(smsBody)).trim() }
            catch (e: Exception) { Log.w("AiService", "suggestNewCategory Gemini failed: ${e.message}") }
        }
        if (result == null && cerebrasApiKey.isNotBlank()) {
            try { result = cerebrasCompletion(suggestionPrompt(smsBody)).trim() }
            catch (e: Exception) { Log.w("AiService", "suggestNewCategory Cerebras failed: ${e.message}") }
        }
        return result
    }

    // ── analyzeSms: Groq → Gemini → Cerebras ─────────────────────────────────

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

        if (geminiApiKey.isNotBlank()) {
            try { return parseRaw(geminiCompletion(analyzePrompt(smsBody))) }
            catch (e: Exception) { Log.w("AiService", "Gemini analyzeSms failed: ${e.message}") }
        }

        if (cerebrasApiKey.isNotBlank()) {
            try { return parseRaw(cerebrasCompletion(analyzePrompt(smsBody))) }
            catch (e: Exception) { Log.w("AiService", "Cerebras analyzeSms failed: ${e.message}") }
        }

        return null
    }

    // ── Smart Spending Insights ─────────────────────────────────────────────

    suspend fun generateSpendingInsights(dataSummary: String): String? {
        val prompt = """
            You are a personal finance advisor. Analyze this monthly spending data and give exactly 4-5 short bullet insights and 1 actionable tip.

            Format: Use bullet points (•). Keep each point under 15 words. End with "Tip:" on a new line.
            Do NOT use markdown headers or bold. Just plain text with bullet points.

            Data:
            $dataSummary
        """.trimIndent()
        return aiCompletion(prompt, maxTokens = 300)
    }

    // ── Smart Budget Suggestions ─────────────────────────────────────────────

    suspend fun suggestBudgets(spendingSummary: String): String? {
        val prompt = """
            Based on this spending history, suggest monthly budget limits for each category.
            Return ONLY a JSON array, no markdown: [{"category":"...","limit":...,"reason":"..."}]

            Rules:
            - Set limits 10-20% above the average monthly spend (realistic buffer)
            - Only suggest for categories with meaningful spending (>100 EGP/month avg)
            - "reason" should be 5-8 words explaining the suggestion
            - Use whole numbers for limits

            Spending data:
            $spendingSummary
        """.trimIndent()
        return aiCompletion(prompt, maxTokens = 400)
    }

    // ── Predictive Cash Flow ─────────────────────────────────────────────────

    suspend fun predictCashFlow(financialContext: String): String? {
        val prompt = """
            You are a personal finance advisor. Based on this data, predict the user's financial situation for the rest of the month.

            Give a brief 3-4 line summary covering:
            1. Predicted end-of-month balance
            2. Key upcoming expenses or bills
            3. Whether the user is on track or should cut spending

            Keep it conversational and concise. No markdown, no headers. Plain text only.

            Data:
            $financialContext
        """.trimIndent()
        return aiCompletion(prompt, maxTokens = 300)
    }

    // ── AI Chat ──────────────────────────────────────────────────────────────

    suspend fun chat(
        userMessage: String,
        history: List<Pair<String, String>>,
        financialContext: String
    ): String? {
        val systemMsg = """
            You are a helpful financial assistant for a personal wallet app. Answer questions about the user's finances based on this data:

            $financialContext

            Rules:
            - Be concise (2-4 sentences max)
            - Use actual numbers from the data
            - If you don't have enough data to answer, say so briefly
            - Currency is EGP unless specified
            - No markdown formatting, plain text only
        """.trimIndent()

        // Try providers with multi-turn support
        if (groqApiKey.isNotBlank()) {
            try {
                val messages = mutableListOf(ChatMessage("system", systemMsg))
                history.takeLast(10).forEach { (role, content) ->
                    messages.add(ChatMessage(role, content))
                }
                messages.add(ChatMessage("user", userMessage))
                val resp = http.post("https://api.groq.com/openai/v1/chat/completions") {
                    header("Authorization", "Bearer $groqApiKey")
                    contentType(ContentType.Application.Json)
                    setBody(ChatRequest(GROQ_MODEL, 500, messages))
                }
                val raw = resp.bodyAsText()
                if (resp.status.isSuccess()) {
                    return json.decodeFromString<ChatResponse>(raw).choices.firstOrNull()?.message?.content
                }
            } catch (e: Exception) { Log.w("AiService", "Groq chat failed: ${e.message}") }
        }

        if (geminiApiKey.isNotBlank()) {
            try {
                val fullPrompt = "$systemMsg\n\nConversation:\n" +
                    history.takeLast(10).joinToString("\n") { "${it.first}: ${it.second}" } +
                    "\nuser: $userMessage\nassistant:"
                return geminiCompletion(fullPrompt)
            } catch (e: Exception) { Log.w("AiService", "Gemini chat failed: ${e.message}") }
        }

        if (cerebrasApiKey.isNotBlank()) {
            try {
                val messages = mutableListOf(ChatMessage("system", systemMsg))
                history.takeLast(10).forEach { (role, content) ->
                    messages.add(ChatMessage(role, content))
                }
                messages.add(ChatMessage("user", userMessage))
                val resp = http.post("https://api.cerebras.ai/v1/chat/completions") {
                    header("Authorization", "Bearer $cerebrasApiKey")
                    contentType(ContentType.Application.Json)
                    setBody(CerebrasRequest(CEREBRAS_MODEL, 500, messages))
                }
                val raw = resp.bodyAsText()
                if (resp.status.isSuccess()) {
                    return json.decodeFromString<ChatResponse>(raw).choices.firstOrNull()?.message?.content
                }
            } catch (e: Exception) { Log.w("AiService", "Cerebras chat failed: ${e.message}") }
        }

        return null
    }

    // ── Receipt OCR (Gemini vision) ──────────────────────────────────────────

    suspend fun scanReceipt(bitmap: android.graphics.Bitmap): ExtractedTransaction? {
        val model = geminiModel ?: throw Exception("Gemini not configured")
        try {
            val response = model.generateContent(
                com.google.ai.client.generativeai.type.content {
                    image(bitmap)
                    text("""
                        Extract from this receipt: merchant name, total amount, currency, items.
                        Return ONLY raw JSON (no markdown):
                        {"amount":"...","category":"Shopping","type":"Expense","isBankRelated":true,"comment":"merchant name","last4Digits":null,"isStatement":false,"dueDate":null}

                        For category, use one of: $availableCategories
                        Use your knowledge of businesses to pick the right category.
                    """.trimIndent())
                }
            )
            val raw = response.text?.trim() ?: return null
            var cleaned = raw
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.lines().filter { !it.trim().startsWith("```") }.joinToString("\n")
            }
            return json.decodeFromString(cleaned)
        } catch (e: Exception) {
            Log.e("AiService", "Receipt OCR failed: ${e.message}", e)
            return null
        }
    }

    // ── Receipt Split OCR (Gemini vision) ──────────────────────────────────

    suspend fun scanReceiptForSplit(bitmap: android.graphics.Bitmap): ParsedReceipt? {
        val model = geminiModel ?: throw Exception("Gemini not configured")
        try {
            val response = model.generateContent(
                com.google.ai.client.generativeai.type.content {
                    image(bitmap)
                    text("""
                        Extract ALL line items from this receipt/bill. Return ONLY raw JSON (no markdown):
                        {
                          "merchant": "restaurant/store name",
                          "items": [
                            {"name": "item name", "quantity": 1, "unitPrice": 50.0, "totalPrice": 50.0}
                          ],
                          "subtotal": 0.0,
                          "tax": 0.0,
                          "serviceCharge": 0.0,
                          "discount": 0.0,
                          "total": 0.0,
                          "currency": "EGP"
                        }

                        Rules:
                        - Extract EVERY individual item with its name, quantity, unit price, and total price
                        - If quantity is not shown, assume 1
                        - Separate tax, service charge (tips/service %), and discount as top-level fields
                        - subtotal = sum of all items before tax/service/discount
                        - total = final amount paid
                        - Use the currency shown on the receipt, default to EGP
                        - For Arabic text, translate item names to English
                        - Be precise with numbers — use the exact values from the receipt
                    """.trimIndent())
                }
            )
            val raw = response.text?.trim() ?: return null
            var cleaned = raw
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.lines().filter { !it.trim().startsWith("```") }.joinToString("\n")
            }
            return json.decodeFromString(cleaned)
        } catch (e: Exception) {
            Log.e("AiService", "Receipt split OCR failed: ${e.message}", e)
            return null
        }
    }

    // ── Generic AI completion helper ─────────────────────────────────────────

    private suspend fun aiCompletion(prompt: String, maxTokens: Int = 300): String? {
        if (groqApiKey.isNotBlank()) {
            try { return groqCompletion(prompt) }
            catch (e: Exception) { Log.w("AiService", "Groq completion failed: ${e.message}") }
        }
        if (geminiApiKey.isNotBlank()) {
            try { return geminiCompletion(prompt) }
            catch (e: Exception) { Log.w("AiService", "Gemini completion failed: ${e.message}") }
        }
        if (cerebrasApiKey.isNotBlank()) {
            try { return cerebrasCompletion(prompt) }
            catch (e: Exception) { Log.w("AiService", "Cerebras completion failed: ${e.message}") }
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

        if (geminiApiKey.isNotBlank()) {
            section("Gemini ($GEMINI_MODEL) — inferCategory") { geminiCompletion(categoryPrompt(smsBody)) }
            section("Gemini ($GEMINI_MODEL) — analyzeSms")   { geminiCompletion(analyzePrompt(smsBody)) }
        }

        if (cerebrasApiKey.isNotBlank()) {
            section("Cerebras ($CEREBRAS_MODEL) — inferCategory") { cerebrasCompletion(categoryPrompt(smsBody)) }
            section("Cerebras ($CEREBRAS_MODEL) — analyzeSms")   { cerebrasCompletion(analyzePrompt(smsBody)) }
        }

        section("💡 Suggested new category (if Others)") {
            suggestNewCategory(smsBody) ?: "(no suggestion available)"
        }

        return sb.trimEnd().toString()
    }
}
