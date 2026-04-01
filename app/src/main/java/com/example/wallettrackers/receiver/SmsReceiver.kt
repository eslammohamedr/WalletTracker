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
    
    // NOTE: In a real app, you should fetch this securely from a backend or encrypted storage
    private val GEMINI_API_KEY = "YOUR_GEMINI_API_KEY" 
    private val CHANNEL_ID = "transaction_alerts"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val messageBody = sms.displayMessageBody
                val sender = sms.displayOriginatingAddress
                
                Log.d("SmsReceiver", "SMS received from $sender: $messageBody")
                
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    processSmsWithAi(context, currentUser.uid, messageBody)
                }
            }
        }
    }

    private fun processSmsWithAi(context: Context, userId: String, body: String) {
        scope.launch {
            val aiService = AiService(GEMINI_API_KEY)
            val result = aiService.analyzeSms(body)

            if (result != null && result.isBankRelated) {
                Log.d("SmsReceiver", "AI detected bank transaction: $result")
                
                val repository = FirebaseRepository(userId)
                
                // Fetch user's accounts to find a match for the last 4 digits
                val accounts = repository.getAccounts().first()
                val targetAccount = accounts.find { it.last4Digits == result.last4Digits }

                val record = if (targetAccount != null) {
                    Record(
                        amount = result.amount,
                        category = result.category,
                        accountId = targetAccount.id,
                        accountName = targetAccount.name,
                        userId = userId,
                        timestamp = Date()
                    )
                } else {
                    Record(
                        amount = result.amount,
                        category = result.category,
                        accountName = "SMS Import (No Match: ${result.last4Digits ?: "Unknown"})",
                        userId = userId,
                        timestamp = Date()
                    )
                }
                
                repository.addRecord(record)
                sendNotification(context, record)
                Log.d("SmsReceiver", "Record auto-added and notification sent.")
            } else {
                Log.d("SmsReceiver", "SMS ignored by AI (not bank related).")
            }
        }
    }

    private fun sendNotification(context: Context, record: Record) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Transaction Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for automatic SMS transaction imports"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val title = if (record.accountName.startsWith("SMS Import")) "New Transaction Detected" else "Transaction Added to ${record.accountName}"
        val message = "${record.category}: ${record.amount}"

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Replace with your app icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}