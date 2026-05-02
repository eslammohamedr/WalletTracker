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
    
    private val aiService = AiService(com.example.wallettrackers.BuildConfig.GEMINI_API_KEY)

    private var observeJob: Job? = null

    init {
        _ignoredSenders.value = smsPrefs.getStringSet("ignored_senders", emptySet()) ?: emptySet()
        observeRules()
        observeData()
    }

    private fun observeRules() {
        viewModelScope.launch {
            repository.getCategoryRules().collect { rules ->
                _categoryRules.value = rules
            }
        }
    }

    fun fetchSms() {
        observeData()
    }

    fun refresh() {
        viewModelScope.launch {
            _isRefreshing.value = true
            delay(1200)
            observeData()
            _isRefreshing.value = false
        }
    }

    private fun observeData() {
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            combine(
                repository.getAccounts(),
                repository.getRecords(),
                repository.getCreditStatements()
            ) { accounts, records, statements ->
                Triple(accounts, records, statements)
            }.collect { (accounts, records, statements) ->
                _accounts.value = accounts
                val rawSms = fetchRawSmsFromInbox()
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
        accounts
            .filter { it.accountType.contains("Credit", ignoreCase = true) }
            .forEach { account ->
                val digits = account.last4Digits.filter { it.isDigit() }
                if (digits.isEmpty()) return@forEach
                val smsCurrency = rawSms
                    .firstOrNull { sms -> sms.body.contains(digits) && extractBalanceCurrencyFromSms(sms.body) != null }
                    ?.let { extractBalanceCurrencyFromSms(it.body) }
                    ?: return@forEach
                if (normaliseCurrency(account.currency) != smsCurrency) {
                    repository.updateAccount(account.copy(currency = smsCurrency))
                }
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
        val processedMessages = rawSms.filter { it.sender !in _ignoredSenders.value }.map { sms ->
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

    fun saveRuleAndResync(merchant: String, category: String) {
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
            _toastMessage.value = if (updated > 0)
                "Rule saved — updated $updated existing record${if (updated > 1) "s" else ""}"
            else
                "Rule saved for \"$merchant\""
        }
    }

    fun deleteRule(ruleId: String) {
        viewModelScope.launch {
            repository.deleteCategoryRule(ruleId)
            _toastMessage.value = "Rule deleted"
        }
    }

    private suspend fun processManualExtraction(message: SmsMessage, amount: String) {
        val type = message.extractedType ?: "Expense"

        // User-defined rules take priority over AI classification
        val ruleCategory = _categoryRules.value.firstOrNull { rule ->
            message.body.contains(rule.merchantKeyword, ignoreCase = true)
        }?.category

        val category = when {
            type == "Statement" -> "Credit Card"
            ruleCategory != null -> ruleCategory
            type == "Income" || type == "Expense" ->
                aiService.inferCategory(message.body) ?: (message.extractedCategory ?: "Others")
            else -> message.extractedCategory ?: "Others"
        }

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

        when {
            manualResult.type == "Statement" || manualResult.isStatement -> saveStatement(message, manualResult)
            manualResult.type == "CardPayment" -> saveCardPayment(message, manualResult)
            manualResult.type == "CreditCardReceived" -> saveCreditCardReceivedManual(message, manualResult)
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

    private suspend fun saveCreditCardReceivedManual(message: SmsMessage, ai: ExtractedTransaction) {
        val currentAccounts = repository.getAccounts().first()
        val statements = repository.getCreditStatements().first()
        val creditDigits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0

        val unpaid = statements.find { it.cardLast4Digits == creditDigits && !it.isPaid }
        if (unpaid != null) {
            repository.updateCreditStatement(unpaid.copy(isPaid = true))
            ReminderManager.cancelReminders(getApplication(), unpaid.smsId)
        }

        val creditAccount = currentAccounts.find { acc ->
            val ad = acc.last4Digits.filter { it.isDigit() }
            ad.isNotEmpty() && creditDigits.isNotEmpty() && (ad == creditDigits || creditDigits.endsWith(ad) || ad.endsWith(creditDigits))
        }
        val ccName = creditAccount?.name ?: "Credit Card ****$creditDigits"

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
        val updated = _ignoredSenders.value + sender
        _ignoredSenders.value = updated
        smsPrefs.edit().putStringSet("ignored_senders", updated).apply()
        _smsMessages.value = _smsMessages.value.filter { it.sender != sender }
        _toastMessage.value = "Sender \"$sender\" ignored"
    }

    fun unignoreSender(sender: String) {
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

    private fun isDeclinedTransaction(body: String): Boolean {
        val b = body.lowercase()
        return listOf(
            "transaction declined", "has been declined", "was declined",
            "card declined", "purchase declined", "payment declined",
            "transaction unsuccessful", "transaction failed",
            "payment unsuccessful", "insufficient funds",
            "unable to process your", "could not be processed"
        ).any { b.contains(it) }
    }

    private fun isBankSms(body: String): Boolean {
        if (isPromotionalSms(body)) return false
        if (isDeclinedTransaction(body)) return false
        val b = body.lowercase()

        val hasAmount = Regex("""(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)\s*[\d,]+|[\d,]+\s*(?:EGP|USD|EUR|GBP|SAR|AED|LE)""", RegexOption.IGNORE_CASE).containsMatchIn(b)

        if (!hasAmount) {
            return listOf("salary", "instapay", "ipn inward", "ipn outward").any { b.contains(it) }
        }

        val hasTransactionVerb = listOf(
            "debited", "debited by", "credited", "spent", "charged", "withdrawn",
            "cashback", "paid to", "payment of", "purchase at",
            "has been used", "now debited", "a refund of", "deposit of"
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
            if (bodyLower.contains("amt due")) return "Statement"
            if (bodyLower.contains("paid") || bodyLower.contains("received")) return "CardPayment"
            if (bodyLower.contains("check your statement") || bodyLower.contains("log on to")) {
                val incomeKeywords = listOf("credited", "received", "earned")
                if (incomeKeywords.any { bodyLower.contains(it) }) return "Income"
                return "Expense"
            }
            return "Statement"
        }

        // Credit-SIDE of a CC payment — credit card bank confirms receiving the payment.
        // Must be checked before generic "credited/received" → Income fallback.
        if (!bodyLower.contains("cashback")) {
            val hasPaymentAction = bodyLower.contains("payment received") || bodyLower.contains("payment credited") ||
                bodyLower.contains("has been credited") || bodyLower.contains("was credited") ||
                bodyLower.contains("credited to") || bodyLower.contains("received for") ||
                bodyLower.contains("was made to") || bodyLower.contains("made to your") ||
                (bodyLower.contains("payment of") && (bodyLower.contains("received") || bodyLower.contains("credited")))
            val hasCreditRef = bodyLower.contains("credit card") || bodyLower.contains("your card") ||
                bodyLower.contains("credit limit") || bodyLower.contains("available credit") ||
                bodyLower.contains("bm credit") || bodyLower.contains("banq masr")
            if (hasPaymentAction && hasCreditRef) return "CreditCardReceived"
        }

        // A deposit made TO a credit card is a payment, not income
        if (bodyLower.contains("deposit") && (bodyLower.contains("credit card") || bodyLower.contains("bm credit card"))) return "CreditCardReceived"

        if (bodyLower.contains("made to credit card") ||
            (bodyLower.contains("transfer") && bodyLower.contains("credit card")) ||
            (bodyLower.contains("instapay") && bodyLower.contains("credit card"))) return "CardPayment"
        if (bodyLower.contains("withdrawal")) return "AtmWithdrawal"

        val incomeKeywords = listOf("credited", "received", "deposit", "returned", "salary", "tt payment", "ipn inward", "earned cashback")
        if (incomeKeywords.any { bodyLower.contains(it) }) return "Income"
        if (body.contains("+")) return "Income"

        return "Expense"
    }

    private fun inferCategory(body: String): String {
        // User-defined rules take highest priority over hardcoded patterns
        _categoryRules.value.firstOrNull { rule ->
            body.contains(rule.merchantKeyword, ignoreCase = true)
        }?.let { return it.category }

        return when {
            body.contains("cashback", ignoreCase = true) -> "Others"
            body.contains("IPN outward", ignoreCase = true) -> "Instapay outcome"
            body.contains("IPN inward", ignoreCase = true) -> "Instapay income"
            body.contains("Salary", ignoreCase = true) || body.contains("TT Payment", ignoreCase = true) -> "Salary"
            // Groceries
            body.contains("BEET ELGOMLA", ignoreCase = true) ||
                    body.contains("Carrefour", ignoreCase = true) ||
                    body.contains("Hypermarket", ignoreCase = true) -> "Groceries"
            // Ride-hailing
            body.contains("Uber", ignoreCase = true) || body.contains("Careem", ignoreCase = true) -> "Uber"
            // Streaming & subscriptions
            body.contains("Netflix", ignoreCase = true) ||
                    body.contains("YouTube", ignoreCase = true) ||
                    body.contains("Amazon Prime", ignoreCase = true) ||
                    body.contains("Shahid", ignoreCase = true) ||
                    body.contains("Yango Play", ignoreCase = true) ||
                    body.contains("Steam", ignoreCase = true) ||
                    body.contains("PlayStation", ignoreCase = true) -> "Subscriptions"
            // Food delivery
            body.contains("talabat", ignoreCase = true) -> "Restaurants"
            // Restaurants / Cafes (by merchant name keywords)
            body.contains("CAFE", ignoreCase = true) ||
                    body.contains("RESTAURANT", ignoreCase = true) ||
                    body.contains("COFFEE", ignoreCase = true) -> "Restaurants"
            // Mobile payments gateway
            body.contains("Fawry", ignoreCase = true) -> "Mobile"
            else -> "Others"
        }
    }

    private fun inferComment(body: String): String? {
        if (body.contains("cashback", ignoreCase = true)) return "Cashback"

        // InstaPay outward: extract recipient name
        val isInstapayOut = body.contains("IPN outward", ignoreCase = true) ||
                (body.contains("instapay", ignoreCase = true) && (
                    body.contains("outward", ignoreCase = true) ||
                    body.contains("sent", ignoreCase = true) ||
                    body.contains("transferred", ignoreCase = true)
                ))
        if (isInstapayOut) {
            // Try to match letter-starting names first
            val instapayToRegex = Regex(
                """(?:transferred?|sent)?\s*to\s+([A-Za-z؀-ۿ][A-Za-z؀-ۿ\s]{1,40})(?:\s+with\s+reference|\s+has\s+been|\s+from\s+your|[.,\n]|$)""",
                RegexOption.IGNORE_CASE
            )
            instapayToRegex.find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return "To: $it" }
            // Fallback: any recipient (e.g. phone alias like "0170")
            Regex("""to\s+(\S+)\s+with\s+reference""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return "To: $it" }
        }

        // InstaPay inward: extract sender name
        val isInstapayIn = body.contains("IPN inward", ignoreCase = true) ||
                (body.contains("instapay", ignoreCase = true) && body.contains("inward", ignoreCase = true))
        if (isInstapayIn) {
            Regex("""from\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return "From: $it" }
        }

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
        val trackedMessages = _smsMessages.value.filter { it.hasRecordAdded && it.linkedRecord != null }
        if (trackedMessages.isEmpty()) {
            _toastMessage.value = "No tracked messages found."
            return
        }

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
            _toastMessage.value = "Re-tracked $count messages!"
        }
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
            _toastMessage.value = "Successfully tracked $addedCount messages!"
        }
    }
}
