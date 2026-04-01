package com.example.wallettrackers.service

import android.util.Log
import com.example.wallettrackers.model.Categories
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ExtractedTransaction(
    val amount: String,
    val category: String,
    val type: String, // "Income" or "Expense"
    val isBankRelated: Boolean,
    val last4Digits: String? = null
)

class AiService(apiKey: String) {

    private val model = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey
    )

    private val json = Json { ignoreUnknownKeys = true }

    private val availableCategories = Categories.list.flatMap { parent -> 
        listOf(parent.name) + parent.subCategories.map { it.name } 
    }.distinct().joinToString(", ")

    suspend fun analyzeSms(smsBody: String): ExtractedTransaction? {
        val prompt = """
            Analyze the following SMS message and determine if it's a bank transaction (credit, debit, payment, or income).
            If it is, extract the amount, category, type (Income or Expense), and the last 4 digits of the card or account if mentioned.
            
            IMPORTANT: Choose the category ONLY from this list of supported categories in the app:
            [$availableCategories]
            
            If it's Starbucks, use "Cafe". If it's a grocery store, use "Groceries".
            
            Return the result ONLY as a JSON object with these keys:
            - amount (string, just the number)
            - category (string, must match one from the list above)
            - type (string, either "Income" or "Expense")
            - isBankRelated (boolean)
            - last4Digits (string of 4 digits or null)

            SMS: "$smsBody"
        """.trimIndent()

        return try {
            val response = model.generateContent(content { text(prompt) })
            val responseText = response.text?.replace("```json", "")?.replace("```", "")?.trim()
            
            if (responseText != null) {
                Log.d("AiService", "AI Response: $responseText")
                json.decodeFromString<ExtractedTransaction>(responseText)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("AiService", "Error analyzing SMS with AI", e)
            null
        }
    }
}