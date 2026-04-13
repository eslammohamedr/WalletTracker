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
import com.example.wallettrackers.model.Account
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
    
    private val aiService = AiService("AIzaSyAxdeJgJcVOe36H2BT6PQ-IU3hYhv4k0Pg") 

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val pendingResult = goAsync()
            
            scope.launch {
                try {
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    if (currentUser != null) {
                        for (sms in messages) {
                            val body = sms.displayMessageBody
                            val timestamp = sms.timestampMillis
                            processSmsLocallyAndWithAi(context, currentUser.uid, body, timestamp.toString(), Date(timestamp))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Error in onReceive", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    private suspend fun processSmsLocallyAndWithAi(context: Context, userId: String, body: String, smsId: String, date: Date) {
        if (isBankSms(body)) {
            val manualAmount = extractAmount(body)
            val manualType = inferType(body)
            
            if (manualAmount != null) {
                val manualCategory = if (manualType == "Statement") "Credit Card" else inferCategory(body)
                val manualComment = inferComment(body)
                val manualDigits = extractLast4Digits(body)
                val manualDueDate = extractDueDate(body)
                
                val repository = FirebaseRepository(userId)
                
                if (manualType == "Statement") {
                    saveStatement(context, repository, userId, smsId, ExtractedTransaction(
                        amount = manualAmount,
                        category = "Credit Card",
                        type = "Statement",
                        isBankRelated = true,
                        last4Digits = manualDigits,
                        isStatement = true,
                        dueDate = manualDueDate,
                        comment = manualComment ?: ""
                    ))
                    return
                } else if (manualType == "AtmWithdrawal") {
                    saveAtmWithdrawal(context, repository, userId, smsId, date, ExtractedTransaction(
                        amount = manualAmount,
                        category = "Others",
                        type = "AtmWithdrawal",
                        isBankRelated = true,
                        last4Digits = manualDigits,
                        comment = "ATM Withdrawal"
                    ))
                    return 
                } else if (manualType == "CardPayment") {
                    saveCardPayment(context, repository, userId, smsId, date, ExtractedTransaction(
                        amount = manualAmount,
                        category = "Credit",
                        type = "CardPayment",
                        isBankRelated = true,
                        last4Digits = manualDigits,
                        comment = manualComment ?: ""
                    ))
                    return 
                } else {
                    val accounts = repository.getAccounts().first()
                    val digits = manualDigits?.filter { it.isDigit() } ?: ""
                    val matchedAccount = accounts.find { acc ->
                        val accDigits = acc.last4Digits.filter { it.isDigit() }
                        accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
                    }
                    
                    if (matchedAccount != null || manualCategory == "Salary") {
                        saveRecord(context, repository, userId, smsId, date, ExtractedTransaction(
                            amount = manualAmount,
                            category = manualCategory,
                            type = manualType,
                            isBankRelated = true,
                            last4Digits = manualDigits,
                            comment = manualComment ?: ""
                        ))
                        return 
                    }
                }
            }
        }

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
            Log.e("SmsReceiver", "AI Fallback failed", e)
        }
    }

    private suspend fun saveAtmWithdrawal(context: Context, repository: FirebaseRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction) {
        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val sourceAccount = accounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
        }
        val cashAccount = accounts.find { it.accountType.equals("Cash", ignoreCase = true) }

        if (sourceAccount != null && cashAccount != null) {
            val amount = ai.amount.toDoubleOrNull() ?: 0.0
            repository.updateAccount(sourceAccount.copy(amount = ((sourceAccount.amount.toDoubleOrNull() ?: 0.0) - amount).toString()))
            repository.updateAccount(cashAccount.copy(amount = ((cashAccount.amount.toDoubleOrNull() ?: 0.0) + amount).toString()))

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
                balanceAfter = ((sourceAccount.amount.toDoubleOrNull() ?: 0.0) - amount).toString()
            )
            repository.addRecord(record)
            sendNotification(context, "ATM Withdrawal Added", "Deducted ${ai.amount} from ${sourceAccount.name} and added to Cash.", true)
        }
    }

    private suspend fun saveRecord(context: Context, repository: FirebaseRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction) {
        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        var targetAccount = accounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
        }

        if (targetAccount == null && ai.category == "Salary") {
            targetAccount = accounts.maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
        }

        val amountDouble = ai.amount.toDoubleOrNull() ?: 0.0
        val isIncome = ai.type == "Income"
        
        val balanceAfter = if (targetAccount != null) {
            val currentBal = targetAccount.amount.toDoubleOrNull() ?: 0.0
            
            // For Credit Cards, we treat 'amount' as 'Available Credit'
            // Expense reduces available credit, Income (Refund) increases it
            val newBal = if (isIncome) currentBal + amountDouble else currentBal - amountDouble
            
            repository.updateAccount(targetAccount.copy(amount = newBal.toString()))
            newBal.toString()
        } else ""

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
            comment = ai.comment,
            balanceAfter = balanceAfter
        )

        repository.addRecord(record)
        sendRecordNotification(context, record)
    }

    private suspend fun saveStatement(context: Context, repository: FirebaseRepository, userId: String, smsId: String, ai: ExtractedTransaction) {
        val dueDate = try {
            ai.dueDate?.let { 
                val format = if (it.contains("/")) "dd/MM/yyyy" else "dd-MM-yyyy"
                SimpleDateFormat(format, Locale.getDefault()).parse(it) 
            } ?: Date()
        } catch (e: Exception) { Date() }

        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val matchedAccount = accounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
        }

        val statement = CreditStatement(
            cardLast4Digits = ai.last4Digits ?: "0000",
            accountId = matchedAccount?.id ?: "",
            totalAmount = ai.amount.toDoubleOrNull() ?: 0.0,
            dueDate = dueDate,
            userId = userId,
            smsId = smsId
        )
        repository.addCreditStatement(statement)
        ReminderManager.scheduleStatementReminders(context, statement)
        sendStatementNotification(context, statement)
    }

    private suspend fun saveCardPayment(context: Context, repository: FirebaseRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction) {
        val accounts = repository.getAccounts().first()
        val statements = repository.getCreditStatements().first()
        
        val creditDigits = ai.last4Digits ?: ""
        
        // 1. Mark statement as paid
        val matchedStatement = statements.find { it.cardLast4Digits == creditDigits && !it.isPaid }
        if (matchedStatement != null) {
            repository.updateCreditStatement(matchedStatement.copy(isPaid = true))
            ReminderManager.cancelReminders(context, matchedStatement.smsId)
        }

        // 2. Find the Credit Card Account to increase its available credit
        val creditAccount = accounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && creditDigits.isNotEmpty() && (accDigits == creditDigits || creditDigits.endsWith(accDigits) || accDigits.endsWith(creditDigits))
        }

        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0

        if (creditAccount != null) {
            val currentAvailable = creditAccount.amount.toDoubleOrNull() ?: 0.0
            repository.updateAccount(creditAccount.copy(amount = (currentAvailable + paymentAmt).toString()))
        }

        // 3. Find the Source Account (where money was taken from)
        val sourceAccount = accounts.filter { !it.accountType.contains("Credit", ignoreCase = true) }
            .maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
            
        if (sourceAccount != null) {
            val currentBal = sourceAccount.amount.toDoubleOrNull() ?: 0.0
            val newBal = currentBal - paymentAmt
            repository.updateAccount(sourceAccount.copy(amount = newBal.toString()))

            val record = Record(
                amount = ai.amount,
                category = "Credit Payment",
                type = "Expense",
                accountId = sourceAccount.id,
                accountName = "${sourceAccount.name} -> ${creditAccount?.name ?: "Credit Card"}",
                currency = sourceAccount.currency,
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

    private fun isBankSms(body: String): Boolean {
        val keywords = listOf("bank", "debited", "credited", "spent", "transaction", "otp", "account", "visa", "mastercard", "purchase", "transfer", "paid", "egp", "statement", "due before", "due date", "made to credit card", "IPN")
        return keywords.any { body.contains(it, ignoreCase = true) }
    }

    private fun inferType(body: String): String {
        val bodyLower = body.lowercase()
        
        if (bodyLower.contains("total amt due") || 
            bodyLower.contains("min. amt due") || 
            bodyLower.contains("statement is issued") ||
            bodyLower.contains("statement date")) {
            return "Statement"
        }

        if (bodyLower.contains("statement") || bodyLower.contains("due before") || bodyLower.contains("due date")) {
            if (bodyLower.contains("amt due") || bodyLower.contains("total egp")) return "Statement"
            if (bodyLower.contains("paid") || bodyLower.contains("received")) return "CardPayment"
            return "Statement"
        }
        
        if (bodyLower.contains("made to credit card") || (bodyLower.contains("transfer") && bodyLower.contains("credit card"))) return "CardPayment"
        if (bodyLower.contains("withdrawal")) return "AtmWithdrawal"
        
        val incomeKeywords = listOf("credited", "received", "deposit", "returned", "salary", "TT Payment", "IPN inward")
        if (incomeKeywords.any { bodyLower.contains(it) }) return "Income"
        if (body.contains("+")) return "Income"
        
        return "Expense"
    }

    private fun inferCategory(body: String): String {
        return when {
            body.contains("IPN outward", ignoreCase = true) -> "Instapay outcome"
            body.contains("IPN inward", ignoreCase = true) -> "Instapay income"
            body.contains("Salary", ignoreCase = true) || body.contains("TT Payment", ignoreCase = true) -> "Salary"
            body.contains("BEET ELGOMLA", ignoreCase = true) || body.contains("Carrefour", ignoreCase = true) -> "Groceries"
            body.contains("Uber", ignoreCase = true) || body.contains("Careem", ignoreCase = true) -> "Uber"
            body.contains("Netflix", ignoreCase = true) || body.contains("YouTube", ignoreCase = true) || body.contains("Amazon", ignoreCase = true) -> "Subscriptions"
            else -> "Others"
        }
    }

    private fun inferComment(body: String): String? {
        val toNameRegex = Regex("""to\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val fromNameRegex = Regex("""from\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val atMerchantRegex = Regex("""at\s+(.*?)(?:\.|\s+on|\s+Your|$)""", RegexOption.IGNORE_CASE)

        return toNameRegex.find(body)?.groupValues?.get(1)?.trim()
            ?: fromNameRegex.find(body)?.groupValues?.get(1)?.trim()
            ?: atMerchantRegex.find(body)?.groupValues?.get(1)?.trim()
    }

    private fun extractAmount(body: String): String? {
        val amountPattern = """([\d,]+\.\d{2}|[\d\.]+\,\d{2}|\d+[\.,]\d+|\d+)"""
        
        // 1. Total Amt Due EGP 8,850.16
        val totalDueRegex = Regex("""Total Amt Due\s*(?:EGP|USD|EUR|LE)?\s*$amountPattern""", RegexOption.IGNORE_CASE)
        totalDueRegex.find(body)?.let { return it.groupValues[1].replace(",", "") }

        // 2. total EGP 6643.33
        val totalEgpRegex = Regex("""total\s+(?:EGP|USD|EUR|LE)?\s*$amountPattern""", RegexOption.IGNORE_CASE)
        totalEgpRegex.find(body)?.let { return it.groupValues[1].replace(",", "") }

        // 3. General
        val generalRegex = Regex("""(?:EGP|USD|EUR|LE|Amount:?|total|Due)\s*$amountPattern""", RegexOption.IGNORE_CASE)
        generalRegex.find(body)?.let { return it.groupValues[1].replace(",", "") }
        
        return Regex(amountPattern).find(body)?.value?.replace(",", "")
    }

    private fun extractLast4Digits(body: String): String? {
        Regex("""(?:\*+|-|card|A/c|ending)\s*(\d{3,4})\b""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1] }
        val allFourDigits = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        return allFourDigits.find { it.toIntOrNull() !in 1900..2100 } ?: allFourDigits.firstOrNull()
    }

    private fun extractDueDate(body: String): String? {
        val regex = Regex("""(?:Due Date|due before)\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun sendRecordNotification(context: Context, record: Record) {
        val title = if (record.accountId.isEmpty()) "Action Required: Match Account" else "Transaction Added Automatically"
        val prefix = if (record.type == "Income") "+" else "-"
        sendNotification(context, title, "${record.category}: $prefix${record.amount} ${record.currency}", true)
    }

    private fun sendStatementNotification(context: Context, statement: CreditStatement) {
        val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(statement.dueDate)
        sendNotification(context, "Credit Card Bill Issued", "Card ****${statement.cardLast4Digits}: ${statement.totalAmount} EGP due by $dateStr", false)
    }

    private fun sendNotification(context: Context, title: String, text: String, goToRecords: Boolean) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "transaction_alerts"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Transaction Alerts", NotificationManager.IMPORTANCE_DEFAULT)
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
