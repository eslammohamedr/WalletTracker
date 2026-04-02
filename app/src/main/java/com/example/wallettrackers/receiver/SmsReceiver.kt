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
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.service.AiService
import com.example.wallettrackers.service.ExtractedTransaction
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val CHANNEL_ID = "transaction_alerts"
    
    // API Key should be managed securely, but following the current pattern
    private val aiService = AiService("YOUR_GEMINI_API_KEY") 

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val messageBody = sms.displayMessageBody
                val timestamp = sms.timestampMillis
                
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser != null) {
                    processWithAi(context, currentUser.uid, messageBody, timestamp.toString(), Date(timestamp))
                }
            }
        }
    }

    private fun processWithAi(context: Context, userId: String, body: String, smsId: String, date: Date) {
        scope.launch {
            try {
                val result = aiService.analyzeSms(body)
                if (result != null && result.isBankRelated) {
                    val repository = FirebaseRepository(userId)
                    
                    if (result.type == "Statement" || result.isStatement) {
                        saveStatement(context, repository, userId, smsId, result)
                    } else {
                        saveRecord(context, repository, userId, smsId, date, result)
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing SMS with AI", e)
            }
        }
    }

    private suspend fun saveRecord(
        context: Context,
        repository: FirebaseRepository,
        userId: String,
        smsId: String,
        date: Date,
        ai: ExtractedTransaction
    ) {
        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        
        var targetAccount = accounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits))
        }

        // Fallback for Salary
        if (targetAccount == null && ai.category == "Salary") {
            targetAccount = accounts.maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
        }

        val record = Record(
            amount = ai.amount,
            category = ai.category,
            type = ai.type,
            accountId = targetAccount?.id ?: "",
            accountName = targetAccount?.name ?: "Imported Card (${ai.last4Digits})",
            currency = targetAccount?.currency ?: "EGP",
            userId = userId,
            timestamp = date,
            smsId = smsId
        )

        if (targetAccount != null) {
            val isIncome = ai.type == "Income"
            val currentBal = targetAccount.amount.toDoubleOrNull() ?: 0.0
            val recordAmt = ai.amount.toDoubleOrNull() ?: 0.0
            val newBal = if (isIncome) currentBal + recordAmt else currentBal - recordAmt
            repository.updateAccount(targetAccount.copy(amount = newBal.toString()))
        }

        repository.addRecord(record)
        sendRecordNotification(context, record)
    }

    private suspend fun saveStatement(
        context: Context,
        repository: FirebaseRepository,
        userId: String,
        smsId: String,
        ai: ExtractedTransaction
    ) {
        val dueDate = try {
            ai.dueDate?.let { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(it) } ?: Date()
        } catch (e: Exception) { Date() }

        val statement = CreditStatement(
            cardLast4Digits = ai.last4Digits ?: "0000",
            totalAmount = ai.amount.toDoubleOrNull() ?: 0.0,
            dueDate = dueDate,
            userId = userId,
            smsId = smsId
        )
        repository.addCreditStatement(statement)
        sendStatementNotification(context, statement)
    }

    private fun sendRecordNotification(context: Context, record: Record) {
        val title = if (record.accountId.isEmpty()) "Match Required" else "Transaction Added"
        val prefix = if (record.type == "Income") "+" else "-"
        sendNotification(context, title, "${record.category}: $prefix${record.amount} ${record.currency}")
    }

    private fun sendStatementNotification(context: Context, statement: CreditStatement) {
        val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(statement.dueDate)
        sendNotification(context, "Credit Card Bill Issued", "Card ****${statement.cardLast4Digits}: ${statement.totalAmount} EGP due by $dateStr")
    }

    private fun sendNotification(context: Context, title: String, text: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Finance Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
