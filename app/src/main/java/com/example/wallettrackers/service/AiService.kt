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
    val dueDate: String? = null, // Format: DD/MM/YYYY
    val comment: String = ""
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
               - "Expense": money spent, debited from debit account, paid, used for, transfer out, IPN outward.
               - "Statement": credit card bill/statement issued notification.
               - "CardPayment": payment made TO a credit card (e.g., "Deposit to credit card", "Transfer to your Credit Card").
            4. Extract the last digits of the account or card mentioned (usually 3 or 4 digits). 
               - For "CardPayment", extract the digits of the CREDIT CARD being paid.
            5. Categorize the transaction into exactly ONE of these categories: [$availableCategories].
               - If it mentions "Uber", "Careem", "InDrive", or any ride-hailing/taxi service, use "Uber".
               - If it mentions telecom, mobile, or internet providers like "DUBAI TELECOM", "Vodafone", "Orange", "Etisalat", "WE", "Telecom Egypt", or "Fawry bill", use "Mobile" or "Internet".
               - If it mentions a subscription service like "YouTube", "Amazon", "Netflix", "Yango Play", "Spotify", "Disney+", etc., use "Subscriptions".
               - If it mentions Egyptian supermarkets like "BEET ELGOMLA", "Carrefour", "Panda", "Lulu", "Metro", "Kheir Zaman", or general groceries, use "Groceries".
               - If it mentions a cafe or coffee, use "Cafe".
               - If it mentions a restaurant or food, use "Restaurants" or "Fast food".
               - If it mentions medical labs or pharmacies like "ALMOKHTBR", "EL BORG", "EL EZABY", "19011", or "Pharmacy", use "Health and beauty".
               - For "CardPayment", use "Credit".
               - If it mentions "TT Payment" or "Salary", use "Salary".
               - If it mentions "IPN outward" or "Instapay" and money is going out, use "Instapay outcome".
               - For credit card statements, use "Others".
            6. For statements, extract the "due date" in DD/MM/YYYY format. Set `isStatement` to true.
            7. Provide a short "comment" (maximum 5 words) describing the merchant or purpose (e.g., "Beet El Gomla", "Netflix Subscription", "Uber Trip").
            
            EXAMPLES:
            
            SMS: "Your Credit Card ending with *** 2601 has been used for EGP 99.95 on 25/03/2026 at . Your available limit is EGP 113682.50"
            JSON: {
              "amount": "99.95",
              "category": "Uber",
              "type": "Expense",
              "isBankRelated": true,
              "last4Digits": "2601",
              "comment": "Uber Trip"
            }

            SMS: "Thank you for using BM credit card *****7000 now debited by EGP 716.75 at BEET ELGOMLA on 17/03/2026..."
            JSON: {
              "amount": "716.75",
              "category": "Groceries",
              "type": "Expense",
              "isBankRelated": true,
              "last4Digits": "7000",
              "comment": "Beet El Gomla"
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
