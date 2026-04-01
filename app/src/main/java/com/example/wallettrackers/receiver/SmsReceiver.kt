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
import com.example.wallettrackers.service.AiService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY" 
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
                    processSmsWithAi(context, currentUser.uid, messageBody, timestamp.toString())
                }
            }
        }
    }

    private fun processSmsWithAi(context: Context, userId: String, body: String, smsId: String) {
        scope.launch {
            val aiService = AiService(GEMINI_API_KEY)
            val result = aiService.analyzeSms(body)

            if (result != null && result.isBankRelated) {
                val repository = FirebaseRepository(userId)
                val accounts = repository.getAccounts().first()
                
                // ROBUST MATCHING: Clean both strings to digits only
                val smsDigitsOnly = result.last4Digits?.filter { it.isDigit() } ?: ""
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
                        amount = result.amount,
                        category = result.category,
                        accountId = targetAccount.id,
                        accountName = targetAccount.name,
                        currency = targetAccount.currency,
                        userId = userId,
                        timestamp = Date(),
                        smsId = smsId // This now correctly matches the SMS date/timestamp
                    )
                } else {
                    Record(
                        amount = result.amount,
                        category = result.category,
                        accountName = "SMS Import (No Match: ${result.last4Digits})",
                        userId = userId,
                        timestamp = Date(),
                        smsId = smsId
                    )
                }
                
                repository.addRecord(record)
                sendNotification(context, record)
            }
        }
    }

    private fun sendNotification(context: Context, record: Record) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Transaction Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val title = if (record.accountId.isEmpty()) "Action Required: Match Account" else "Transaction Added to ${record.accountName}"
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText("${record.category}: ${record.amount} ${record.currency}")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}