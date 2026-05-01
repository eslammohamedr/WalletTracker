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
    val type: String, // "Income", "Expense", "Statement", "CardPayment", or "AtmWithdrawal"
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

    /**
     * Asks Gemini to pick the single best category from [Categories.list] for the given SMS body.
     * Returns null on network/parse failure so callers can fall back to keyword matching.
     */
    suspend fun inferCategory(smsBody: String): String? {
        val prompt = """
            Classify this bank SMS into exactly ONE category from the list below.

            Categories: $availableCategories

            Rules (apply in order):
            - Reply with ONLY the exact category name from the list, nothing else — no punctuation, no explanation.
            - Instapay outward / IPN outward → "Instapay outcome"
            - Instapay inward / IPN inward → "Instapay income"
            - Salary or TT payment → "Salary"
            - Cashback reward → "Gifts"
            - ATM withdrawal → "Others"
            - Subscription services (Netflix, Spotify, YouTube, Amazon Prime, Disney+, Yango Play, Steam, PlayStation) → "Subscriptions"
            - Ride-hailing (Uber, Careem, InDrive) → "Uber"
            - Supermarkets / groceries (Carrefour, Metro, Kheir Zaman, Lulu, Panda, BEET ELGOMLA) → "Groceries"
            - Restaurants / fast food (KFC, McDonald's, Pizza, Talabat, restaurant) → "Restaurants"
            - Café / coffee → "Cafe"
            - Pharmacy / medical (El Ezaby, ALMOKHTBR, El Borg, pharmacy) → "Health and beauty"
            - Telecom / mobile / internet (Vodafone, Orange, Etisalat, WE, Fawry) → "Mobile"
            - Fuel / petrol / gas station → "Car"

            SMS: "$smsBody"
        """.trimIndent()

        return try {
            val response = model.generateContent(content { text(prompt) })
            val raw = response.text?.trim()?.removeSurrounding("\"") ?: return null
            val allCategories = Categories.list.flatMap { listOf(it.name) + it.subCategories.map { sub -> sub.name } }
            allCategories.find { it.equals(raw, ignoreCase = true) } ?: raw.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e("AiService", "inferCategory error", e)
            null
        }
    }

    suspend fun analyzeSms(smsBody: String): ExtractedTransaction? {
        val prompt = """
            You are a financial assistant. Analyze the following SMS message from a bank or payment service.
            
            TASKS:
            1. Identify if this is a transaction (money coming in/out), a credit card statement, a payment MADE TO a credit card, or an ATM Cash Withdrawal.
            2. Identify if this is just an OTP (One Time Password) message. If it is an OTP, set `isBankRelated` to false.
            3. Extract the numeric amount. Remove any commas (e.g., "2,202.20" becomes "2202.20").
            4. Identify the transaction type: 
               - "Income": money received, credited, deposit to debit account, salary, IPN inward, or Cashback rewards.
               - "Expense": money spent, debited from debit account, paid, used for, transfer out, IPN outward.
               - "Statement": credit card bill/statement issued notification.
               - "CardPayment": payment made TO a credit card (e.g., "Deposit to credit card", "Transfer to your Credit Card").
               - "AtmWithdrawal": money withdrawn from an ATM (e.g., "ATM Cash Withdrawal").
            5. Extract the last digits of the account or card mentioned (usually 3 or 4 digits). 
               - For "CardPayment", extract the digits of the CREDIT CARD being paid.
               - For "AtmWithdrawal", extract the digits of the DEBIT ACCOUNT.
               - For Cashback, extract the digits of the card it was credited to.
            6. Categorize the transaction into exactly ONE of these categories: [$availableCategories].
               - For Instapay transfers, use "Instapay outcome" (money going out) or "Instapay income" (money coming in).
               - For Cashback, use "Gifts" or "Income".
               - For "AtmWithdrawal", use "Others".
               - If it mentions "Uber", "Careem", "InDrive", or any ride-hailing/taxi service, use "Uber".
               - If it mentions telecom, mobile, or internet providers like "Vodafone", "Orange", "Etisalat", "WE", "Telecom Egypt", or "Fawry bill", use "Mobile" or "Internet".
               - If it mentions a subscription service like "YouTube", "Amazon", "Netflix", "Yango Play", "Spotify", "Disney+", etc., use "Subscriptions".
               - If it mentions Egyptian supermarkets like "BEET ELGOMLA", "Carrefour", "Panda", "Lulu", "Metro", "Kheir Zaman", or general groceries, use "Groceries".
               - If it mentions a cafe or coffee, use "Cafe".
               - If it mentions a restaurant or food, use "Restaurants" or "Fast food".
               - If it mentions medical labs or pharmacies like "ALMOKHTBR", "EL BORG", "EL EZABY", "19011", or "Pharmacy", use "Health and beauty".
               - For "CardPayment", use "Credit".
               - If it mentions "TT Payment" or "Salary", use "Salary".
               - For credit card statements, use "Others".
            7. For statements, extract the "due date" in DD/MM/YYYY format. Set `isStatement` to true.
            8. Provide a "comment":
               - For Instapay outward transfers ("IPN outward"), extract the FULL name of the person between the word 'to' and 'with reference'.
               - For Instapay inward transfers ("IPN inward"), extract the FULL name of the person between the word 'from' and 'with reference'.
               - For ATM Withdrawals, use "ATM Withdrawal".
               - For other transactions, use the merchant name or purpose.
            
            IMPORTANT: Set `isBankRelated` to true for any transaction from a known bank (like HSBC, BM, CIB) involving money debited, credited, or transferred.
            Set `isBankRelated` to false if the message is:
            - An OTP (One Time Password) message.
            - A promotional message from a telecom provider (Vodafone, Orange, etc.).
            - A general bank notification that doesn't involve a financial transaction or statement.

            EXAMPLES:
            
            SMS: "Your HSBC Account ********3001 was debited with IPN outward transfer for EGP 2,202.20 on 02-04-2026 23:47 to AHMED MOHAMED AHMED ABDELGHANY MABROUK with reference 83c38775. For further details, please contact HSBC call centre"
            JSON: {
              "amount": "2202.20",
              "category": "Instapay outcome",
              "type": "Expense",
              "isBankRelated": true,
              "last4Digits": "3001",
              "comment": "Ahmed Mohamed Ahmed Abdelghany Mabrouk"
            }

            SMS: "OTP: 123456 is your code for online purchase at Amazon. Do not share this code."
            JSON: {
              "amount": "0",
              "category": "Others",
              "type": "Expense",
              "isBankRelated": false,
              "last4Digits": null,
              "comment": "OTP Message"
            }

            SMS: "You have earned Cashback of EGP 64.43 credited to your card ending with *** 2505.Check your statement..."
            JSON: {
              "amount": "64.43",
              "category": "Gifts",
              "type": "Income",
              "isBankRelated": true,
              "last4Digits": "2505",
              "comment": "Cashback Reward"
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
