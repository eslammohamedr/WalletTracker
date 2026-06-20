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
import com.example.wallettrackers.model.CategoryRule
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SmsMessage
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.service.AiService
import com.example.wallettrackers.service.ExtractedTransaction
import com.example.wallettrackers.util.ReminderManager
import com.example.wallettrackers.util.SmsParser
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private val _geminiDebugMap = mutableStateOf<Map<String, String>>(emptyMap())
    val geminiDebugMap: State<Map<String, String>> = _geminiDebugMap

    private val _geminiDebugLoadingIds = mutableStateListOf<String>()
    val geminiDebugLoadingIds: List<String> = _geminiDebugLoadingIds

    private val _isRefreshing = mutableStateOf(false)
    val isRefreshing: State<Boolean> = _isRefreshing

    private val smsPrefs by lazy {
        getApplication<Application>().getSharedPreferences("sms_prefs", android.content.Context.MODE_PRIVATE)
    }

    private val _ignoredSenders = mutableStateOf(emptySet<String>())
    val ignoredSenders: State<Set<String>> = _ignoredSenders

    private val _categoryRules = mutableStateOf<List<CategoryRule>>(emptyList())
    val categoryRules: State<List<CategoryRule>> = _categoryRules

    private val repository = FirebaseRepository(userId)
    
    private val aiService = AiService(
        groqApiKey     = com.example.wallettrackers.BuildConfig.GROQ_API_KEY,
        cerebrasApiKey = com.example.wallettrackers.BuildConfig.CEREBRAS_API_KEY,
        geminiApiKey   = com.example.wallettrackers.BuildConfig.GEMINI_API_KEY
    )

    private var observeJob: Job? = null

    init {
        Log.d("SmsVM", "init START: userId='$userId'")
        _ignoredSenders.value = smsPrefs.getStringSet("ignored_senders", emptySet()) ?: emptySet()
        Log.d("SmsVM", "init: ${_ignoredSenders.value.size} ignored senders loaded")
        observeRules()
        observeData()
        Log.d("SmsVM", "init END")
    }

    private fun observeRules() {
        Log.d("SmsVM", "observeRules: subscribing to category rules")
        viewModelScope.launch {
            repository.getCategoryRules().collect { rules ->
                Log.d("SmsVM", "observeRules: received ${rules.size} rules")
                _categoryRules.value = rules
            }
        }
    }

    fun fetchSms() {
        Log.d("SmsVM", "fetchSms: triggering data refresh")
        observeData()
    }

    fun refresh() {
        Log.d("SmsVM", "refresh START")
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(1200)
            observeData()
            _isRefreshing.value = false
            Log.d("SmsVM", "refresh END")
        }
    }

    private fun observeData() {
        Log.d("SmsVM", "observeData: cancelling old job, starting new observation")
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                repository.getAccounts(),
                repository.getRecords(),
                repository.getCreditStatements()
            ) { accounts, records, statements ->
                Triple(accounts, records, statements)
            }.collect { (accounts, records, statements) ->
                Log.d("SmsVM", "observeData: received ${accounts.size} accounts, ${records.size} records, ${statements.size} statements")
                _accounts.value = accounts
                val rawSms = fetchRawSmsFromInbox()
                Log.d("SmsVM", "observeData: fetched ${rawSms.size} raw SMS from inbox")
                // Detect each credit card's true home currency from its SMS balance line
                // and auto-correct the account if the user stored the wrong currency (e.g. "Dollar").
                autoFixCreditCardCurrencies(accounts, rawSms)
                matchSmsWithData(rawSms, accounts, records, statements)
            }
        }
    }

    /**
     * For every credit card account, scan the SMS inbox for a message belonging to that card
     * that contains an "available limit is EGP/USD/EUR …" line. If the SMS balance currency
     * differs from what is stored in Firestore, update the account — once fixed, no further
     * writes happen (the condition becomes false on every subsequent load).
     */
    private suspend fun autoFixCreditCardCurrencies(accounts: List<Account>, rawSms: List<SmsMessage>) {
        val creditAccounts = accounts.filter { it.accountType.contains("Credit", ignoreCase = true) }
        Log.d("SmsVM", "autoFixCreditCardCurrencies: checking ${creditAccounts.size} credit accounts")
        creditAccounts.forEach { account ->
                val digits = account.last4Digits.filter { it.isDigit() }
                if (digits.isEmpty()) return@forEach
                val smsCurrency = rawSms
                    .firstOrNull { sms -> sms.body.contains(digits) && extractBalanceCurrencyFromSms(sms.body) != null }
                    ?.let { extractBalanceCurrencyFromSms(it.body) }
                    ?: return@forEach
                if (normaliseCurrency(account.currency) != smsCurrency) {
                    Log.d("SmsVM", "autoFixCreditCardCurrencies: fixing '${account.name}' currency: ${account.currency} → $smsCurrency")
                    repository.updateAccount(account.copy(currency = smsCurrency))
                }
            }
    }

    private fun fetchRawSmsFromInbox(): List<SmsMessage> {
        Log.d("SmsVM", "fetchRawSmsFromInbox START")
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
        // Collect records whose smsId was stored as timestampMillis (by SmsReceiver) so we can
        // upgrade them to the content-provider _ID after the loop.
        val smsIdUpgrades = mutableListOf<Pair<Record, String>>()

        val processedMessages = rawSms.filter { it.sender !in _ignoredSenders.value }.map { sms ->
            val isBank = isBankSms(sms.body, sms.sender)

            // Primary match: content-provider _ID stored by SmsViewModel manual tracking.
            // Fallback match: SmsReceiver stores smsId as timestampMillis.toString(), which differs
            // from the content-provider _ID. Match by timestamp proximity (should be exactly 0 diff).
            var linkedRecord = currentRecords.find { it.smsId == sms.id }
            if (linkedRecord == null) {
                val ts = sms.timestamp.time
                currentRecords.find { record ->
                    val recTs = record.smsId?.toLongOrNull()
                    recTs != null && kotlin.math.abs(recTs - ts) < 2000L
                }?.also { match ->
                    linkedRecord = match
                    smsIdUpgrades.add(match to sms.id)
                }
            }

            var linkedStatement = currentStatements.find { it.smsId == sms.id }
            if (linkedStatement == null) {
                val ts = sms.timestamp.time
                currentStatements.find { stmt ->
                    val stmtTs = stmt.smsId?.toLongOrNull()
                    stmtTs != null && kotlin.math.abs(stmtTs - ts) < 2000L
                }?.also { match ->
                    linkedStatement = match
                }
            }
            
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
                linkedStatement = linkedStatement,
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

        // Upgrade timestamp-based smsIds to content-provider _IDs so future lookups hit the fast path.
        if (smsIdUpgrades.isNotEmpty()) {
            viewModelScope.launch {
                smsIdUpgrades.forEach { (record, correctId) ->
                    repository.updateRecord(record.copy(smsId = correctId))
                }
            }
        }
    }

    fun trackSmsManually(message: SmsMessage) {
        Log.d("SmsVM", "trackSmsManually START: smsId=${message.id} sender=${message.sender}")
        if (_loadingSmsIds.contains(message.id)) { Log.d("SmsVM", "trackSmsManually: already loading, skipping"); return }

        val amount = message.extractedAmount ?: run { Log.d("SmsVM", "trackSmsManually: no amount extracted, skipping"); return }
        Log.d("SmsVM", "trackSmsManually: amount=$amount, starting processing")

        _loadingSmsIds.add(message.id)
        viewModelScope.launch {
            try {
                processManualExtraction(message, amount)
                Log.d("SmsVM", "trackSmsManually END: success")
                _toastMessage.value = "Processed successfully!"
            } catch (e: Exception) {
                Log.e("SmsViewModel", "Error manual track", e)
                _toastMessage.value = "Error: ${e.message}"
            } finally {
                _loadingSmsIds.remove(message.id)
            }
        }
    }

    fun saveRuleAndResync(merchant: String, category: String) {
        Log.d("SmsVM", "saveRuleAndResync START: merchant='$merchant' → category='$category'")
        viewModelScope.launch {
            val rule = CategoryRule(merchantKeyword = merchant, category = category, userId = userId)
            repository.addCategoryRule(rule)

            // Update all existing records whose comment contains this merchant
            val allRecords = repository.getRecords().first()
            var updated = 0
            allRecords.forEach { record ->
                val commentMatches = record.comment?.contains(merchant, ignoreCase = true) == true
                if (commentMatches && record.category != category) {
                    repository.updateRecord(record.copy(category = category))
                    updated++
                }
            }
            Log.d("SmsVM", "saveRuleAndResync END: updated $updated existing records")
            _toastMessage.value = if (updated > 0)
                "Rule saved — updated $updated existing record${if (updated > 1) "s" else ""}"
            else
                "Rule saved for \"$merchant\""
        }
    }

    fun deleteRule(ruleId: String) {
        Log.d("SmsVM", "deleteRule: ruleId=$ruleId")
        viewModelScope.launch {
            repository.deleteCategoryRule(ruleId)
            _toastMessage.value = "Rule deleted"
        }
    }

    private suspend fun processManualExtraction(message: SmsMessage, amount: String) {
        Log.d("SmsVM", "processManualExtraction START: smsId=${message.id} amount=$amount body='${message.body.take(60)}...'")
        val type = message.extractedType ?: "Expense"
        Log.d("SmsVM", "processManualExtraction: type=$type")

        // User-defined rules take priority over AI classification
        val ruleCategory = _categoryRules.value.firstOrNull { rule ->
            message.body.contains(rule.merchantKeyword, ignoreCase = true)
        }?.category
        if (ruleCategory != null) Log.d("SmsVM", "processManualExtraction: matched rule ��� category='$ruleCategory'")

        // Detection priority: saved rules → AI → keyword (keyword only when AI is unsure)
        val category = when {
            type == "Statement" -> "Credit Card"
            ruleCategory != null -> ruleCategory
            type == "Income" || type == "Expense" -> {
                val ai = aiService.inferCategory(message.body)
                if (!ai.isNullOrBlank() && !ai.equals("Others", ignoreCase = true)) ai
                else inferCategory(message.body)
            }
            else -> message.extractedCategory ?: "Others"
        }
        Log.d("SmsVM", "processManualExtraction: finalCategory='$category'")

        val manualResult = ExtractedTransaction(
            amount = amount,
            category = category,
            type = type,
            isBankRelated = true,
            last4Digits = message.last4Digits,
            isStatement = type == "Statement",
            dueDate = message.extractedDueDate,
            comment = message.extractedComment ?: ""
        )

        Log.d("SmsVM", "processManualExtraction: routing to handler for type='${manualResult.type}' isStatement=${manualResult.isStatement}")
        when {
            manualResult.type == "Statement" || manualResult.isStatement -> saveStatement(message, manualResult)
            manualResult.type == "CardPayment" -> saveCardPayment(message, manualResult)
            manualResult.type == "CreditCardReceived" -> saveCreditCardReceivedManual(message, manualResult)
            manualResult.type == "AtmWithdrawal" -> saveAtmWithdrawal(message, manualResult)
            else -> saveRecord(message, manualResult)
        }
        Log.d("SmsVM", "processManualExtraction END: done")
    }

    private suspend fun saveAtmWithdrawal(message: SmsMessage, ai: ExtractedTransaction) {
        Log.d("SmsVM", "saveAtmWithdrawal START: amount=${ai.amount} digits=${ai.last4Digits}")
        val currentAccounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""

        val sourceAccount = currentAccounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits))
        }

        val cashAccount = currentAccounts.find { it.accountType.equals("Cash", ignoreCase = true) }
        val amount = ai.amount.toDoubleOrNull() ?: 0.0

        // Deduct from source bank account if identified
        var newSourceBal = 0.0
        if (sourceAccount != null) {
            val sourceBal = sourceAccount.amount.toDoubleOrNull() ?: 0.0
            newSourceBal = sourceBal - amount
            repository.updateAccount(sourceAccount.copy(amount = newSourceBal.toString()))
        }

        // Always credit cash — ATM withdrawal always puts money in the user's pocket
        if (cashAccount != null) {
            val cashBal = cashAccount.amount.toDoubleOrNull() ?: 0.0
            val newCashBal = cashBal + amount
            repository.updateAccount(cashAccount.copy(amount = newCashBal.toString()))
        }

        val sourceName = sourceAccount?.name ?: "Bank"
        val record = Record(
            amount = ai.amount,
            category = "Transfer",
            type = "Expense",
            accountId = sourceAccount?.id ?: "",
            accountName = if (cashAccount != null) "$sourceName -> Cash" else sourceName,
            currency = sourceAccount?.currency ?: "EGP",
            userId = userId,
            timestamp = message.timestamp,
            smsId = message.id,
            comment = "ATM Withdrawal",
            balanceAfter = if (sourceAccount != null) newSourceBal.toString() else ""
        )
        repository.addRecord(record)
    }

    private suspend fun saveRecord(message: SmsMessage, ai: ExtractedTransaction) {
        Log.d("SmsVM", "saveRecord START: type=${ai.type} amount=${ai.amount} category=${ai.category} digits=${ai.last4Digits}")
        // Guard: if a complete CC payment transfer already exists for this amount (created by the
        // credit-side SMS pairing logic), skip to avoid producing a duplicate expense record.
        if (ai.type == "Expense") {
            val existingTransfer = repository.findRecentCardPaymentRecord(ai.amount)
            if (existingTransfer != null && existingTransfer.accountName.contains("->")) {
                Log.d("SmsViewModel", "Skipping duplicate — CC transfer already recorded for ${ai.amount}")
                return
            }
        }

        val currentAccounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""

        var targetAccount = currentAccounts.find { acc ->
            val accDigits = acc.last4Digits.filter { it.isDigit() }
            accDigits.isNotEmpty() && digits.isNotEmpty() && (
                accDigits == digits || digits.endsWith(accDigits) || accDigits.endsWith(digits)
            )
        }

        if (targetAccount == null && ai.category == "Salary") {
            targetAccount = currentAccounts.maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
        }

        val txCurrency = inferCurrency(message.body)
        val isCreditCard = targetAccount?.accountType?.contains("Credit", ignoreCase = true) == true

        // HSBC always prints "available limit is EGP X" for EGP cards even for foreign charges.
        // Use this to detect the card's TRUE home currency and auto-correct the account if the user
        // stored it as "Dollar" / "Euro" when it is actually an EGP card.
        val smsBalanceCurrency = extractBalanceCurrencyFromSms(message.body)
        if (isCreditCard && targetAccount != null && smsBalanceCurrency != null &&
            normaliseCurrency(targetAccount.currency) != smsBalanceCurrency) {
            targetAccount = targetAccount.copy(currency = smsBalanceCurrency)
        }

        val cardCurrency = normaliseCurrency(targetAccount?.currency ?: "EGP")
        // For credit cards, arithmetic balance update is only valid when tx currency == card currency.
        // A USD/EUR/SAR charge on an EGP card cannot be deducted by raw arithmetic.
        val canUpdateBalance = !isCreditCard || txCurrency == cardCurrency

        val isIncome = ai.type == "Income"
        val amountDouble = ai.amount.toDoubleOrNull() ?: 0.0
        val previousBal = targetAccount?.amount?.toDoubleOrNull() ?: 0.0
        val smsBalance = extractBalanceFromSms(message.body)

        // EGP equivalent for foreign-currency charges on EGP cards.
        // HSBC always prints "available limit is EGP X" — the balance drop is the EGP equivalent.
        val egpEquivalent: Double? = if (!canUpdateBalance && smsBalance != null) {
            val impact = if (isIncome) smsBalance - previousBal else previousBal - smsBalance
            if (impact > 0.01) impact else null  // guard against zero/stale previousBal
        } else null

        var recordAmount = ai.amount
        var recordCurrency = txCurrency
        var balanceAfter = ""
        var conversionNote: String? = null

        if (targetAccount != null) {
            when {
                // Prefer explicit SMS balance over arithmetic for all same-currency accounts
                canUpdateBalance && smsBalance != null -> {
                    repository.updateAccount(targetAccount.copy(amount = smsBalance.toString()))
                    balanceAfter = smsBalance.toString()
                }
                canUpdateBalance -> {
                    val newBal = if (isIncome) previousBal + amountDouble else previousBal - amountDouble
                    repository.updateAccount(targetAccount.copy(amount = newBal.toString()))
                    balanceAfter = newBal.toString()
                }
                egpEquivalent != null -> {
                    // Foreign CC charge: update EGP available limit + record in EGP
                    repository.updateAccount(targetAccount.copy(amount = smsBalance.toString()))
                    balanceAfter = smsBalance.toString()
                    recordAmount = "%.2f".format(egpEquivalent)
                    recordCurrency = cardCurrency  // EGP
                    conversionNote = "Charged $txCurrency ${ai.amount}"
                }
                smsBalance != null -> {
                    // Foreign CC charge, but previousBal was 0/stale — still update EGP limit,
                    // but keep original foreign amount since we can't compute a reliable EGP equivalent
                    repository.updateAccount(targetAccount.copy(amount = smsBalance.toString()))
                    balanceAfter = smsBalance.toString()
                }
                // else: foreign currency, no EGP balance info — record as-is, don't touch account
            }
        }

        val finalComment = listOfNotNull(conversionNote, ai.comment.ifEmpty { null }).joinToString(" | ")
        val record = Record(
            amount = recordAmount, category = ai.category, type = ai.type,
            accountId = targetAccount?.id ?: "",
            accountName = targetAccount?.name ?: "Imported Card (${ai.last4Digits})",
            currency = recordCurrency,
            userId = userId, timestamp = message.timestamp, smsId = message.id,
            comment = finalComment, balanceAfter = balanceAfter
        )
        repository.addRecord(record)
    }

    private suspend fun saveStatement(message: SmsMessage, ai: ExtractedTransaction) {
        Log.d("SmsVM", "saveStatement START: amount=${ai.amount} digits=${ai.last4Digits} dueDate=${ai.dueDate}")
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
        Log.d("SmsVM", "saveStatement END: saved statement for card=${statement.cardLast4Digits} amount=${statement.totalAmount}")
    }

    private suspend fun saveCardPayment(message: SmsMessage, ai: ExtractedTransaction) {
        Log.d("SmsVM", "saveCardPayment START: amount=${ai.amount} digits=${ai.last4Digits}")
        val currentAccounts = repository.getAccounts().first()
        val statements = repository.getCreditStatements().first()
        
        val creditDigits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val creditAccount = currentAccounts.find { acc ->
            val ad = acc.last4Digits.filter { it.isDigit() }
            ad.isNotEmpty() && creditDigits.isNotEmpty() && (ad == creditDigits || creditDigits.endsWith(ad) || ad.endsWith(creditDigits))
        }
        val matchedStatement = statements.find { statement ->
            if (statement.isPaid) return@find false
            if (creditAccount != null && statement.accountId.isNotEmpty()) return@find statement.accountId == creditAccount.id
            val sd = statement.cardLast4Digits.filter { it.isDigit() }
            sd.isNotEmpty() && creditDigits.isNotEmpty() && (sd == creditDigits || creditDigits.endsWith(sd) || sd.endsWith(creditDigits))
        }
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

    private suspend fun saveCreditCardReceivedManual(message: SmsMessage, ai: ExtractedTransaction) {
        Log.d("SmsVM", "saveCreditCardReceivedManual START: amount=${ai.amount} digits=${ai.last4Digits}")
        val currentAccounts = repository.getAccounts().first()
        val statements = repository.getCreditStatements().first()
        val creditDigits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0

        val creditAccount = currentAccounts.find { acc ->
            val ad = acc.last4Digits.filter { it.isDigit() }
            ad.isNotEmpty() && creditDigits.isNotEmpty() && (ad == creditDigits || creditDigits.endsWith(ad) || ad.endsWith(creditDigits))
        }
        val ccName = creditAccount?.name ?: "Credit Card ****$creditDigits"

        val unpaid = statements.find { statement ->
            if (statement.isPaid) return@find false
            if (creditAccount != null && statement.accountId.isNotEmpty()) return@find statement.accountId == creditAccount.id
            val sd = statement.cardLast4Digits.filter { it.isDigit() }
            sd.isNotEmpty() && creditDigits.isNotEmpty() && (sd == creditDigits || creditDigits.endsWith(sd) || sd.endsWith(creditDigits))
        }
        if (unpaid != null) {
            repository.updateCreditStatement(unpaid.copy(isPaid = true))
            ReminderManager.cancelReminders(getApplication(), unpaid.smsId)
        }

        // Find a same-day debit SMS matching this payment amount (fuzzy: within 100 EGP or 5%)
        val msgCal = java.util.Calendar.getInstance().apply { time = message.timestamp }
        val matchingDebitSms = _smsMessages.value.find { other ->
            if (other.id == message.id) return@find false
            val otherCal = java.util.Calendar.getInstance().apply { time = other.timestamp }
            val sameDay = otherCal.get(java.util.Calendar.YEAR) == msgCal.get(java.util.Calendar.YEAR) &&
                    otherCal.get(java.util.Calendar.DAY_OF_YEAR) == msgCal.get(java.util.Calendar.DAY_OF_YEAR)
            val otherAmt = other.extractedAmount?.toDoubleOrNull() ?: 0.0
            val diff = kotlin.math.abs(otherAmt - paymentAmt)
            val sameAmt = otherAmt > 0 && (diff <= 100.0 || diff / maxOf(otherAmt, paymentAmt) <= 0.05)
            val isDebit = other.extractedType?.let { it == "Expense" || it == "CardPayment" } ?: false
            sameDay && sameAmt && isDebit
        }

        when {
            // Case 1: debit SMS was already tracked as a record.
            // The debit account was already deducted — just restore CC balance and re-label the record.
            matchingDebitSms?.hasRecordAdded == true && matchingDebitSms.linkedRecord != null -> {
                val existing = matchingDebitSms.linkedRecord!!
                // Guard: already a completed transfer — avoid double-linking on repeated Track All runs.
                if (existing.accountName.contains("->") || existing.category == "Credit Payment") {
                    _toastMessage.value = "Credit payment already linked"
                    return
                }
                if (creditAccount != null) {
                    repository.updateAccount(creditAccount.copy(
                        amount = ((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt).toString()
                    ))
                }
                repository.updateRecord(existing.copy(
                    category = "Credit Payment",
                    accountName = "${existing.accountName} -> $ccName"
                ))
                _toastMessage.value = "Linked: ${existing.accountName} → $ccName"
            }

            // Case 2: debit SMS visible but UI list says not yet tracked.
            // Try smsId lookup first (no time window — works for historical SMS), then fall back to
            // the time-windowed amount search for real-time SMS processed out of order.
            matchingDebitSms != null && matchingDebitSms.hasRecordAdded == false -> {
                val recentDebit = repository.findRecordBySmsId(matchingDebitSms.id)
                    ?.takeIf { !it.accountName.contains("->") && it.category != "Credit Payment" }
                    ?: repository.findRecentDebitExpenseRecord(ai.amount)
                if (recentDebit != null && !recentDebit.accountName.contains("->")) {
                    // Debit was just saved in this batch — upgrade it to a transfer
                    if (creditAccount != null) {
                        repository.updateAccount(creditAccount.copy(
                            amount = ((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt).toString()
                        ))
                    }
                    repository.updateRecord(recentDebit.copy(
                        category = "Credit Payment",
                        accountName = "${recentDebit.accountName} -> $ccName"
                    ))
                    _toastMessage.value = "Linked: ${recentDebit.accountName} → $ccName"
                } else {
                    // Debit truly not processed yet — handle both sides now
                    val debitDigits = matchingDebitSms.last4Digits?.filter { it.isDigit() } ?: ""
                    val sourceAccount = currentAccounts.find { acc ->
                        val ad = acc.last4Digits.filter { it.isDigit() }
                        ad.isNotEmpty() && debitDigits.isNotEmpty() && (ad == debitDigits || debitDigits.endsWith(ad) || ad.endsWith(debitDigits))
                    }
                    if (sourceAccount != null) {
                        val debitAmt = matchingDebitSms.extractedAmount?.toDoubleOrNull() ?: paymentAmt
                        val newSourceBal = extractBalanceFromSms(matchingDebitSms.body)
                            ?: ((sourceAccount.amount.toDoubleOrNull() ?: 0.0) - debitAmt)
                        repository.updateAccount(sourceAccount.copy(amount = newSourceBal.toString()))
                        if (creditAccount != null) {
                            repository.updateAccount(creditAccount.copy(
                                amount = ((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt).toString()
                            ))
                        }
                        // Use debit smsId so the HSBC SMS shows as tracked in the list
                        repository.addRecord(Record(
                            amount = ai.amount, category = "Credit Payment", type = "Expense",
                            accountId = sourceAccount.id,
                            accountName = "${sourceAccount.name} -> $ccName",
                            currency = sourceAccount.currency, userId = userId,
                            timestamp = matchingDebitSms.timestamp,
                            smsId = matchingDebitSms.id,
                            balanceAfter = newSourceBal.toString(), comment = ai.comment
                        ))
                        _toastMessage.value = "Credit payment: ${sourceAccount.name} → $ccName"
                    } else {
                        createPartialCreditRecord(message, ai, creditAccount, creditDigits, paymentAmt, ccName)
                    }
                }
            }

            // Case 3: no debit SMS visible in the list.
            // Check Firestore for a recently-saved debit Expense (e.g. Instapay tracked earlier).
            else -> {
                val existingDebit = repository.findRecentDebitExpenseRecord(ai.amount)
                if (existingDebit != null && !existingDebit.accountName.contains("->")) {
                    // Debit was already tracked as a plain expense — upgrade to transfer + restore CC balance
                    if (creditAccount != null) {
                        val calculated = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt
                        repository.updateAccount(creditAccount.copy(amount = calculated.toString()))
                    }
                    repository.updateRecord(existingDebit.copy(
                        category = "Credit Payment",
                        accountName = "${existingDebit.accountName} -> $ccName",
                        smsId = message.id
                    ))
                    _toastMessage.value = "Linked: ${existingDebit.accountName} → $ccName"
                } else {
                    // Debit SMS not yet received — create partial record + pending flag
                    if (creditAccount != null) {
                        repository.updateAccount(creditAccount.copy(
                            amount = ((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt).toString()
                        ))
                    }
                    getApplication<android.app.Application>().applicationContext
                        .getSharedPreferences("pending_cc", android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString("cc_pending_${ai.amount}", "${message.id}|$creditDigits|${System.currentTimeMillis()}")
                        .apply()
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = creditAccount?.id ?: "",
                        accountName = ccName,
                        currency = creditAccount?.currency ?: "EGP", userId = userId,
                        timestamp = message.timestamp, smsId = message.id,
                        balanceAfter = "", comment = ai.comment
                    ))
                    _toastMessage.value = "Credit payment recorded — pending debit bank SMS"
                }
            }
        }
    }

    private suspend fun createPartialCreditRecord(
        message: SmsMessage, ai: ExtractedTransaction,
        creditAccount: com.example.wallettrackers.model.Account?, creditDigits: String,
        paymentAmt: Double, ccName: String
    ) {
        if (creditAccount != null) {
            repository.updateAccount(creditAccount.copy(
                amount = ((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt).toString()
            ))
        }
        repository.addRecord(Record(
            amount = ai.amount, category = "Credit Payment", type = "Expense",
            accountId = creditAccount?.id ?: "", accountName = ccName,
            currency = creditAccount?.currency ?: "EGP", userId = userId,
            timestamp = message.timestamp, smsId = message.id,
            balanceAfter = "", comment = ai.comment
        ))
        _toastMessage.value = "Credit payment recorded for card ****$creditDigits"
    }

    fun fetchGeminiDebug(message: SmsMessage) {
        if (_geminiDebugLoadingIds.contains(message.id)) return
        _geminiDebugLoadingIds.add(message.id)
        viewModelScope.launch {
            try {
                val result = aiService.getDebugAnalysis(message.body)
                _geminiDebugMap.value = _geminiDebugMap.value + (message.id to result)
            } catch (e: Exception) {
                _geminiDebugMap.value = _geminiDebugMap.value + (message.id to "ERROR: ${e.message}")
            } finally {
                _geminiDebugLoadingIds.remove(message.id)
            }
        }
    }

    fun exportSmsAsText(): String {
        val bankSms = _smsMessages.value.filter { it.isBankRelated }
        val df = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        return buildString {
            appendLine("=== WALLET TRACKERS — BANK SMS EXPORT ===")
            appendLine("Total messages: ${bankSms.size}")
            appendLine("Exported at: ${df.format(Date())}")
            appendLine()
            bankSms.forEachIndexed { index, sms ->
                appendLine("──────────────────────────────────────")
                appendLine("SMS #${index + 1}")
                appendLine("Sender  : ${sms.sender}")
                appendLine("Date    : ${df.format(sms.timestamp)}")
                appendLine("Status  : ${if (sms.hasRecordAdded) "✓ Tracked" else "✗ Untracked"}")
                appendLine("Body    :")
                appendLine(sms.body.trim())
                appendLine("--- App extracted ---")
                val type     = sms.extractedType     ?: sms.linkedRecord?.type     ?: "?"
                val category = sms.extractedCategory ?: sms.linkedRecord?.category ?: "?"
                val amount   = sms.extractedAmount   ?: sms.linkedRecord?.amount
                    ?: sms.linkedStatement?.totalAmount?.toString() ?: "?"
                val comment  = sms.extractedComment  ?: sms.linkedRecord?.comment  ?: ""
                val digits   = sms.last4Digits        ?: sms.linkedRecord?.accountName ?: "?"
                appendLine("Type    : $type")
                appendLine("Category: $category")
                appendLine("Amount  : $amount")
                appendLine("Digits  : $digits")
                if (comment.isNotBlank()) appendLine("Comment : $comment")
                if (!sms.hasRecordAdded && sms.missingInfoReason != null)
                    appendLine("Issue   : ${sms.missingInfoReason}")
                appendLine()
            }
        }
    }

    fun ignoreSender(sender: String) {
        Log.d("SmsVM", "ignoreSender: adding '$sender' to ignored list")
        val updated = _ignoredSenders.value + sender
        _ignoredSenders.value = updated
        smsPrefs.edit().putStringSet("ignored_senders", updated).apply()
        _smsMessages.value = _smsMessages.value.filter { it.sender != sender }
        _toastMessage.value = "Sender \"$sender\" ignored"
    }

    fun unignoreSender(sender: String) {
        Log.d("SmsVM", "unignoreSender: removing '$sender' from ignored list")
        val updated = _ignoredSenders.value - sender
        _ignoredSenders.value = updated
        smsPrefs.edit().putStringSet("ignored_senders", updated).apply()
        refresh()
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    private fun inferCurrency(body: String): String {
        val b = body.uppercase()
        return when {
            b.contains("USD") || b.contains("\$") -> "USD"
            b.contains("EUR") || b.contains("€")  -> "EUR"
            b.contains("GBP") || b.contains("£")  -> "GBP"
            b.contains("SAR") || b.contains("﷼")  -> "SAR"
            b.contains("AED")                      -> "AED"
            else -> "EGP"
        }
    }

    private fun extractBalanceCurrencyFromSms(body: String): String? =
        Regex(
            """avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)\s*(?:[:\-]|is)?\s*(EGP|USD|EUR|GBP|SAR|AED)""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)?.uppercase()

    private fun extractBalanceFromSms(body: String): Double? {
        val num = """([\d,]+(?:\.\d{1,2})?)"""
        val cur = """(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)?\s*"""
        Regex("""(?:avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)|avbl\.?\s*bal|new\s*bal(?:ance)?|current\s*bal(?:ance)?|bal(?:ance)?\s*after|a/c\s*bal|remaining\s*bal(?:ance)?)\s*(?:[:\-]|is)?\s*$cur$num""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        Regex("""(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)\s*$num\s+(?:is\s+)?(?:your\s+)?avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        return null
    }

    private fun normaliseCurrency(currency: String): String = when {
        currency.contains("Dollar", ignoreCase = true) || currency.equals("USD", ignoreCase = true) -> "USD"
        currency.contains("Euro",   ignoreCase = true) || currency.equals("EUR", ignoreCase = true) -> "EUR"
        currency.contains("GBP",    ignoreCase = true) || currency.contains("Pound", ignoreCase = true) -> "GBP"
        currency.equals("SAR",      ignoreCase = true) -> "SAR"
        currency.equals("AED",      ignoreCase = true) -> "AED"
        else -> "EGP"
    }

    private fun isDeclinedTransaction(body: String) = SmsParser.isDeclinedTransaction(body)
    private fun isBankSms(body: String, sender: String = "") = SmsParser.isBankSms(body, sender)
    private fun isPromotionalSms(body: String) = SmsParser.isPromotionalSms(body)

    private fun inferType(body: String) = SmsParser.inferType(body)

    private fun inferCategory(body: String) = SmsParser.inferCategory(body, _categoryRules.value)

    private fun inferComment(body: String) = SmsParser.inferComment(body)

    private fun extractAmount(body: String): String? {
        // More robust pattern for currency and numbers with commas/dots
        val amountPattern = """([\d,]+\.\d{2}|[\d\.]+\,\d{2}|\d+[\.,]\d+|\d+)"""
        
        // Specifically look for "Total Amt Due EGP 8,850.16"
        val totalDueRegex = Regex("""Total Amt Due\s*(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)?\s*$amountPattern""", RegexOption.IGNORE_CASE)
        totalDueRegex.find(body)?.let { return it.groupValues[1].replace(",", "") }

        val generalRegex = Regex("""(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼|Amount:?|total|Due|Cashback of)\s*$amountPattern""", RegexOption.IGNORE_CASE)
        generalRegex.find(body)?.let { return it.groupValues[1].replace(",", "") }

        if (Regex("""(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)""", RegexOption.IGNORE_CASE).containsMatchIn(body))
            return Regex(amountPattern).find(body)?.value?.replace(",", "")
        
        return Regex(amountPattern).find(body)?.value?.replace(",", "")
    }

    private fun extractLast4Digits(body: String): String? {
        // Highest priority: explicit credit-card number in transfer/payment messages.
        // "Transfer from 074-151***-001 ... to your Credit Card ending with 2601"
        // "Your Credit Card ending with *** 2601 has been used"
        val ccEndingStarred = Regex("""Credit Card ending with\s+\*+\s*(\d{4})\b""", RegexOption.IGNORE_CASE).find(body)
        if (ccEndingStarred != null) return ccEndingStarred.groupValues[1]
        val ccEndingPlain = Regex("""Credit Card ending with\s+(\d{4})\b""", RegexOption.IGNORE_CASE).find(body)
        if (ccEndingPlain != null) return ccEndingPlain.groupValues[1]

        // General patterns: prefixes that precede account/card numbers
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

    fun retrackAllSms() {
        Log.d("SmsVM", "retrackAllSms START")
        val trackedMessages = _smsMessages.value.filter { it.hasRecordAdded && it.linkedRecord != null }
        if (trackedMessages.isEmpty()) {
            Log.d("SmsVM", "retrackAllSms: no tracked messages found")
            _toastMessage.value = "No tracked messages found."
            return
        }
        Log.d("SmsVM", "retrackAllSms: processing ${trackedMessages.size} tracked messages")

        _isBatchProcessing.value = true
        _batchTotal.intValue = trackedMessages.size
        _batchCurrent.intValue = 0

        viewModelScope.launch {
            var count = 0
            val currentAccounts = repository.getAccounts().first()

            trackedMessages.forEach { message ->
                try {
                    val linked = message.linkedRecord!!
                    when (linked.type) {
                        "Income", "Expense" -> {
                            // Reverse the balance impact on the account
                            val account = currentAccounts.find { it.id == linked.accountId }
                            if (account != null) {
                                val bal = account.amount.toDoubleOrNull() ?: 0.0
                                val amt = linked.amount.toDoubleOrNull() ?: 0.0
                                val reversed = if (linked.type == "Income") bal - amt else bal + amt
                                repository.updateAccount(account.copy(amount = reversed.toString()))
                            }
                            repository.deleteRecord(linked.id)
                            // Re-extract everything fresh from the SMS body
                            val freshAmount = extractAmount(message.body) ?: linked.amount
                            val freshMessage = message.copy(
                                extractedAmount = freshAmount,
                                extractedType = inferType(message.body),
                                extractedCategory = inferCategory(message.body),
                                extractedComment = inferComment(message.body),
                                last4Digits = extractLast4Digits(message.body),
                                hasRecordAdded = false,
                                linkedRecord = null
                            )
                            processManualExtraction(freshMessage, freshAmount)
                            count++
                        }
                        else -> {
                            // Statement / CardPayment: just refresh the comment
                            val freshComment = inferComment(message.body)
                            if (freshComment != null && freshComment != linked.comment) {
                                repository.updateRecord(linked.copy(comment = freshComment))
                                count++
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsViewModel", "Error retracking ${message.id}", e)
                } finally {
                    _batchCurrent.intValue++
                }
            }
            _isBatchProcessing.value = false
            Log.d("SmsVM", "retrackAllSms END: re-tracked $count messages")
            _toastMessage.value = "Re-tracked $count messages!"
        }
    }

    fun trackAllBankSms() {
        Log.d("SmsVM", "trackAllBankSms START")
        val untrackedBankMessages = _smsMessages.value.filter { it.isBankRelated && !it.hasRecordAdded && it.extractedAmount != null }
        if (untrackedBankMessages.isEmpty()) {
            Log.d("SmsVM", "trackAllBankSms: no untracked bank messages found")
            _toastMessage.value = "No untracked bank messages with detected amounts found."
            return
        }
        Log.d("SmsVM", "trackAllBankSms: processing ${untrackedBankMessages.size} untracked messages")

        _isBatchProcessing.value = true
        _batchTotal.intValue = untrackedBankMessages.size
        _batchCurrent.intValue = 0

        viewModelScope.launch {
            var addedCount = 0
            // Process debit-side SMS before credit-side (CreditCardReceived) so that when the
            // credit-side SMS is processed, the debit record already exists in Firestore and can
            // be upgraded to a transfer — producing exactly one "Credit Payment" record.
            val ordered = untrackedBankMessages.sortedBy { msg ->
                if (inferType(msg.body) == "CreditCardReceived") 1 else 0
            }
            ordered.forEach { message ->
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
            Log.d("SmsVM", "trackAllBankSms END: tracked $addedCount messages")
            _toastMessage.value = "Successfully tracked $addedCount messages!"
        }
    }
}
