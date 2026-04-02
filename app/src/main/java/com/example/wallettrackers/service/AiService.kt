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
    val type: String, // "Income", "Expense", or "Statement"
    val isBankRelated: Boolean,
    val last4Digits: String? = null,
    val isStatement: Boolean = false,
    val dueDate: String? = null // Format: DD/MM/YYYY
)

class AiService(apiKey: String) {

    private val model = GenerativeModel(
        modelName = "gemini-1.5-flash",
        apiKey = apiKey
    )

    private val json = Json { 
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

    private val availableCategories = Categories.list.flatMap { parent -> 
        listOf(parent.name) + parent.subCategories.map { it.name } 
    }.distinct().joinToString(", ")

    suspend fun analyzeSms(smsBody: String): ExtractedTransaction? {
        val prompt = """
            You are a financial assistant. Analyze the following SMS message from a bank or payment service.
            
            TASKS:
            1. Identify if this is a transaction (money coming in or going out) OR a credit card statement notification.
            2. Extract the numeric amount. Remove any commas (e.g., "53,848.10" becomes "53848.10"). 
               - For statements, extract the "total" or "statement" amount.
            3. Identify the transaction type: 
               - "Income": money received, credited, deposit, salary, IPN inward.
               - "Expense": money spent, debited, paid, transfer out, IPN outward.
               - "Statement": credit card bill or statement issued notification.
            4. Extract the last digits of the account or card mentioned (usually 3 or 4 digits). Ignore any dates or years like 2024, 2025, 2026.
            5. Categorize the transaction into exactly ONE of these categories: [$availableCategories].
               - If it mentions "TT Payment" or "Salary", use "Salary".
               - If it mentions "IPN outward", "IPN outward transfer", or "Instapay" and money is going out, use "Instapay outcome".
               - If it mentions "IPN inward", "IPN inward transfer", or "Instapay" and money is coming in, use "Instapay income".
               - For credit card statements, use "Others".
            6. For statements, extract the "due date" in DD/MM/YYYY format. Set `isStatement` to true.
            
            EXAMPLES:
            
            SMS: "Dear customer, your card ****7000 statement is issued with total EGP 6643.33, minimum due is EGP 332.17, due before 26/04/2026"
            JSON: {
              "amount": "6643.33",
              "category": "Others",
              "type": "Statement",
              "isBankRelated": true,
              "last4Digits": "7000",
              "isStatement": true,
              "dueDate": "26/04/2026"
            }

            SMS: "From HSBC: 18MAR26 TT Payment to 074-151***-001 EGP 53,848.10+ ..."
            JSON: {
              "amount": "53848.10",
              "category": "Salary",
              "type": "Income",
              "isBankRelated": true,
              "last4Digits": "001",
              "isStatement": false,
              "dueDate": null
            }
            
            SMS: "Your HSBC Account ********3001 was debited with IPN outward transfer for EGP 275.50 on 01-04-2026..."
            JSON: {
              "amount": "275.50",
              "category": "Instapay outcome",
              "type": "Expense",
              "isBankRelated": true,
              "last4Digits": "3001",
              "isStatement": false,
              "dueDate": null
            }

            Return the result ONLY as a raw JSON object. Do not include markdown formatting.
            JSON structure:
            {
              "amount": "string",
              "category": "string",
              "type": "string",
              "isBankRelated": true or false,
              "last4Digits": "string or null",
              "isStatement": true or false,
              "dueDate": "string or null"
            }

            SMS Message: "$smsBody"
        """.trimIndent()

        var responseText: String? = null
        return try {
            val response = model.generateContent(content { text(prompt) })
            responseText = response.text?.trim()
            
            if (responseText == null) return null
            
            var cleanedJson = responseText
            if (cleanedJson.startsWith("```")) {
                cleanedJson = cleanedJson.lines().filter { !it.trim().startsWith("```") }.joinToString("\n")
            }
            
            Log.d("AiService", "Cleaned AI Response: $cleanedJson")
            json.decodeFromString<ExtractedTransaction>(cleanedJson)
        } catch (e: Exception) {
            Log.e("AiService", "Error analyzing SMS with AI. Response text was: ${responseText ?: "null"}", e)
            null
        }
    }
}
