package com.example.wallettrackers.viewmodel

import android.app.Application
import android.provider.Telephony
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SmsMessage
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.service.AiService
import com.example.wallettrackers.service.ExtractedTransaction
import com.example.wallettrackers.util.ReminderManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SmsViewModel(application: Application, private val userId: String) : AndroidViewModel(application) {

    private val _smsMessages = mutableStateOf<List<SmsMessage>>(emptyList())
    val smsMessages: State<List<SmsMessage>> = _smsMessages

    private val _accounts = mutableStateOf<List<Account>>(emptyList())
    val accounts: State<List<Account>> = _accounts

    private val _loadingSmsIds = mutableStateListOf<String>()
    val loadingSmsIds: List<String> = _loadingSmsIds

    private val _isBatchProcessing = mutableStateOf(false)
    val isBatchProcessing: State<Boolean> = _isBatchProcessing

    private val _batchTotal = mutableIntStateOf(0)
    val batchTotal: State<Int> = _batchTotal

    private val _batchCurrent = mutableIntStateOf(0)
    val batchCurrent: State<Int> = _batchCurrent

    private val _toastMessage = mutableStateOf<String?>(null)
    val toastMessage: State<String?> = _toastMessage

    private val repository = FirebaseRepository(userId)
    
    // API Key should ideally be in BuildConfig or strings.xml
    private val aiService = AiService("YOUR_GEMINI_API_KEY") 

    private var observeJob: Job? = null

    init {
        observeData()
    }

    fun fetchSms() {
        observeData()
    }

    private fun observeData() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                repository.getAccounts(),
                repository.getRecords(),
                repository.getCreditStatements()
            ) { accounts, records, statements ->
                _accounts.value = accounts
                val rawSms = fetchRawSmsFromInbox()
                matchSmsWithData(rawSms, accounts, records, statements)
            }.collect { }
        }
    }

    private fun fetchRawSmsFromInbox(): List<SmsMessage> {
        val messages = mutableListOf<SmsMessage>()
        val context = getApplication<Application>().applicationContext
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.Inbox._ID, Telephony.Sms.Inbox.BODY, Telephony.Sms.Inbox.ADDRESS, Telephony.Sms.Inbox.DATE),
            null, null, Telephony.Sms.Inbox.DATE + " DESC"
        )

        cursor?.use {
            val idIndex = it.getColumnIndex(Telephony.Sms.Inbox._ID)
            val bodyIndex = it.getColumnIndex(Telephony.Sms.Inbox.BODY)
            val addressIndex = it.getColumnIndex(Telephony.Sms.Inbox.ADDRESS)
            val dateIndex = it.getColumnIndex(Telephony.Sms.Inbox.DATE)

            while (it.moveToNext()) {
                messages.add(SmsMessage(
                    id = it.getString(idIndex),
                    body = it.getString(bodyIndex),
                    sender = it.getString(addressIndex),
                    timestamp = Date(it.getLong(dateIndex))
                ))
            }
        }
        return messages
    }

    private fun matchSmsWithData(
        rawSms: List<SmsMessage>, 
        currentAccounts: List<Account>, 
        currentRecords: List<Record>,
        currentStatements: List<CreditStatement>
    ) {
        val processedMessages = rawSms.map { sms ->
            val isBank = isBankSms(sms.body)
            
            val linkedRecord = currentRecords.find { it.smsId == sms.id }
            val linkedStatement = currentStatements.find { it.smsId == sms.id }
            
            var missingReason: String? = null
            var extractedAmt: String? = null
            var digits: String? = null
            var category: String? = null
            var type: String? = null

            if (isBank && linkedRecord == null && linkedStatement == null) {
                extractedAmt = extractAmount(sms.body)
                digits = extractLast4Digits(sms.body)
                type = inferType(sms.body)
                category = "Others"
                
                if (type == "Statement") {
                    missingReason = "Credit Statement Detected"
                } else if (type == "CardPayment") {
                    missingReason = "Credit Card Payment Detected"
                } else {
                    val smsDigitsOnly = digits?.filter { it.isDigit() } ?: ""
                    val matchedAccount = currentAccounts.find { acc ->
                        val accDigitsOnly = acc.last4Digits.filter { it.isDigit() }
                        accDigitsOnly.isNotEmpty() && smsDigitsOnly.isNotEmpty() && (
                            accDigitsOnly == smsDigitsOnly || 
                            smsDigitsOnly.endsWith(accDigitsOnly) || 
                            accDigitsOnly.endsWith(smsDigitsOnly)
                        )
                    }

                    missingReason = when {
                        extractedAmt == null -> "Amount not detected"
                        digits == null -> "Account digits not found"
                        matchedAccount == null -> "No match ($digits)"
                        else -> null
                    }
                }
            } else if (linkedRecord != null) {
                digits = linkedRecord.accountName.takeLast(4)
                category = linkedRecord.category
                type = linkedRecord.type
            } else if (linkedStatement != null) {
                digits = linkedStatement.cardLast4Digits
                type = if (linkedStatement.isPaid) "CardPayment" else "Statement"
                category = "Credit Card"
            }

            sms.copy(
                isBankRelated = isBank,
                hasRecordAdded = linkedRecord != null || linkedStatement?.isPaid == true,
                linkedRecord = linkedRecord,
                missingInfoReason = missingReason,
                extractedAmount = extractedAmt,
                last4Digits = digits,
                extractedCategory = category,
                extractedType = type
            )
        }
        _smsMessages.value = processedMessages
    }

    fun trackSmsManually(message: SmsMessage) {
        if (_loadingSmsIds.contains(message.id)) return
        
        _loadingSmsIds.add(message.id)
        viewModelScope.launch {
            try {
                val result = aiService.analyzeSms(message.body)
                if (result != null && result.isBankRelated) {
                    when {
                        result.type == "Statement" || result.isStatement -> saveStatement(message, result)
                        result.type == "CardPayment" -> saveCardPayment(message, result)
                        else -> saveRecord(message, result)
                    }
                    _toastMessage.value = "Processed successfully!"
                } else {
                    _toastMessage.value = "AI could not process this message correctly."
                }
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Error manual track", e)
                _toastMessage.value = "Error: ${e.message}"
            } finally {
                _loadingSmsIds.remove(message.id)
            }
        }
    }

    private suspend fun saveRecord(message: SmsMessage, ai: ExtractedTransaction) {
        val currentAccounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        
        var targetAccount = currentAccounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits))
        }

        if (targetAccount == null && ai.category == "Salary") {
            targetAccount = currentAccounts.maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
        }

        val record = Record(
            amount = ai.amount,
            category = ai.category,
            type = ai.type,
            accountId = targetAccount?.id ?: "",
            accountName = targetAccount?.name ?: "Imported Card (${ai.last4Digits})",
            currency = targetAccount?.currency ?: "EGP",
            userId = userId,
            timestamp = message.timestamp,
            smsId = message.id,
            comment = ai.comment // Now passing the comment from AI
        )
        
        if (targetAccount != null) {
            val isIncome = ai.type == "Income"
            val currentBal = targetAccount.amount.toDoubleOrNull() ?: 0.0
            val recordAmt = ai.amount.toDoubleOrNull() ?: 0.0
            val newBal = if (isIncome) currentBal + recordAmt else currentBal - recordAmt
            repository.updateAccount(targetAccount.copy(amount = newBal.toString()))
        }

        repository.addRecord(record)
    }

    private suspend fun saveStatement(message: SmsMessage, ai: ExtractedTransaction) {
        val date = try {
            ai.dueDate?.let { SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(it) } ?: Date()
        } catch (e: Exception) { Date() }

        val statement = CreditStatement(
            cardLast4Digits = ai.last4Digits ?: "0000",
            totalAmount = ai.amount.toDoubleOrNull() ?: 0.0,
            dueDate = date,
            userId = userId,
            smsId = message.id
        )
        repository.addCreditStatement(statement)
        ReminderManager.scheduleStatementReminders(getApplication(), statement)
    }

    private suspend fun saveCardPayment(message: SmsMessage, ai: ExtractedTransaction) {
        val currentAccounts = repository.getAccounts().first()
        val statements = repository.getCreditStatements().first()
        
        // 1. Mark the statement as PAID in the Credit Tab
        val creditDigits = ai.last4Digits ?: ""
        val matchedStatement = statements.find { it.cardLast4Digits == creditDigits && !it.isPaid }
        if (matchedStatement != null) {
            repository.updateCreditStatement(matchedStatement.copy(isPaid = true))
            ReminderManager.cancelReminders(getApplication(), matchedStatement.smsId)
        }

        // 2. Deduct from richest Debit account
        val richestAccount = currentAccounts.maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
        if (richestAccount != null) {
            val currentBal = richestAccount.amount.toDoubleOrNull() ?: 0.0
            val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0
            val newBal = currentBal - paymentAmt
            repository.updateAccount(richestAccount.copy(amount = newBal.toString()))

            // 3. Add a Record with category "Credit"
            val record = Record(
                amount = ai.amount,
                category = "Credit",
                type = "Expense",
                accountId = richestAccount.id,
                accountName = richestAccount.name,
                currency = richestAccount.currency,
                userId = userId,
                timestamp = message.timestamp,
                smsId = message.id,
                balanceAfter = newBal.toString(),
                comment = ai.comment // Adding AI generated comment for payments too
            )
            repository.addRecord(record)
        }
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    private fun isBankSms(body: String): Boolean {
        val keywords = listOf("bank", "debited", "credited", "spent", "transaction", "otp", "account", "visa", "mastercard", "purchase", "transfer", "paid", "egp", "statement", "due before", "made to credit card")
        return keywords.any { body.contains(it, ignoreCase = true) }
    }

    private fun inferType(body: String): String {
        if (body.contains("statement", ignoreCase = true) || body.contains("due before", ignoreCase = true)) return "Statement"
        if (body.contains("made to credit card", ignoreCase = true) || (body.contains("transfer", ignoreCase = true) && body.contains("credit card", ignoreCase = true))) return "CardPayment"
        val incomeKeywords = listOf("credited", "received", "deposit", "returned", "salary", "TT Payment", "IPN inward")
        if (incomeKeywords.any { body.contains(it, ignoreCase = true) }) return "Income"
        if (body.contains("+")) return "Income"
        return "Expense"
    }

    private fun extractAmount(body: String): String? {
        val regex = Regex("""(?:EGP|USD|EUR|LE|Amount:?|total)\s*(\d+[\.,]\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(body)
        if (match != null) return match.groupValues[1].replace(",", "")
        return Regex("""(\d+[\.,]\d+)""").find(body)?.value?.replace(",", "")
    }

    private fun extractLast4Digits(body: String): String? {
        Regex("""(?:\*+|-|card|A/c|ending)\s*(\d{3,4})\b""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1] }
        val allFourDigits = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        return allFourDigits.find { it.toIntOrNull() !in 1900..2100 } ?: allFourDigits.firstOrNull()
    }

    fun trackAllBankSms() {
        // Implementation for batch tracking...
    }
}
