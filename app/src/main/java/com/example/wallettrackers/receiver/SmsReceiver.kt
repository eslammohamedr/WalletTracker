package com.example.wallettrackers.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wallettrackers.MainActivity
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.service.AiService
import com.example.wallettrackers.service.ExtractedTransaction
import com.example.wallettrackers.util.ReminderManager
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
                    
                    when {
                        result.type == "Statement" || result.isStatement -> {
                            saveStatement(context, repository, userId, smsId, result)
                        }
                        result.type == "CardPayment" -> {
                            saveCardPayment(context, repository, userId, smsId, date, result)
                        }
                        result.type == "AtmWithdrawal" -> {
                            saveAtmWithdrawal(context, repository, userId, smsId, date, result)
                        }
                        else -> {
                            saveRecord(context, repository, userId, smsId, date, result)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SmsReceiver", "Error processing SMS with AI", e)
            }
        }
    }

    private suspend fun saveAtmWithdrawal(
        context: Context,
        repository: FirebaseRepository,
        userId: String,
        smsId: String,
        date: Date,
        ai: ExtractedTransaction
    ) {
        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        
        val sourceAccount = accounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
        }

        val cashAccount = accounts.find { it.accountType.equals("Cash", ignoreCase = true) }

        if (sourceAccount != null && cashAccount != null) {
            val amount = ai.amount.toDoubleOrNull() ?: 0.0
            val sourceBal = sourceAccount.amount.toDoubleOrNull() ?: 0.0
            val newSourceBal = sourceBal - amount
            repository.updateAccount(sourceAccount.copy(amount = newSourceBal.toString()))

            val cashBal = cashAccount.amount.toDoubleOrNull() ?: 0.0
            val newCashBal = cashBal + amount
            repository.updateAccount(cashAccount.copy(amount = newCashBal.toString()))

            val record = Record(
                amount = ai.amount,
                category = "Others",
                type = "Expense",
                accountId = sourceAccount.id,
                accountName = "${sourceAccount.name} -> Cash",
                currency = sourceAccount.currency,
                userId = userId,
                timestamp = date,
                smsId = smsId,
                comment = "ATM Withdrawal",
                balanceAfter = newSourceBal.toString()
            )
            repository.addRecord(record)
            sendNotification(context, "ATM Withdrawal", "Deducted ${ai.amount} from ${sourceAccount.name} and added to Cash.", true)
        } else {
            saveRecord(context, repository, userId, smsId, date, ai)
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
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
        }

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
            smsId = smsId,
            comment = ai.comment
        )

        if (targetAccount != null) {
            val isIncome = ai.type == "Income"
            val currentBal = targetAccount.amount.toDoubleOrNull() ?: 0.0
            val recordAmt = ai.amount.toDoubleOrNull() ?: 0.0
            val newBal = if (isIncome) currentBal + recordAmt else currentBal - recordAmt
            repository.updateAccount(targetAccount.copy(amount = newBal.toString()))
            repository.addRecord(record.copy(balanceAfter = newBal.toString()))
        } else {
            repository.addRecord(record)
        }
        
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
        ReminderManager.scheduleStatementReminders(context, statement)
        sendStatementNotification(context, statement)
    }

    private suspend fun saveCardPayment(
        context: Context,
        repository: FirebaseRepository,
        userId: String,
        smsId: String,
        date: Date,
        ai: ExtractedTransaction
    ) {
        val accounts = repository.getAccounts().first()
        val statements = repository.getCreditStatements().first()
        
        val creditDigits = ai.last4Digits ?: ""
        val matchedStatement = statements.find { it.cardLast4Digits == creditDigits && !it.isPaid }
        if (matchedStatement != null) {
            repository.updateCreditStatement(matchedStatement.copy(isPaid = true))
            ReminderManager.cancelReminders(context, matchedStatement.smsId)
        }

        val richestAccount = accounts.filter { !it.accountType.equals("Credit", ignoreCase = true) && !it.accountType.equals("Credit Card", ignoreCase = true) }
            .maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
            
        if (richestAccount != null) {
            val currentBal = richestAccount.amount.toDoubleOrNull() ?: 0.0
            val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0
            val newBal = currentBal - paymentAmt
            repository.updateAccount(richestAccount.copy(amount = newBal.toString()))

            val record = Record(
                amount = ai.amount,
                category = "Credit",
                type = "Expense",
                accountId = richestAccount.id,
                accountName = richestAccount.name,
                currency = richestAccount.currency,
                userId = userId,
                timestamp = date,
                smsId = smsId,
                balanceAfter = newBal.toString(),
                comment = ai.comment
            )
            repository.addRecord(record)
            sendRecordNotification(context, record)
        }
    }

    private fun sendRecordNotification(context: Context, record: Record) {
        val title = if (record.accountId.isEmpty()) "Match Required" else "Transaction Added"
        val prefix = if (record.type == "Income") "+" else "-"
        sendNotification(context, title, "${record.category}: $prefix${record.amount} ${record.currency}", true)
    }

    private fun sendStatementNotification(context: Context, statement: CreditStatement) {
        val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(statement.dueDate)
        sendNotification(context, "Credit Card Bill Issued", "Card ****${statement.cardLast4Digits}: ${statement.totalAmount} EGP due by $dateStr", false)
    }

    private fun sendNotification(context: Context, title: String, text: String, goToRecords: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Finance Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (goToRecords) {
                putExtra("navigate_to", "all_records")
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
}
