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
            var comment: String? = null
            var dueDate: String? = null

            if (isBank && linkedRecord == null && linkedStatement == null) {
                extractedAmt = extractAmount(sms.body)
                digits = extractLast4Digits(sms.body)
                type = inferType(sms.body)
                category = if (type == "Statement") "Credit Card" else inferCategory(sms.body)
                comment = inferComment(sms.body)
                dueDate = extractDueDate(sms.body)
                
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
            } else if (linkedRecord != null) {
                digits = linkedRecord.accountName.takeLast(4)
                category = linkedRecord.category
                type = linkedRecord.type
                comment = linkedRecord.comment
            } else if (linkedStatement != null) {
                digits = linkedStatement.cardLast4Digits
                type = if (linkedStatement.isPaid) "CardPayment" else "Statement"
                category = "Credit Card"
                dueDate = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(linkedStatement.dueDate)
            }

            sms.copy(
                isBankRelated = isBank,
                hasRecordAdded = linkedRecord != null || linkedStatement != null,
                linkedRecord = linkedRecord,
                missingInfoReason = missingReason,
                extractedAmount = extractedAmt,
                last4Digits = digits,
                extractedCategory = category,
                extractedType = type,
                extractedComment = comment,
                extractedDueDate = dueDate
            )
        }
        _smsMessages.value = processedMessages
    }

    fun trackSmsManually(message: SmsMessage) {
        if (_loadingSmsIds.contains(message.id)) return
        
        val amount = message.extractedAmount ?: return
        
        _loadingSmsIds.add(message.id)
        viewModelScope.launch {
            try {
                processManualExtraction(message, amount)
                _toastMessage.value = "Processed successfully!"
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Error manual track", e)
                _toastMessage.value = "Error: ${e.message}"
            } finally {
                _loadingSmsIds.remove(message.id)
            }
        }
    }

    private suspend fun processManualExtraction(message: SmsMessage, amount: String) {
        val manualResult = ExtractedTransaction(
            amount = amount,
            category = message.extractedCategory ?: "Others",
            type = message.extractedType ?: "Expense",
            isBankRelated = true,
            last4Digits = message.last4Digits,
            isStatement = message.extractedType == "Statement",
            dueDate = message.extractedDueDate,
            comment = message.extractedComment ?: ""
        )

        when {
            manualResult.type == "Statement" || manualResult.isStatement -> saveStatement(message, manualResult)
            manualResult.type == "CardPayment" -> saveCardPayment(message, manualResult)
            manualResult.type == "AtmWithdrawal" -> saveAtmWithdrawal(message, manualResult)
            else -> saveRecord(message, manualResult)
        }
    }

    private suspend fun saveAtmWithdrawal(message: SmsMessage, ai: ExtractedTransaction) {
        val currentAccounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        
        val sourceAccount = currentAccounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
        }

        val cashAccount = currentAccounts.find { it.accountType.equals("Cash", ignoreCase = true) }

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
                timestamp = message.timestamp,
                smsId = message.id,
                comment = "ATM Withdrawal",
                balanceAfter = newSourceBal.toString()
            )
            repository.addRecord(record)
        } else {
            saveRecord(message, ai)
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
    }

    private suspend fun saveStatement(message: SmsMessage, ai: ExtractedTransaction) {
        val currentAccounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val matchedAccount = currentAccounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits))
        }

        val date = try {
            ai.dueDate?.let { 
                val format = if (it.contains("/")) "dd/MM/yyyy" else "dd-MM-yyyy"
                SimpleDateFormat(format, Locale.getDefault()).parse(it) 
            } ?: Date()
        } catch (e: Exception) { Date() }

        val statement = CreditStatement(
            cardLast4Digits = ai.last4Digits ?: "0000",
            accountId = matchedAccount?.id ?: "",
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
        
        val creditDigits = ai.last4Digits ?: ""
        val matchedStatement = statements.find { it.cardLast4Digits == creditDigits && !it.isPaid }
        if (matchedStatement != null) {
            repository.updateCreditStatement(matchedStatement.copy(isPaid = true))
            ReminderManager.cancelReminders(getApplication(), matchedStatement.smsId)
        }

        val richestAccount = currentAccounts.filter { !it.accountType.equals("Credit", ignoreCase = true) }
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
                timestamp = message.timestamp,
                smsId = message.id,
                balanceAfter = newBal.toString(),
                comment = ai.comment
            )
            repository.addRecord(record)
        }
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    private fun isBankSms(body: String): Boolean {
        if (isPromotionalSms(body)) return false
        val b = body.lowercase()

        val hasAmount = Regex("""(EGP|USD|EUR|LE)\s*[\d,]+""", RegexOption.IGNORE_CASE).containsMatchIn(b)

        if (!hasAmount) {
            return listOf("salary", "instapay", "ipn inward", "ipn outward").any { b.contains(it) }
        }

        val hasTransactionVerb = listOf(
            "debited", "credited", "spent", "charged", "withdrawn",
            "cashback", "paid to", "payment of", "purchase at"
        ).any { b.contains(it) }

        val hasAccountId = Regex(
            """(?:\*{2,}|card|a/c|ending|acc\.?|account)\s*[-]?\s*\d{3,4}\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(b)

        val hasBalanceInfo = listOf(
            "avail bal", "available balance", "available credit",
            "avbl bal", "balance after", "new balance", "current balance"
        ).any { b.contains(it) }

        val hasStatementSignal = listOf(
            "total amt due", "min. amt due", "statement", "due before", "due date"
        ).any { b.contains(it) }

        val hasTransferSignal = listOf(
            "salary", "instapay", "ipn", "tt payment", "withdrawal", "atm"
        ).any { b.contains(it) }

        return hasTransactionVerb || hasAccountId || hasBalanceInfo
                || hasStatementSignal || hasTransferSignal
    }

    private fun isPromotionalSms(body: String): Boolean {
        val b = body.lowercase()
        val promoSignals = listOf(
            "t&cs apply", "terms & conditions", "terms and conditions",
            "installment plan", "no processing fee", "discounted interest",
            "special offer", "limited time", "enjoy up to",
            "apply now", "click here", "for more info", "to know more",
            "call us at", "visit our branch", "download our app"
        )
        val transactionSignals = listOf(
            "debited", "credited", "your account", "avail bal", "available balance",
            "card ending", "a/c no", "withdrawal", "ref no", "transaction id"
        )
        val hasPromo = promoSignals.any { b.contains(it) }
        val hasTransaction = transactionSignals.any { b.contains(it) }
        return hasPromo && !hasTransaction
    }

    private fun inferType(body: String): String {
        val bodyLower = body.lowercase()
        
        // Priority for Cashback/Income
        if (bodyLower.contains("cashback") && (bodyLower.contains("credited") || bodyLower.contains("earned"))) return "Income"

        // Priority keywords for Statements
        if (bodyLower.contains("total amt due") || 
            bodyLower.contains("min. amt due") || 
            bodyLower.contains("statement date")) {
            return "Statement"
        }

        if (bodyLower.contains("statement") || bodyLower.contains("due before") || bodyLower.contains("due date")) {
            // Refined check: If it has "paid" or "received" but also "amt due" it's likely a statement with a disclaimer
            if (bodyLower.contains("amt due")) return "Statement"
            
            if (bodyLower.contains("paid") || bodyLower.contains("received")) return "CardPayment"
            
            // If the word 'statement' is just part of a footer instruction, don't mark as statement type
            if (bodyLower.contains("check your statement") || bodyLower.contains("log on to")) {
                val incomeKeywords = listOf("credited", "received", "earned")
                if (incomeKeywords.any { bodyLower.contains(it) }) return "Income"
                return "Expense"
            }
            
            return "Statement"
        }
        
        if (bodyLower.contains("made to credit card") || (bodyLower.contains("transfer") && bodyLower.contains("credit card"))) return "CardPayment"
        if (bodyLower.contains("withdrawal")) return "AtmWithdrawal"
        
        val incomeKeywords = listOf("credited", "received", "deposit", "returned", "salary", "TT Payment", "IPN inward", "earned cashback")
        if (incomeKeywords.any { bodyLower.contains(it) }) return "Income"
        if (body.contains("+")) return "Income"
        
        return "Expense"
    }

    private fun inferCategory(body: String): String {
        return when {
            body.contains("cashback", ignoreCase = true) -> "Others" // Or a specific 'Cashback' category if you have one
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
        if (body.contains("cashback", ignoreCase = true)) return "Cashback"
        
        val toNameRegex = Regex("""to\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val fromNameRegex = Regex("""from\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val atMerchantRegex = Regex("""at\s+(.*?)(?:\.|\s+on|\s+Your|$)""", RegexOption.IGNORE_CASE)

        return toNameRegex.find(body)?.groupValues?.get(1)?.trim()
            ?: fromNameRegex.find(body)?.groupValues?.get(1)?.trim()
            ?: atMerchantRegex.find(body)?.groupValues?.get(1)?.trim()
    }

    private fun extractAmount(body: String): String? {
        // More robust pattern for currency and numbers with commas/dots
        val amountPattern = """([\d,]+\.\d{2}|[\d\.]+\,\d{2}|\d+[\.,]\d+|\d+)"""
        
        // Specifically look for "Total Amt Due EGP 8,850.16"
        val totalDueRegex = Regex("""Total Amt Due\s*(?:EGP|USD|EUR|LE)?\s*$amountPattern""", RegexOption.IGNORE_CASE)
        totalDueRegex.find(body)?.let { return it.groupValues[1].replace(",", "") }

        val generalRegex = Regex("""(?:EGP|USD|EUR|LE|Amount:?|total|Due|Cashback of)\s*$amountPattern""", RegexOption.IGNORE_CASE)
        generalRegex.find(body)?.let { return it.groupValues[1].replace(",", "") }
        
        return Regex(amountPattern).find(body)?.value?.replace(",", "")
    }

    private fun extractLast4Digits(body: String): String? {
        // List of prefixes that typically precede account/card numbers.
        val pattern = """(?:\*+|card|A/c|ending|acc\.?|account|visa|mastercard)\s*[-]?\s*(\d{3,4})\b"""
        val matches = Regex(pattern, RegexOption.IGNORE_CASE).findAll(body).toList()
        
        if (matches.isNotEmpty()) {
            val starredMatch = matches.find { it.value.contains("*") }
            if (starredMatch != null) return starredMatch.groupValues[1]
            return matches.last().groupValues[1]
        }
        
        val allFourDigits = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        return allFourDigits.find { it.toIntOrNull() !in 1900..2100 } ?: allFourDigits.firstOrNull()
    }

    private fun extractDueDate(body: String): String? {
        val regex = Regex("""Due Date\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1)
    }

    fun trackAllBankSms() {
        val untrackedBankMessages = _smsMessages.value.filter { it.isBankRelated && !it.hasRecordAdded && it.extractedAmount != null }
        if (untrackedBankMessages.isEmpty()) {
            _toastMessage.value = "No untracked bank messages with detected amounts found."
            return
        }

        _isBatchProcessing.value = true
        _batchTotal.intValue = untrackedBankMessages.size
        _batchCurrent.intValue = 0

        viewModelScope.launch {
            var addedCount = 0
            untrackedBankMessages.forEach { message ->
                try {
                    processManualExtraction(message, message.extractedAmount!!)
                    addedCount++
                } catch (e: Exception) {
                    Log.e("SmsViewModel", "Error batch processing message ${message.id}", e)
                } finally {
                    _batchCurrent.intValue++
                }
            }
            _isBatchProcessing.value = false
            _toastMessage.value = "Successfully tracked $addedCount messages!"
        }
    }
}
