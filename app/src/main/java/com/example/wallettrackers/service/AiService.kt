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
    val type: String, // "Income", "Expense", "Statement", or "CardPayment"
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
            1. Identify if this is a transaction (money coming in/out), a credit card statement, or a payment MADE TO a credit card.
            2. Extract the numeric amount. Remove any commas (e.g., "53,848.10" becomes "53848.10").
            3. Identify the transaction type: 
               - "Income": money received, credited, deposit to debit account, salary, IPN inward.
               - "Expense": money spent, debited from debit account, paid, transfer out, IPN outward.
               - "Statement": credit card bill/statement issued notification.
               - "CardPayment": payment made TO a credit card (e.g., "Deposit to credit card", "Transfer to your Credit Card").
            4. Extract the last digits of the account or card mentioned (usually 3 or 4 digits). 
               - For "CardPayment", extract the digits of the CREDIT CARD being paid.
            5. Categorize the transaction into exactly ONE of these categories: [$availableCategories].
               - For "CardPayment", use "Credit".
               - If it mentions "TT Payment" or "Salary", use "Salary".
               - If it mentions "IPN outward" or "Instapay" and money is going out, use "Instapay outcome".
               - For credit card statements, use "Others".
            6. For statements, extract the "due date" in DD/MM/YYYY format. Set `isStatement` to true.
            
            EXAMPLES:
            
            SMS: "Deposit of EGP 10000 was made to BM credit card ending ****7000 at BM-Online..."
            JSON: {
              "amount": "10000",
              "category": "Credit",
              "type": "CardPayment",
              "isBankRelated": true,
              "last4Digits": "7000"
            }

            SMS: "From HSBC: 26MAR26 Transfer from 074-151***-001 EGP 1,798.04- to your Credit Card ending with 2601..."
            JSON: {
              "amount": "1798.04",
              "category": "Credit",
              "type": "CardPayment",
              "isBankRelated": true,
              "last4Digits": "2601"
            }

            SMS: "Dear customer, your card ****7000 statement is issued with total EGP 6643.33, due before 26/04/2026"
            JSON: {
              "amount": "6643.33",
              "category": "Others",
              "type": "Statement",
              "isBankRelated": true,
              "last4Digits": "7000",
              "isStatement": true,
              "dueDate": "26/04/2026"
            }

            Return the result ONLY as a raw JSON object. Do not include markdown formatting.
            
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
