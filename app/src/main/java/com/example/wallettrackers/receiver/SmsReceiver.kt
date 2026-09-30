package com.example.wallettrackers.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.wallettrackers.BuildConfig
import com.example.wallettrackers.MainActivity
import com.example.wallettrackers.db.WalletDatabase
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.repository.OfflineFirstRepository
import com.example.wallettrackers.repository.WalletRepository
import com.example.wallettrackers.service.AiService
import com.example.wallettrackers.service.ExtractedTransaction
import com.example.wallettrackers.util.BudgetAlertHelper
import com.example.wallettrackers.util.FinancialCalculator
import com.example.wallettrackers.util.ReminderManager
import com.example.wallettrackers.util.SmsParser
import com.example.wallettrackers.util.CreditPaymentMatcher
import com.example.wallettrackers.util.CreditPaymentLinker
import com.example.wallettrackers.util.InboxSmsCandidate
import com.example.wallettrackers.util.SmsInboxFallback
import com.example.wallettrackers.util.SmsMultipartAssembler
import com.example.wallettrackers.util.SmsPduPart
import com.example.wallettrackers.util.SmsBroadcastId
import com.example.wallettrackers.util.SmsCardPaymentDigits
import com.example.wallettrackers.util.SmsAccountMatcher
import com.example.wallettrackers.util.StatementDueDateParser
import com.example.wallettrackers.util.BalanceAmountFormatter
import com.example.wallettrackers.util.InstapayPairingPolicy
import com.example.wallettrackers.util.PendingCreditPaymentStore
import com.google.firebase.auth.FirebaseAuth
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SmsReceiver : BroadcastReceiver() {

    private companion object {
        val smsProcessingMutex = Mutex()
    }

    private val channelId = "transaction_alerts"
    private val aiService = AiService(
        groqApiKey     = BuildConfig.GROQ_API_KEY,
        cerebrasApiKey = BuildConfig.CEREBRAS_API_KEY,
        geminiApiKey   = BuildConfig.GEMINI_API_KEY
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        com.example.wallettrackers.service.AiEndpointOverride.initialize(context)
        val userId = FirebaseAuth.getInstance().currentUser?.uid
        if (userId == null) {
            Log.w("SmsReceiver", "SMS_RECEIVED ignored because no Firebase user is signed in")
            return
        }
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val hasReadSmsPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        Log.i("SmsReceiver", "SMS_RECEIVED: pduCount=${messages.size}, readSmsPermission=$hasReadSmsPermission")
        if (SmsInboxFallback.shouldReadInboxFallback(messages.size)) {
            if (!hasReadSmsPermission) {
                Log.w("SmsReceiver", "Cannot process stripped-PDU broadcast: READ_SMS permission is not granted")
                return
            }
            enqueueSmsWork(context, userId, workDataOf("user_id" to userId, "inbox_fallback" to true))
            return
        }
        val assembledMessages = SmsMultipartAssembler.assemble(messages.map { sms ->
            SmsPduPart(
                sender = sms.displayOriginatingAddress.orEmpty(),
                timestampMillis = sms.timestampMillis,
                body = sms.displayMessageBody.orEmpty()
            )
        })
        Log.i("SmsReceiver", "Enqueuing ${assembledMessages.size} assembled SMS message(s) for ordered processing")
        assembledMessages.forEach { sms ->
            val smsId = SmsBroadcastId.create(sms.timestampMillis, sms.sender, sms.body)
            enqueueSmsWork(context, userId, workDataOf(
                "user_id" to userId,
                "body" to sms.body,
                "sms_id" to smsId,
                "timestamp" to sms.timestampMillis,
                "sender" to sms.sender
            ))
        }
    }

    private fun enqueueSmsWork(context: Context, userId: String, input: androidx.work.Data) {
        val request = OneTimeWorkRequestBuilder<SmsProcessingWorker>()
            .setInputData(input)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).beginUniqueWork(
            "wallet-sms-processing-$userId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        ).enqueue()
    }

    internal suspend fun processQueuedSms(context: Context, userId: String, body: String, smsId: String, timestamp: Long, sender: String) {
        com.example.wallettrackers.service.AiEndpointOverride.initialize(context)
        processSms(context, userId, body, smsId, Date(timestamp), sender)
    }

    internal suspend fun processRecentSmsInbox(context: Context, userId: String) {
        val candidates = SmsInboxFallback.readRecentCandidatesWithRetry(readCandidates = {
            readRecentInboxSms(context)
        })
        if (candidates.isEmpty()) {
            Log.w("SmsReceiver", "SMS_RECEIVED contained no PDUs; no eligible Inbox row appeared during bounded retry")
        } else {
            Log.i("SmsReceiver", "Stripped-PDU fallback found ${candidates.size} recent Inbox candidate(s)")
            processInboxFallback(context, userId, candidates)
        }
    }

    private suspend fun processInboxFallback(context: Context, userId: String, candidates: List<InboxSmsCandidate>) {
        val preferences = context.getSharedPreferences("sms_receiver_fallback", Context.MODE_PRIVATE)
        val lastProcessedId = preferences.getLong("last_processed_inbox_id", 0L)
        val unseenCandidates = SmsInboxFallback.selectRecentUnseen(
            candidates,
            System.currentTimeMillis(),
            lastProcessedId
        )
        unseenCandidates.forEach { candidate ->
            val rowId = candidate.id.toLongOrNull() ?: return@forEach
            processSms(
                context = context,
                userId = userId,
                body = candidate.body,
                smsId = "inbox:" + candidate.id,
                date = Date(candidate.timestampMillis),
                sender = candidate.address
            )
            preferences.edit().putLong("last_processed_inbox_id", rowId).apply()
        }
    }

    private fun readRecentInboxSms(context: Context): List<InboxSmsCandidate> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val now = System.currentTimeMillis()
        val projection = arrayOf("_id", "address", "body", "date")
        val candidates = mutableListOf<InboxSmsCandidate>()
        try {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                projection,
                "date >= ? AND date <= ?",
                arrayOf(
                    (now - SmsInboxFallback.MAX_AGE_MILLIS).toString(),
                    (now + 5_000L).toString()
                ),
                "date DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow("_id")
                val addressColumn = cursor.getColumnIndexOrThrow("address")
                val bodyColumn = cursor.getColumnIndexOrThrow("body")
                val dateColumn = cursor.getColumnIndexOrThrow("date")
                while (cursor.moveToNext()) {
                    candidates += InboxSmsCandidate(
                        id = cursor.getString(idColumn).orEmpty(),
                        address = cursor.getString(addressColumn).orEmpty(),
                        body = cursor.getString(bodyColumn).orEmpty(),
                        timestampMillis = cursor.getLong(dateColumn)
                    )
                }
            }
        } catch (error: Exception) {
            Log.e("SmsReceiver", "Failed to read recent inbox SMS for stripped-PDU fallback", error)
            return emptyList()
        }

        return SmsInboxFallback.selectRecentUnseen(
            candidates,
            now,
            context.getSharedPreferences("sms_receiver_fallback", Context.MODE_PRIVATE)
                .getLong("last_processed_inbox_id", 0L)
        )
    }

    private fun isNonBankSender(sender: String): Boolean {
        val s = sender.lowercase().trim()
        val blockedSenders = listOf(
            "vodafone", "voda", "vf-cash", "vfcash", "vf cash",
            "orange", "orangemoney", "orange-money",
            "etisalat", "e&egypt", "e& egypt",
            "we-egypt", "weegypt", "telecomegypt", "telecom egypt",
            "amazon", "noon", "jumia", "careem", "uber", "talabat",
            "whatsapp", "google", "apple", "facebook", "instagram",
            "twitter", "tiktok", "snapchat", "linkedin", "telegram",
            "netflix", "spotify", "shahid", "starzplay", "anghami",
            "elmenus", "swvl", "otlob", "glovo", "breadfast",
            "souq", "namshi", "b.tech", "extra", "lc waikiki",
            "zara", "h&m", "starbucks", "costa", "mcdonalds",
            "dominos", "kfc", "hardees", "pizza hut", "gourmet"
        )
        if (blockedSenders.any { s.contains(it) }) return true
        // Egyptian mobile numbers (01XXXXXXXXX) are personal, not banks
        val cleaned = s.removePrefix("+20").removePrefix("20").removePrefix("0")
        if (cleaned.matches(Regex("""^1[0125][0-9]{8}$"""))) return true
        return false
    }

    private suspend fun processSms(context: Context, userId: String, body: String, smsId: String, date: Date, sender: String = "") {
        smsProcessingMutex.withLock {
            processSmsOnce(context, userId, body, smsId, date, sender)
        }
    }

    private suspend fun processSmsOnce(context: Context, userId: String, body: String, smsId: String, date: Date, sender: String) {
        if (isNonBankSender(sender)) {
            Log.d("SmsReceiver", "Skipping SMS from non-bank sender: $sender")
            return
        }

        val db = WalletDatabase.getInstance(context)
        val repository: WalletRepository = OfflineFirstRepository(FirebaseRepository(userId), db.recordDao(), db.accountDao(), userId)

        if (repository.recordWithSmsIdExists(smsId) || repository.statementWithSmsIdExists(smsId)) {
            Log.d("SmsReceiver", "SMS already processed, skipping: $smsId")
            return
        }

        // Always keep account balance current if the bank prints a balance in this SMS
        val smsBalance = extractBalanceFromSms(body)
        var balanceBeforeSms: String? = null
        if (smsBalance != null) {
            val digits = extractLast4Digits(body)?.filter { it.isDigit() } ?: ""
            if (digits.isNotEmpty()) {
                val accounts = repository.getAccounts().first()
                val account = matchAccount(accounts, digits, body, sender)
                if (account != null) {
                    balanceBeforeSms = account.amount
                    val storedBal = account.amount.toDoubleOrNull() ?: 0.0
                    if (storedBal != smsBalance) {
                        val drift = kotlin.math.abs(storedBal - smsBalance)
                        if (drift > 100.0 && storedBal > 0.0) {
                            Log.w("SmsReceiver", "Balance drift on ${account.name}: stored=$storedBal actual=$smsBalance diff=$drift")
                            sendNotification(
                                context,
                                "Balance Corrected: ${account.name}",
                                "Was %.2f → now %.2f (%.2f difference)".format(storedBal, smsBalance, drift),
                                false
                            )
                        }
                        repository.updateAccount(account.copy(amount = BalanceAmountFormatter.format(smsBalance)))
                    }
                }
            }
        }

        if (isDeclinedTransaction(body)) {
            Log.d("SmsReceiver", "Skipping declined transaction SMS")
            return
        }

        // Load rules once — user-defined rules always win on category
        val rules = repository.getCategoryRules().first()
        fun applyRules(tx: ExtractedTransaction): ExtractedTransaction {
            val matched = rules.firstOrNull { it.merchantKeyword.isNotBlank() && body.contains(it.merchantKeyword, ignoreCase = true) }
            return if (matched != null) tx.copy(category = matched.category) else tx
        }

        suspend fun save(tx: ExtractedTransaction) {
            if (!SmsParser.isPositiveTransactionAmount(tx.amount)) {
                Log.w("SmsReceiver", "Skipping transaction with missing or non-positive amount: smsId=$smsId amount='${tx.amount}'")
                return
            }
            val final = applyRules(tx)
            when (final.type) {
                "Statement"          -> saveStatement(context, repository, userId, smsId, final, body, sender)
                "CardPayment"        -> saveCardPayment(context, repository, userId, smsId, date, final, body, balanceBeforeSms.orEmpty(), sender)
                "CreditCardReceived" -> saveCreditCardReceived(context, repository, userId, smsId, date, final, body, balanceBeforeSms.orEmpty(), sender)
                "AtmWithdrawal"      -> saveAtmWithdrawal(context, repository, userId, smsId, date, final, body, sender, balanceBeforeSms.orEmpty())
                else                 -> saveRecord(context, repository, userId, smsId, date, final, body, balanceBeforeSms.orEmpty(), sender)
            }
        }

        // 1. Keywords extract structure; dedicated AI inferCategory assigns category (same as SMS Center)
        if (isBankSms(body, sender)) {
            val amount      = extractAmount(body)
            val keywordType = inferType(body)

            if (amount != null) {
                // Special types have their category hardcoded in the save functions — no AI needed
                val finalCategory = when (keywordType) {
                    "Statement"                        -> "Credit Card"
                    "AtmWithdrawal"                    -> "Transfer"
                    "CardPayment", "CreditCardReceived" -> "Credit Payment"
                    else -> {
                        // Detection priority: saved rules → AI → keyword.
                        // Saved rules win via applyRules() in save(); here we try the AI first
                        // and fall back to keyword detection only when the AI is unsure ("Others").
                        val aiCategory = try {
                            withTimeoutOrNull(4000L) { aiService.inferCategory(body) }
                        } catch (e: Exception) {
                            Log.w("SmsReceiver", "AI inferCategory failed: ${e.message}")
                            null
                        }
                        if (!aiCategory.isNullOrBlank() && !aiCategory.equals("Others", ignoreCase = true)) {
                            aiCategory
                        } else {
                            inferCategory(body)
                        }
                    }
                }

                save(ExtractedTransaction(
                    amount = amount, category = finalCategory, type = keywordType,
                    isBankRelated = true, last4Digits = extractLast4Digits(body),
                    isStatement = keywordType == "Statement", dueDate = extractDueDate(body),
                    comment = inferComment(body) ?: ""
                ))
                return
            }
        }

        // 2. Unknown format — full AI extraction as last resort.
        // Skip if the body was already identified as non-bank (telecom/promo/OTP) — the AI can
        // misclassify Arabic promotional SMS (e.g. Vodafone Cash offers) as financial.
        if (SmsParser.isNonBankSms(body) || SmsParser.isPromotionalSms(body) || SmsParser.isAdvertisement(body)) {
            Log.d("SmsReceiver", "Skipping AI fallback — non-bank/promo/ad SMS body")
            return
        }
        try {
            val result = withTimeoutOrNull(5000L) { aiService.analyzeSms(body) }
            if (result != null && result.isBankRelated) save(result)
        } catch (e: Exception) {
            Log.e("SmsReceiver", "AI full extraction failed", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Dual-SMS credit card payment handlers
    // ──────────────────────────────────────────────────────────────

    // SharedPreferences key scheme for pending Instapay transfers:
    //   key   = "ip_out_<amount>" or "ip_in_<amount>"
    //   value = "<smsId>|<accountId>|<accountName>|<currency>|<epochMillis>"
    private data class PendingInstapay(val smsId: String, val accountId: String, val accountName: String, val currency: String)

    private fun storeInstapayPending(context: Context, isOutgoing: Boolean, amount: String, smsId: String, accountId: String, accountName: String, currency: String) {
        val key = if (isOutgoing) "ip_out_$amount" else "ip_in_$amount"
        context.getSharedPreferences("pending_instapay", Context.MODE_PRIVATE)
            .edit()
            .putString(key, "$smsId|$accountId|${accountName.replace("|", "_")}|$currency|${System.currentTimeMillis()}")
            .apply()
    }

    private fun consumeInstapayPending(
        context: Context,
        isOutgoing: Boolean,
        amount: String,
        currentAccountId: String,
        currentCurrency: String
    ): PendingInstapay? {
        val lookPrefix = if (isOutgoing) "ip_in_" else "ip_out_"
        val amountDouble = amount.toDoubleOrNull() ?: return null
        val prefs = context.getSharedPreferences("pending_instapay", Context.MODE_PRIVATE)
        val match = prefs.all.entries
            .filter { it.key.startsWith(lookPrefix) }
            .mapNotNull { entry ->
                val storedAmt = entry.key.removePrefix(lookPrefix).toDoubleOrNull() ?: return@mapNotNull null
                val raw = entry.value as? String ?: return@mapNotNull null
                val parts = raw.split("|")
                if (parts.size < 5) return@mapNotNull null
                val timestamp = parts[4].toLongOrNull() ?: 0L
                if (System.currentTimeMillis() - timestamp > 10 * 60_000L) {
                    prefs.edit().remove(entry.key).apply()
                    return@mapNotNull null
                }
                if (!InstapayPairingPolicy.canPair(parts[1], parts[3], currentAccountId, currentCurrency)) {
                    return@mapNotNull null
                }
                val diff = kotlin.math.abs(storedAmt - amountDouble)
                if (diff <= 5.0 || diff / maxOf(amountDouble, storedAmt) <= 0.02) entry to diff else null
            }
            .minByOrNull { it.second }
            ?.first ?: return null
        val raw = match.value as? String ?: return null
        val parts = raw.split("|")
        if (parts.size < 5) { prefs.edit().remove(match.key).apply(); return null }
        val timestamp = parts[4].toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - timestamp > 10 * 60_000L) {
            prefs.edit().remove(match.key).apply()
            return null
        }
        prefs.edit().remove(match.key).apply()
        return PendingInstapay(smsId = parts[0], accountId = parts[1], accountName = parts[2], currency = parts[3])
    }

    /**
     * Handles the DEBIT-SIDE SMS.
     *
     * Pending path — credit-side SMS arrived first (pending flag set):
     *   • CC balance already restored; deduct from debit account only
     *   • Upgrade the partial record to "DebitAcc -> CreditCard"
     *   • Clear the pending flag
     *
     * Scenario A — debit arrives first, no prior record:
     *   • Deduct from debit account + restore CC + create full record
     *
     * Scenario B — full record already exists (has "->"):
     *   • Duplicate debit SMS → skip
     */
    private suspend fun saveCardPayment(
        context: Context, repository: WalletRepository, userId: String,
        smsId: String, date: Date, ai: ExtractedTransaction, smsBody: String = "", balanceBeforeSms: String = "", sender: String = ""
    ) {
        val accounts = repository.getAccounts().first()
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0

        // Extract source and credit card digits separately so we don't confuse the two.
        // extractLast4Digits() can return SOURCE account digits for SMS like "from ****5678 to credit card 1234".
        val paymentDigits = SmsCardPaymentDigits.parse(smsBody)
        val extractedSourceDigits = paymentDigits.sourceDigits
        val extractedCreditDigits = paymentDigits.creditCardDigits
        val smsCreditDigits = paymentDigits.resolveCreditCardDigits(ai.last4Digits)
        val sourceAccounts = accounts.filter { !it.accountType.contains("Credit", ignoreCase = true) }
        val smsContainsSourceSuffix = sourceAccounts.any { account ->
            val accountDigits = account.last4Digits.filter(Char::isDigit)
            accountDigits.length >= 3 && smsBody.contains(accountDigits)
        }
        val pendingStore = PendingCreditPaymentStore(context)
        val pending = pendingStore.peek(ai.amount, smsCreditDigits)
        val partialRecord = if (pending != null) repository.findRecordBySmsId(pending.smsId) else null
        val creditDigits = pending?.creditDigits ?: smsCreditDigits
        val creditAccount = if (pending != null) {
            CreditPaymentLinker.findCreditAccount(accounts, pending.creditDigits, smsCreditDigits, partialRecord?.accountId.orEmpty())
        } else {
            matchAccount(accounts, creditDigits, smsBody, sender)
        }
        Log.d("SmsReceiver", "saveCardPayment: extractedCreditDigits='$extractedCreditDigits' ai.last4Digits='${ai.last4Digits}' creditDigits='$creditDigits' creditAccount=${creditAccount?.name} creditAccountId=${creditAccount?.id}")

        markStatementPaidIfUnambiguous(repository, context, accounts, creditDigits, creditAccount)

        // Pending path: credit-side SMS arrived first and stored a flag.
        // CC balance is already restored — just handle the debit side.
        if (pending != null) {
            val sourceAccount = findSourceAccount(accounts, smsBody, sender)
                ?: if (extractedSourceDigits == null && !smsContainsSourceSuffix) {
                    findSourceAccountByBalance(accounts, smsBody, paymentAmt)
                } else null
            if (sourceAccount != null) {
                pendingStore.remove(pending)
                val calculated = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - paymentAmt
                val finalDebitBal = extractBalanceFromSms(smsBody) ?: calculated
                val formattedDebitBalance = BalanceAmountFormatter.format(finalDebitBal)
                repository.updateAccount(sourceAccount.copy(amount = formattedDebitBalance))
                if (partialRecord != null && !partialRecord.accountName.contains("->")) {
                    val sourceBalanceBefore = BalanceAmountFormatter.format(
                        balanceBeforeSms.toDoubleOrNull() ?: (sourceAccount.amount.toDoubleOrNull() ?: 0.0)
                    )
                    val linkedRecord = if (creditAccount != null) {
                        CreditPaymentLinker.completePartialRecord(partialRecord, sourceAccount, creditAccount)
                    } else {
                        partialRecord.copy(accountId = sourceAccount.id, accountName = "${sourceAccount.name} -> ${partialRecord.accountName}")
                    }
                    repository.updateRecord(linkedRecord.copy(
                        balanceBefore = sourceBalanceBefore,
                        balanceAfter = formattedDebitBalance,
                        smsId = smsId
                    ))
                } else {
                    val sourceBalanceBefore = BalanceAmountFormatter.format(
                        balanceBeforeSms.toDoubleOrNull() ?: (sourceAccount.amount.toDoubleOrNull() ?: 0.0)
                    )
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = sourceAccount.id,
                        accountName = "${sourceAccount.name} -> ${creditAccount?.name ?: "Credit Card ****$creditDigits"}",
                        currency = sourceAccount.currency, userId = userId, timestamp = date,
                        smsId = smsId, balanceBefore = sourceBalanceBefore,
                        balanceAfter = formattedDebitBalance, comment = ai.comment
                    ))
                }
                sendNotification(context, "Credit Card Payment Complete",
                    "${sourceAccount.name} paid ${ai.amount} to card ****$creditDigits", true)
            }
            return
        }

        // Normal path: check for an existing record (debit-first or duplicate)
        val existingRecord = repository.findRecentCardPaymentRecord(ai.amount)
        when {
            // Full record already exists — duplicate debit SMS, skip
            existingRecord != null && existingRecord.accountName.contains("->") -> {
                Log.d("SmsReceiver", "Duplicate debit SMS for credit payment, skipping")
            }

            // Scenario A: no prior record — debit arrived first, full operation
            else -> {
                if (creditAccount != null) {
                    repository.updateAccount(creditAccount.copy(
                        amount = BalanceAmountFormatter.format((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt)
                    ))
                }
                val sourceAccount = findSourceAccount(accounts, smsBody, sender)
                    ?: if (extractedSourceDigits == null && !smsContainsSourceSuffix) {
                        findSourceAccountByBalance(accounts, smsBody, paymentAmt)
                    } else null
                if (sourceAccount != null) {
                    val calculated = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - paymentAmt
                    val finalDebitBal = extractBalanceFromSms(smsBody) ?: calculated
                    val formattedDebitBalance = BalanceAmountFormatter.format(finalDebitBal)
                    val sourceBalanceBefore = BalanceAmountFormatter.format(
                        balanceBeforeSms.toDoubleOrNull() ?: (sourceAccount.amount.toDoubleOrNull() ?: 0.0)
                    )
                    repository.updateAccount(sourceAccount.copy(amount = formattedDebitBalance))
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = sourceAccount.id,
                        accountName = "${sourceAccount.name} -> ${creditAccount?.name ?: "Credit Card ****$creditDigits"}",
                        currency = sourceAccount.currency, userId = userId, timestamp = date,
                        smsId = smsId, balanceBefore = sourceBalanceBefore,
                        balanceAfter = formattedDebitBalance, comment = ai.comment
                    ))
                } else {
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = creditAccount?.id ?: "",
                        accountName = creditAccount?.name ?: "Credit Card ****$creditDigits",
                        currency = creditAccount?.currency ?: inferCurrency(smsBody), userId = userId, timestamp = date,
                        smsId = smsId, balanceAfter = "", comment = ai.comment
                    ))
                }
                sendNotification(context, "Credit Card Payment Tracked",
                    "Payment of ${ai.amount} to card ****$creditDigits", true)
            }
        }
    }

    /**
     * Handles the CREDIT-SIDE SMS.
     *
     * Scenario A — debit-side record already exists (debit arrived first):
     *   • Everything was handled by saveCardPayment; just confirm
     *
     * Scenario B — no prior record (credit SMS arrives first):
     *   • Restore CC balance + mark statement paid
     *   • Create partial record: "Credit Card ****XXXX" (no debit side yet)
     *   • Store a pending flag in SharedPreferences
     *   • When debit SMS arrives later, saveCardPayment() picks up the flag and completes it
     */
    private suspend fun saveCreditCardReceived(
        context: Context, repository: WalletRepository, userId: String,
        smsId: String, date: Date, ai: ExtractedTransaction, body: String = "", balanceBeforeSms: String = "", sender: String = ""
    ) {
        val accounts = repository.getAccounts().first()
        val creditDigits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0
        val creditAccount = matchAccount(accounts, creditDigits, body, sender)

        markStatementPaidIfUnambiguous(repository, context, accounts, creditDigits, creditAccount)

        // Check if the debit-side was already saved (as CardPayment or as a regular Expense/Instapay)
        val targetCardName = creditAccount?.name ?: "Credit Card ****$creditDigits"
        val completedPayment = CreditPaymentMatcher.findCompletedPayment(
            repository.getRecords().first(), ai.amount, targetCardName, date.time
        )
        if (completedPayment != null) {
            val printedCardBalance = extractBalanceFromSms(body)
            val reconciliation = CreditPaymentLinker.reconcileCompletedPayment(
                completedPayment,
                paymentAmt,
                cardBalanceAlreadyPrinted = printedCardBalance != null
            )
            if (creditAccount != null) {
                val reconciledBalance = printedCardBalance ?: if (reconciliation.cardBalanceAdjustment != 0.0) {
                    (creditAccount.amount.toDoubleOrNull() ?: 0.0) + reconciliation.cardBalanceAdjustment
                } else null
                if (reconciledBalance != null) {
                    repository.updateAccount(creditAccount.copy(amount = BalanceAmountFormatter.format(reconciledBalance)))
                }
            }
            if (reconciliation.record != completedPayment) {
                repository.updateRecord(reconciliation.record)
            }
            sendNotification(context, "Credit Card Payment Confirmed",
                "Payment of ${ai.amount} confirmed for card ****$creditDigits", false)
            return
        }

        val existingRecord = repository.findRecentDebitExpenseRecord(ai.amount, date.time)
        if (existingRecord != null) {
            // Debit expense record exists but hasn't been upgraded to a transfer yet.
            // Restore CC balance and upgrade the record to "DebitAccount -> CreditCard".
            if (creditAccount != null) {
                val calculated = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt
                val finalBal = extractBalanceFromSms(body) ?: calculated
                repository.updateAccount(creditAccount.copy(amount = BalanceAmountFormatter.format(finalBal)))
            }
            repository.updateRecord(existingRecord.copy(
                category = "Credit Payment",
                accountName = if (existingRecord.accountName.isNotBlank())
                    "${existingRecord.accountName} -> ${creditAccount?.name ?: "Credit Card ****$creditDigits"}"
                else
                    creditAccount?.name ?: "Credit Card ****$creditDigits",
                transferDestinationAmount = ai.amount,
                smsId = smsId
            ))
            markStatementPaidIfUnambiguous(repository, context, accounts, creditDigits, creditAccount)
            sendNotification(context, "Credit Card Payment Linked",
                "${existingRecord.accountName} → card ****$creditDigits: ${ai.amount}", true)
            return
        }

        // Credit arrives first: restore CC balance, create partial record, store pending flag.
        // Do NOT touch any debit account — the debit SMS will do that when it arrives.
        if (creditAccount != null) {
            val calculated = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt
            val finalBal = extractBalanceFromSms(body) ?: calculated
            repository.updateAccount(creditAccount.copy(amount = BalanceAmountFormatter.format(finalBal)))
        }
        PendingCreditPaymentStore(context).store(ai.amount, creditDigits, smsId)
        repository.addRecord(Record(
            amount = ai.amount, category = "Credit Payment", type = "Expense",
            accountId = creditAccount?.id ?: "",
            accountName = creditAccount?.name ?: "Credit Card ****$creditDigits",
            currency = creditAccount?.currency ?: inferCurrency(body), userId = userId, timestamp = date,
            smsId = smsId, balanceAfter = "", comment = ai.comment
        ))
        sendNotification(context, "Credit Card Payment Received",
            "Card ****$creditDigits received ${ai.amount} — awaiting debit bank SMS", false)
    }

    // ──────────────────────────────────────────────────────────────
    // Other save functions
    // ──────────────────────────────────────────────────────────────

    private suspend fun saveAtmWithdrawal(context: Context, repository: WalletRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction, body: String = "", sender: String = "", balanceBeforeSms: String = "") {
        val accounts = repository.getAccounts().first()
        val sourceAccount = matchAccount(accounts, ai.last4Digits?.filter { it.isDigit() } ?: "", body, sender)
        val amount = ai.amount.toDoubleOrNull() ?: 0.0

        if (sourceAccount == null) {
            repository.addRecord(Record(
                amount = ai.amount, category = "Transfer", type = "Expense",
                accountId = "", accountName = "Unknown bank -> Cash",
                currency = inferCurrency(body), userId = userId, timestamp = date,
                smsId = smsId, comment = "ATM Withdrawal; account match required"
            ))
            sendNotification(context, "Action Required: Match Account",
                "ATM withdrawal of ${ai.amount} ${inferCurrency(body)} was not applied to any balance.", true)
            return
        }

        val calculatedSourceBal = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - amount
        val finalSourceBal = extractBalanceFromSms(body) ?: calculatedSourceBal
        repository.updateAccount(sourceAccount.copy(amount = BalanceAmountFormatter.format(finalSourceBal)))

        // Always credit cash — ATM withdrawal always puts money in the user's pocket
        val cashAccount = accounts.find { it.accountType.equals("Cash", ignoreCase = true) }
        if (cashAccount != null) {
            val newCashBal = (cashAccount.amount.toDoubleOrNull() ?: 0.0) + amount
            repository.updateAccount(cashAccount.copy(amount = BalanceAmountFormatter.format(newCashBal)))
        }

        val sourceName = sourceAccount.name
        repository.addRecord(Record(
            amount = ai.amount, category = "Transfer", type = "Expense",
            accountId = sourceAccount.id,
            accountName = if (cashAccount != null) "$sourceName -> Cash" else sourceName,
            currency = sourceAccount.currency, userId = userId, timestamp = date,
            smsId = smsId, comment = "ATM Withdrawal",
            balanceBefore = balanceBeforeSms.toDoubleOrNull()?.let(BalanceAmountFormatter::format)
                ?: sourceAccount.amount.toDoubleOrNull()?.let(BalanceAmountFormatter::format).orEmpty(),
            balanceAfter = BalanceAmountFormatter.format(finalSourceBal)
        ))
        val notifDetail = "Deducted ${ai.amount} from ${sourceAccount.name}"
        sendNotification(context, "ATM Withdrawal Tracked",
            "$notifDetail${if (cashAccount != null) " and added to Cash" else ""}.", true)
    }

    private suspend fun saveRecord(context: Context, repository: WalletRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction, body: String = "", balanceBeforeSms: String = "", sender: String = "") {
        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        var targetAccount = matchAccount(accounts, digits, body, sender)

        if (targetAccount == null && digits.isBlank() && ai.category == "Salary") {
            targetAccount = accounts.maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
        }

        val amountDouble = ai.amount.toDoubleOrNull() ?: 0.0

        // Cross-bank CC payment: if a credit-side SMS is pending for this amount, this
        // Expense is its debit-side. Upgrade the partial record instead of creating a new one.
        if (ai.type == "Expense" && targetAccount != null) {
            // Guard: skip if a complete CC transfer already covers this payment
            val existingTransfer = repository.findRecentCardPaymentRecord(ai.amount)
            if (existingTransfer != null && existingTransfer.accountName.contains("->")) {
                Log.d("SmsReceiver", "Skipping duplicate — CC transfer already recorded for ${ai.amount}")
                return
            }

            val destinationDigits = SmsCardPaymentDigits.parse(body).resolveCreditCardDigits(ai.last4Digits)
            val pending = PendingCreditPaymentStore(context).consume(ai.amount, destinationDigits)
            if (pending != null) {
                val calculated = (targetAccount.amount.toDoubleOrNull() ?: 0.0) - amountDouble
                val finalBal = extractBalanceFromSms(body) ?: calculated
                val formattedBalance = BalanceAmountFormatter.format(finalBal)
                repository.updateAccount(targetAccount.copy(amount = formattedBalance))
                val partialRecord = repository.findRecordBySmsId(pending.smsId)
                if (partialRecord != null && !partialRecord.accountName.contains("->")) {
                    repository.updateRecord(partialRecord.copy(
                        accountId = targetAccount.id,
                        accountName = "${targetAccount.name} -> ${partialRecord.accountName}",
                        balanceAfter = formattedBalance,
                        smsId = smsId
                    ))
                } else {
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = targetAccount.id,
                        accountName = "${targetAccount.name} -> Credit Card ****${pending.creditDigits}",
                        currency = targetAccount.currency, userId = userId, timestamp = date,
                        smsId = smsId, balanceAfter = formattedBalance, comment = ai.comment
                    ))
                }
                sendNotification(context, "Credit Card Payment Complete",
                    "${targetAccount.name} paid ${ai.amount} to card ****${pending.creditDigits}", true)
                return
            }
        }

        // ── Instapay transfer linking (#9) ─────────────────────────────────────
        // Instapay expense = money sent from user's account A; income = money received on account B.
        // If both arrive within 10 min for the same amount, merge into one Transfer record.
        if (ai.category == "Instapay outcome" || ai.category == "Instapay income") {
            val isOutgoing = ai.category == "Instapay outcome"
            val matchingPending = if (!isOutgoing &&
                targetAccount?.accountType?.contains("Credit", ignoreCase = true) == true
            ) {
                null
            } else {
                consumeInstapayPending(
                    context,
                    isOutgoing,
                    ai.amount,
                    targetAccount?.id.orEmpty(),
                    targetAccount?.currency ?: inferCurrency(body)
                )
            }
            if (matchingPending != null) {
                val sourceName: String; val sourceId: String; val sourceCurrency: String; val destName: String
                if (isOutgoing) {
                    sourceName = targetAccount?.name ?: "Account"
                    sourceId = targetAccount?.id ?: ""
                    sourceCurrency = targetAccount?.currency ?: "EGP"
                    destName = matchingPending.accountName
                    if (targetAccount != null) {
                        val calc = (targetAccount.amount.toDoubleOrNull() ?: 0.0) - amountDouble
                        val final = extractBalanceFromSms(body) ?: calc
                        repository.updateAccount(targetAccount.copy(amount = BalanceAmountFormatter.format(final)))
                    }
                } else {
                    destName = targetAccount?.name ?: "Account"
                    sourceName = matchingPending.accountName
                    sourceId = matchingPending.accountId
                    sourceCurrency = matchingPending.currency
                    if (targetAccount != null) {
                        val calc = (targetAccount.amount.toDoubleOrNull() ?: 0.0) + amountDouble
                        val final = extractBalanceFromSms(body) ?: calc
                        repository.updateAccount(targetAccount.copy(amount = BalanceAmountFormatter.format(final)))
                    }
                }
                val partialRecord = repository.findRecordBySmsId(matchingPending.smsId)
                if (partialRecord != null) {
                    repository.updateRecord(partialRecord.copy(
                        category = "Transfer", type = "Expense",
                        accountId = sourceId, accountName = "$sourceName -> $destName",
                        currency = sourceCurrency
                    ))
                } else {
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Transfer", type = "Expense",
                        accountId = sourceId, accountName = "$sourceName -> $destName",
                        currency = sourceCurrency, userId = userId, timestamp = date,
                        smsId = smsId, balanceAfter = "", comment = "Instapay Transfer"
                    ))
                }
                sendNotification(context, "Instapay Transfer Linked",
                    "EGP ${ai.amount}: $sourceName → $destName", true)
                return
            }
            // No pending match found for either side.
            if (!isOutgoing) {
                // Instapay inward to a credit card account is always the credit card payment
                // notification — NOT a genuine income. The CreditCardReceived SMS from the same
                // bank already handles this correctly (creates "debitAcc -> creditCard" record).
                // Saving it here would create a phantom +income record.
                if (targetAccount?.accountType?.contains("Credit", ignoreCase = true) == true) {
                    Log.d("SmsReceiver", "Skipping Instapay inward to credit card ${targetAccount.name} — handled by CreditCardReceived path")
                    return
                }
                // Also skip if there's already a recent Credit Payment record involving this account
                // (handles the case where CreditCardReceived SMS arrived before this IPN inward SMS)
                val recentCp = repository.findRecentCardPaymentRecord(ai.amount)
                if (recentCp != null) {
                    val accountMatches = targetAccount == null ||
                        recentCp.accountName.contains(targetAccount.name, ignoreCase = true)
                    if (accountMatches) {
                        Log.d("SmsReceiver", "Skipping Instapay inward — already tracked as credit payment (account=${targetAccount?.name ?: "unknown"})")
                        return
                    }
                }
            }
            // Store this side as pending so the matching SMS (if it arrives within 10 min) can link them.
            storeInstapayPending(
                context, isOutgoing, ai.amount, smsId,
                targetAccount?.id ?: "",
                targetAccount?.name ?: "Account",
                targetAccount?.currency ?: "EGP"
            )
            // Fall through to normal record save below.
        }

        val txCurrency = inferCurrency(body)
        val isCreditCard = targetAccount?.accountType?.contains("Credit", ignoreCase = true) == true

        // HSBC always prints "available limit is EGP X" for EGP cards, even for foreign charges.
        // Use this to detect the card's TRUE home currency and auto-correct the account if the user
        // stored it as "Dollar" / "Euro" when it is actually an EGP card.
        val smsBalanceCurrency = extractBalanceCurrencyFromSms(body)
        if (isCreditCard && targetAccount != null && smsBalanceCurrency != null &&
            normaliseCurrency(targetAccount.currency) != smsBalanceCurrency) {
            targetAccount = targetAccount.copy(currency = smsBalanceCurrency)
        }

        val cardCurrency = normaliseCurrency(targetAccount?.currency ?: "EGP")
        // For credit cards, arithmetic is only valid when the transaction currency matches the card's
        // home currency. Foreign charges (USD/EUR/SAR on an EGP card) must use the EGP equivalent.
        val canCalculateBalance = !isCreditCard || txCurrency == cardCurrency

        val isIncome = ai.type == "Income"
        val previousBal = balanceBeforeSms.toDoubleOrNull() ?: targetAccount?.amount?.toDoubleOrNull() ?: 0.0
        val smsBalance = extractBalanceFromSms(body)

        // EGP equivalent for a foreign-currency charge on an EGP card.
        // Priority 1: balance drop (bank printed new EGP available limit in the SMS)
        // Priority 2: explicit inline text like "USD 50.00 (EGP 2,475.00)"
        val egpEquivalent: Double? = when {
            !canCalculateBalance && smsBalance != null -> {
                val impact = if (isIncome) smsBalance - previousBal else previousBal - smsBalance
                if (impact > 0.01) impact else null  // guard against stale/zero previousBal
            }
            !canCalculateBalance -> extractEgpEquivalent(body)
            else -> null
        }

        val balanceAfter = if (targetAccount != null) {
            when {
                smsBalance != null -> {
                    val formattedBalance = BalanceAmountFormatter.format(smsBalance)
                    repository.updateAccount(targetAccount.copy(amount = formattedBalance))
                    formattedBalance
                }
                canCalculateBalance -> {
                    val calculated = previousBal.let { if (isIncome) it + amountDouble else it - amountDouble }
                    val formattedBalance = BalanceAmountFormatter.format(calculated)
                    repository.updateAccount(targetAccount.copy(amount = formattedBalance))
                    formattedBalance
                }
                egpEquivalent != null -> {
                    val calculated = previousBal.let { if (isIncome) it + egpEquivalent else it - egpEquivalent }
                    val formattedBalance = BalanceAmountFormatter.format(calculated)
                    repository.updateAccount(targetAccount.copy(amount = formattedBalance))
                    formattedBalance
                }
                else -> ""
            }
        } else ""

        // When we know the EGP equivalent, record in EGP (the card's home currency) and note the
        // original foreign charge in the comment. This keeps all CC records consistently in EGP.
        val (finalAmount, finalCurrency) = if (egpEquivalent != null && !canCalculateBalance)
            "%.2f".format(egpEquivalent) to cardCurrency
        else
            ai.amount to txCurrency
        val conversionNote = if (egpEquivalent != null && !canCalculateBalance) "Charged $txCurrency ${ai.amount}" else null
        val finalComment = listOfNotNull(conversionNote, ai.comment.ifEmpty { null }).joinToString(" | ")

        val record = Record(
            amount = finalAmount, category = ai.category, type = ai.type,
            accountId = targetAccount?.id ?: "",
            accountName = targetAccount?.name ?: "Imported Card (${ai.last4Digits})",
            currency = finalCurrency,
            userId = userId, timestamp = date, smsId = smsId,
            comment = finalComment, balanceAfter = balanceAfter,
            balanceBefore = balanceBeforeSms.ifBlank {
                targetAccount?.amount?.toDoubleOrNull()?.let(BalanceAmountFormatter::format).orEmpty()
            }
        )
        repository.addRecord(record)
        sendRecordNotification(context, record)

        // Check budget thresholds for expense records
        if (ai.type == "Expense") {
            val amtDbl = finalAmount.toDoubleOrNull() ?: 0.0
            if (amtDbl > 0) {
                BudgetAlertHelper.checkBudgetAfterTransaction(context, repository, ai.category, amtDbl, finalCurrency)
            }
        }
    }

    private suspend fun saveStatement(context: Context, repository: WalletRepository, userId: String, smsId: String, ai: ExtractedTransaction, body: String = "", sender: String = "") {
        val dueDate = StatementDueDateParser.parse(ai.dueDate) ?: StatementDueDateParser.parse(extractDueDate(body))
        if (dueDate == null) {
            sendNotification(
                context,
                "Statement Needs Review",
                "The statement due date is missing or invalid. No statement reminders were scheduled.",
                true
            )
            return
        }

        val accounts = repository.getAccounts().first()
        val matchedAccount = matchAccount(accounts, ai.last4Digits?.filter { it.isDigit() } ?: "", body, sender)
        val statement = CreditStatement(
            cardLast4Digits = ai.last4Digits ?: "0000",
            accountId = matchedAccount?.id ?: "",
            totalAmount = ai.amount.toDoubleOrNull() ?: 0.0,
            dueDate = dueDate, userId = userId, smsId = smsId
        )
        repository.addCreditStatement(statement)
        ReminderManager.scheduleStatementReminders(context, statement)
        sendStatementNotification(context, statement)
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    /** Finds the account whose last-4-digits match [digits]. */
    private fun matchAccount(accounts: List<Account>, digits: String, body: String = "", sender: String = ""): Account? =
        SmsAccountMatcher.match(accounts, digits, body, sender)

    /** Finds a non-credit account whose digits appear in [smsBody]. */
    private fun findSourceAccount(accounts: List<Account>, smsBody: String, sender: String = ""): Account? {
        if (smsBody.isEmpty()) return null
        val debitAccounts = accounts.filter { !it.accountType.contains("Credit", ignoreCase = true) }
        val parsedSourceDigits = SmsCardPaymentDigits.parse(smsBody).sourceDigits
        if (!parsedSourceDigits.isNullOrBlank()) {
            return SmsAccountMatcher.match(debitAccounts, parsedSourceDigits, smsBody, sender)
        }
        val candidateDigits = debitAccounts.map { it.last4Digits.filter(Char::isDigit) }
            .filter { it.length >= 3 && smsBody.contains(it) }
            .distinct()
        return candidateDigits.singleOrNull()?.let { SmsAccountMatcher.match(debitAccounts, it, smsBody, sender) }
    }

    /** Fallback for source-less payment SMS, requiring one cent-accurate EGP balance match. */
    private fun findSourceAccountByBalance(accounts: List<Account>, smsBody: String, paymentAmt: Double): Account? {
        val smsBalance = extractBalanceFromSms(smsBody) ?: return null
        return SmsAccountMatcher.matchByPrintedBalance(accounts, paymentAmt, smsBalance)
    }

    /**
     * For a CardPayment SMS, the credit card digits may appear after "to credit card" or
     * "بطاقة" while the source account digits appear after "from account" / "حسابك".
     * Returns Pair(sourceDigits, creditCardDigits) — either may be null if not found.
     */
    private fun extractCardPaymentDigits(body: String): Pair<String?, String?> {
        val creditDigits = Regex(
            """(?:to\s+)?(?:credit\s+card|بطاقة(?:\s+ائتمانية?)?)\s*(?:ending\s+(?:with\s+)?)?\*{0,4}\s*(\d{3,4})\b""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)

        val sourceDigits = Regex(
            """(?:from\s+)?(?:account|a/c|acc\.?|حسابك?|حساب)\s*(?:no\.?\s*)?\*{0,4}\s*(\d{3,4})\b""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)

        return Pair(sourceDigits, creditDigits)
    }

    /** Marks the unpaid statement for [creditDigits] or [accountId] as paid and cancels its reminders. */
    private suspend fun markStatementPaid(
        repository: WalletRepository, context: Context,
        creditDigits: String, accountId: String = ""
    ) {
        // Use a one-shot .get() fetch (not a snapshot listener) so we always read fresh data
        val statements = repository.getUnpaidStatementsOnce()
        Log.d("SmsReceiver", "markStatementPaid: creditDigits='$creditDigits' accountId='$accountId' unpaidCount=${statements.size}")
        statements.forEach { s ->
            Log.d("SmsReceiver", "  unpaid: id=${s.id} cardLast4=${s.cardLast4Digits} accountId=${s.accountId} amount=${s.totalAmount}")
        }
        val unpaid = statements.find { statement ->
            // Prefer matching by accountId (most reliable)
            if (accountId.isNotEmpty() && statement.accountId.isNotEmpty()) {
                return@find statement.accountId == accountId
            }
            // Fallback: fuzzy digit match
            val sd = statement.cardLast4Digits.filter { it.isDigit() }
            sd.isNotEmpty() && creditDigits.isNotEmpty() &&
                (sd == creditDigits || creditDigits.endsWith(sd) || sd.endsWith(creditDigits))
        }
        if (unpaid != null) {
            Log.d("SmsReceiver", "Marking statement paid (deleting): id=${unpaid.id} card=${unpaid.cardLast4Digits}")
            // Delete matches the manual-pay path in HomeViewModel and avoids Firestore rules
            // that may block updates to creditStatements documents.
            repository.deleteCreditStatement(unpaid.id)
            Log.d("SmsReceiver", "deleteCreditStatement returned for id=${unpaid.id}")
            ReminderManager.cancelReminders(context, unpaid.smsId)
            sendNotification(
                context,
                "Credit Card Statement Paid",
                "Card ****${unpaid.cardLast4Digits} — statement of ${unpaid.totalAmount.toLong()} EGP marked as paid",
                false
            )
        } else {
            Log.w("SmsReceiver", "markStatementPaid: no unpaid statement found for digits='$creditDigits' accountId='$accountId'")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Classification
    // ──────────────────────────────────────────────────────────────

    private fun inferCurrency(body: String) = SmsParser.inferCurrency(body)

    private fun normaliseCurrency(currency: String) = FinancialCalculator.normaliseCurrency(currency)

    private fun isDeclinedTransaction(body: String) = SmsParser.isDeclinedTransaction(body)

    private fun isBankSms(body: String, sender: String = "") = SmsParser.isBankSms(body, sender)
    private fun inferType(body: String) = SmsParser.inferType(body)
    private fun inferCategory(body: String) = SmsParser.inferCategory(body)
    private fun inferComment(body: String) = SmsParser.inferComment(body)
    private fun extractAmount(body: String) = SmsParser.extractAmount(body)
    private fun extractLast4Digits(body: String) = SmsParser.extractLast4Digits(body)
    private fun extractBalanceFromSms(body: String) = SmsParser.extractBalanceFromSms(body)

    private fun extractDueDate(body: String): String? {
        val regex = Regex("""(?:Due Date|due before)\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1)
    }

    /**
     * Returns the currency code of the available balance printed in the SMS
     * (e.g. "available limit is EGP 82,829" → "EGP", "available balance EUR 8.64" → "EUR").
     * This is the card's TRUE home currency — more reliable than whatever the user stored.
     */
    private fun extractBalanceCurrencyFromSms(body: String): String? =
        Regex(
            """avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)\s*(?:[:\-]|is)?\s*(EGP|USD|EUR|GBP|SAR|AED)""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)?.uppercase()

    /**
     * When an EGP credit card is charged in a foreign currency, many Egyptian banks include the
     * EGP equivalent in the SMS alongside the foreign amount, e.g.:
     *   "charged USD 50.00 (EGP 2,475.00)"
     *   "EGP equiv 2,475"
     *   "EGP amount: 4,950"
     *   "converted to EGP: 2,475"
     * This is distinct from the running balance printed by extractBalanceFromSms.
     */
    private fun extractEgpEquivalent(body: String): Double? {
        val num = """([\d,]+(?:\.\d{1,2})?)"""
        return Regex(
            """(?:\(\s*EGP|EGP\s+(?:amount|equiv(?:alent)?)\s*:?|equiv(?:alent)?\.?\s*EGP|converted\s+to\s+EGP\s*:?)\s*$num""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }

    // ──────────────────────────────────────────────────────────────
    // Notifications
    // ──────────────────────────────────────────────────────────────

    private fun sendRecordNotification(context: Context, record: Record) {
        val title = if (record.accountId.isEmpty()) "Action Required: Match Account" else "Transaction Added Automatically"
        val prefix = if (record.type == "Income") "+" else "-"
        sendNotification(context, title, "${record.category}: $prefix${record.amount} ${record.currency}", true)
    }

    private fun sendStatementNotification(context: Context, statement: CreditStatement) {
        val dateStr = SimpleDateFormat("dd MMM", Locale.getDefault()).format(statement.dueDate)
        sendNotification(context, "Credit Card Bill Issued",
            "Card ****${statement.cardLast4Digits}: ${statement.totalAmount} EGP due by $dateStr", false)
    }

    private fun sendNotification(context: Context, title: String, text: String, goToRecords: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(channelId, "Transaction Alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w("SmsReceiver", "Notification not posted because app notifications are disabled: title=$title")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("SmsReceiver", "Notification not posted because POST_NOTIFICATIONS is not granted: title=$title")
            return
        }
        try {
            nm.notify(System.currentTimeMillis().toInt(), buildNotification(context, title, text, goToRecords))
            Log.i("SmsReceiver", "Notification posted: title=$title")
        } catch (error: SecurityException) {
            Log.w("SmsReceiver", "Notification post denied by system: title=$title", error)
        }
    }

    private suspend fun markStatementPaidIfUnambiguous(
        repository: WalletRepository,
        context: Context,
        accounts: List<Account>,
        creditDigits: String,
        creditAccount: Account?
    ) {
        val creditCards = accounts.filter { it.accountType.contains("Credit", ignoreCase = true) }
        if (creditAccount == null && SmsAccountMatcher.hasAmbiguousMatch(creditCards, creditDigits)) {
            Log.w("SmsReceiver", "Skipping statement auto-payment for ambiguous card suffix '$creditDigits'")
            return
        }
        markStatementPaid(repository, context, creditDigits, creditAccount?.id.orEmpty())
    }

    internal fun buildNotification(context: Context, title: String, text: String, goToRecords: Boolean): Notification {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (goToRecords) {
                action = "com.example.wallettrackers.OPEN_RECORDS"
                data = android.net.Uri.parse("wallettrackers://records")
                putExtra("navigate_to", "all_records")
            }
        }
        val pi = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(context, channelId)
                .setSmallIcon(com.example.wallettrackers.R.drawable.ic_stat_wallet)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi).setAutoCancel(true).build()
    }
}
