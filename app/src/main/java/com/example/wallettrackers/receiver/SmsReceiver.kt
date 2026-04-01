package com.example.wallettrackers.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val CHANNEL_ID = "transaction_alerts"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val messageBody = sms.displayMessageBody
                val sender = sms.displayOriginatingAddress
                val timestamp = sms.timestampMillis
                
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    processSms(context, currentUser.uid, messageBody, timestamp.toString(), Date(timestamp))
                }
            }
        }
    }

    private fun processSms(context: Context, userId: String, body: String, smsId: String, date: Date) {
        scope.launch {
            try {
                if (isBankSms(body)) {
                    val amount = extractAmount(body)
                    if (amount != null) {
                        val repository = FirebaseRepository(userId)
                        val accounts = repository.getAccounts().first()
                        
                        val digits = extractLast4Digits(body)
                        val smsDigitsOnly = digits?.filter { it.isDigit() } ?: ""
                        val type = inferType(body)
                        
                        val targetAccount = accounts.find { acc ->
                            val accDigitsOnly = acc.last4Digits.filter { it.isDigit() }
                            accDigitsOnly.isNotEmpty() && smsDigitsOnly.isNotEmpty() && (
                                accDigitsOnly == smsDigitsOnly || 
                                smsDigitsOnly.endsWith(accDigitsOnly) || 
                                accDigitsOnly.endsWith(smsDigitsOnly)
                            )
                        }

                        val record = if (targetAccount != null) {
                            Record(
                                amount = amount,
                                category = "Others",
                                type = type,
                                accountId = targetAccount.id,
                                accountName = targetAccount.name,
                                currency = targetAccount.currency,
                                userId = userId,
                                timestamp = date,
                                smsId = smsId
                            )
                        } else {
                            Record(
                                amount = amount,
                                category = "Others",
                                type = type,
                                accountName = "SMS Import (No Match: ${digits ?: "Unknown"})",
                                currency = "EGP",
                                userId = userId,
                                timestamp = date,
                                smsId = smsId
                            )
                        }
                        
                        repository.addRecord(record)
                        sendNotification(context, record)
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing SMS", e)
            }
        }
    }

    private fun isBankSms(body: String): Boolean {
        val keywords = listOf("bank", "debited", "credited", "spent", "transaction", "otp", "account", "visa", "mastercard", "purchase", "transfer", "paid", "egp")
        return keywords.any { body.contains(it, ignoreCase = true) }
    }

    private fun inferType(body: String): String {
        val incomeKeywords = listOf("credited", "received", "deposit", "returned", "salary")
        return if (incomeKeywords.any { body.contains(it, ignoreCase = true) }) "Income" else "Expense"
    }

    private fun extractAmount(body: String): String? {
        val regex = Regex("""(?:EGP|USD|EUR|LE|Amount:?)\s*(\d+[\.,]\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(body)
        if (match != null) return match.groupValues[1]
        return Regex("""(\d+[\.,]\d+)""").find(body)?.value
    }

    private fun extractLast4Digits(body: String): String? {
        Regex("""[^0-9\s]{2,}(\d{4})""").find(body)?.let { return it.groupValues[1] }
        Regex("""(?:Account|card|A/c|ending|no\.?)\s*(?:[^0-9\s]+)?(\d{4})""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1] }
        val allFourDigits = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        return allFourDigits.find { it.toIntOrNull() !in 1900..2100 } ?: allFourDigits.firstOrNull()
    }

    private fun sendNotification(context: Context, record: Record) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Transaction Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val typePrefix = if (record.type == "Income") "+" else "-"
        val title = if (record.accountId.isEmpty()) "Action Required: Match Account" else "Transaction Added to ${record.accountName}"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("${record.category}: $typePrefix${record.amount} ${record.currency}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
