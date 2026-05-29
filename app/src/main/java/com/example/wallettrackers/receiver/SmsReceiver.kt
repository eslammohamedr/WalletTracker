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
import com.example.wallettrackers.BuildConfig
import com.example.wallettrackers.MainActivity
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.service.AiService
import com.example.wallettrackers.service.ExtractedTransaction
import com.example.wallettrackers.util.BudgetAlertHelper
import com.example.wallettrackers.util.FinancialCalculator
import com.example.wallettrackers.util.ReminderManager
import com.example.wallettrackers.util.SmsParser
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SmsReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val channelId = "transaction_alerts"
    private val aiService = AiService(
        groqApiKey     = BuildConfig.GROQ_API_KEY,
        cerebrasApiKey = BuildConfig.CEREBRAS_API_KEY,
        geminiApiKey   = BuildConfig.GEMINI_API_KEY
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val pendingResult = goAsync()
            scope.launch {
                try {
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    if (currentUser != null) {
                        for (sms in messages) {
                            processSms(context, currentUser.uid, sms.displayMessageBody,
                                sms.timestampMillis.toString(), Date(sms.timestampMillis),
                                sms.displayOriginatingAddress ?: "")
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
        if (isNonBankSender(sender)) {
            Log.d("SmsReceiver", "Skipping SMS from non-bank sender: $sender")
            return
        }

        val repository = FirebaseRepository(userId)

        // Always keep account balance current if the bank prints a balance in this SMS
        val smsBalance = extractBalanceFromSms(body)
        if (smsBalance != null) {
            val digits = extractLast4Digits(body)?.filter { it.isDigit() } ?: ""
            if (digits.isNotEmpty()) {
                val accounts = repository.getAccounts().first()
                val account = matchAccount(accounts, digits)
                if (account != null) {
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
                        repository.updateAccount(account.copy(amount = smsBalance.toString()))
                    }
                }
            }
        }

        if (repository.recordWithSmsIdExists(smsId) || repository.statementWithSmsIdExists(smsId)) {
            Log.d("SmsReceiver", "SMS already processed, skipping: $smsId")
            return
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
            val final = applyRules(tx)
            when (final.type) {
                "Statement"          -> saveStatement(context, repository, userId, smsId, final)
                "CardPayment"        -> saveCardPayment(context, repository, userId, smsId, date, final, body)
                "CreditCardReceived" -> saveCreditCardReceived(context, repository, userId, smsId, date, final, body)
                "AtmWithdrawal"      -> saveAtmWithdrawal(context, repository, userId, smsId, date, final, body)
                else                 -> saveRecord(context, repository, userId, smsId, date, final, body)
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
                        // Keyword-based category first — if it returns a specific category, trust it
                        val keywordCategory = inferCategory(body)
                        val genericCategories = setOf("Transfer", "Other")
                        if (keywordCategory !in genericCategories) {
                            keywordCategory
                        } else {
                            // Only use AI when keywords give a generic result
                            try {
                                aiService.inferCategory(body) ?: keywordCategory
                            } catch (e: Exception) {
                                Log.w("SmsReceiver", "AI inferCategory failed: ${e.message}")
                                keywordCategory
                            }
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
        if (SmsParser.isNonBankSms(body) || SmsParser.isPromotionalSms(body)) {
            Log.d("SmsReceiver", "Skipping AI fallback — non-bank/promo SMS body")
            return
        }
        try {
            val result = aiService.analyzeSms(body)
            if (result != null && result.isBankRelated) save(result)
        } catch (e: Exception) {
            Log.e("SmsReceiver", "AI full extraction failed", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Dual-SMS credit card payment handlers
    // ──────────────────────────────────────────────────────────────

    // SharedPreferences key scheme for pending credit payments:
    //   key   = "cc_pending_<amount>"  (e.g. "cc_pending_10000")
    //   value = "<creditSmsId>|<creditDigits>|<epochMillis>"
    private data class PendingCreditPayment(val creditSmsId: String, val creditDigits: String)

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

    private fun consumeInstapayPending(context: Context, isOutgoing: Boolean, amount: String): PendingInstapay? {
        val lookPrefix = if (isOutgoing) "ip_in_" else "ip_out_"
        val amountDouble = amount.toDoubleOrNull() ?: return null
        val prefs = context.getSharedPreferences("pending_instapay", Context.MODE_PRIVATE)
        val match = prefs.all.entries
            .filter { it.key.startsWith(lookPrefix) }
            .mapNotNull { entry ->
                val storedAmt = entry.key.removePrefix(lookPrefix).toDoubleOrNull() ?: return@mapNotNull null
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

    private fun storePendingPayment(context: Context, amount: String, creditDigits: String, creditSmsId: String) {
        context.getSharedPreferences("pending_cc", Context.MODE_PRIVATE)
            .edit()
            .putString("cc_pending_$amount", "$creditSmsId|$creditDigits|${System.currentTimeMillis()}")
            .apply()
    }

    private fun consumePendingPayment(context: Context, amount: String): PendingCreditPayment? {
        val prefs = context.getSharedPreferences("pending_cc", Context.MODE_PRIVATE)
        val amountDouble = amount.toDoubleOrNull() ?: return null

        // Scan all pending entries; match by amount within 100 EGP or 5% (handles Instapay fees)
        val matchingEntry = prefs.all.entries
            .filter { it.key.startsWith("cc_pending_") }
            .mapNotNull { entry ->
                val storedAmt = entry.key.removePrefix("cc_pending_").toDoubleOrNull() ?: return@mapNotNull null
                val diff = kotlin.math.abs(storedAmt - amountDouble)
                if (diff <= 100.0 || diff / maxOf(amountDouble, storedAmt) <= 0.05) entry to diff else null
            }
            .minByOrNull { it.second }
            ?.first
            ?: return null

        val raw = matchingEntry.value as? String ?: return null
        val parts = raw.split("|")
        if (parts.size < 3) { prefs.edit().remove(matchingEntry.key).apply(); return null }
        val timestamp = parts[2].toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - timestamp > 48 * 3600_000L) {
            prefs.edit().remove(matchingEntry.key).apply()
            return null
        }
        prefs.edit().remove(matchingEntry.key).apply()
        return PendingCreditPayment(creditSmsId = parts[0], creditDigits = parts[1])
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
        context: Context, repository: FirebaseRepository, userId: String,
        smsId: String, date: Date, ai: ExtractedTransaction, smsBody: String = ""
    ) {
        val accounts = repository.getAccounts().first()
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0

        // Extract source and credit card digits separately so we don't confuse the two.
        // extractLast4Digits() can return SOURCE account digits for SMS like "from ****5678 to credit card 1234".
        val (extractedSourceDigits, extractedCreditDigits) = extractCardPaymentDigits(smsBody)
        val creditDigits = extractedCreditDigits
            ?: ai.last4Digits?.filter { it.isDigit() }
            ?: ""
        val creditAccount = matchAccount(accounts, creditDigits)
        Log.d("SmsReceiver", "saveCardPayment: extractedCreditDigits='$extractedCreditDigits' ai.last4Digits='${ai.last4Digits}' creditDigits='$creditDigits' creditAccount=${creditAccount?.name} creditAccountId=${creditAccount?.id}")

        markStatementPaid(repository, context, creditDigits, creditAccount?.id ?: "")

        // Pending path: credit-side SMS arrived first and stored a flag.
        // CC balance is already restored — just handle the debit side.
        val pending = consumePendingPayment(context, ai.amount)
        if (pending != null) {
            val sourceAccount = findSourceAccount(accounts, smsBody)
                ?: extractedSourceDigits?.let { digits -> accounts.filter { a -> !a.accountType.contains("Credit", ignoreCase = true) }.find { a -> val ad = a.last4Digits.filter { c -> c.isDigit() }; ad.isNotEmpty() && (ad == digits || digits.endsWith(ad) || ad.endsWith(digits)) } }
                ?: findSourceAccountByBalance(accounts, smsBody, paymentAmt)
            if (sourceAccount != null) {
                val calculated = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - paymentAmt
                val finalDebitBal = extractBalanceFromSms(smsBody) ?: calculated
                repository.updateAccount(sourceAccount.copy(amount = finalDebitBal.toString()))
                val partialRecord = repository.findRecentCardPaymentRecord(ai.amount)
                if (partialRecord != null && !partialRecord.accountName.contains("->")) {
                    repository.updateRecord(partialRecord.copy(
                        accountId = sourceAccount.id,
                        accountName = "${sourceAccount.name} -> ${partialRecord.accountName}",
                        balanceAfter = finalDebitBal.toString(),
                        smsId = smsId
                    ))
                } else {
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = sourceAccount.id,
                        accountName = "${sourceAccount.name} -> ${creditAccount?.name ?: "Credit Card ****$creditDigits"}",
                        currency = sourceAccount.currency, userId = userId, timestamp = date,
                        smsId = smsId, balanceAfter = finalDebitBal.toString(), comment = ai.comment
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
                        amount = ((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt).toString()
                    ))
                }
                val sourceAccount = findSourceAccount(accounts, smsBody)
                    ?: extractedSourceDigits?.let { digits -> accounts.filter { a -> !a.accountType.contains("Credit", ignoreCase = true) }.find { a -> val ad = a.last4Digits.filter { c -> c.isDigit() }; ad.isNotEmpty() && (ad == digits || digits.endsWith(ad) || ad.endsWith(digits)) } }
                    ?: findSourceAccountByBalance(accounts, smsBody, paymentAmt)
                if (sourceAccount != null) {
                    val calculated = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - paymentAmt
                    val finalDebitBal = extractBalanceFromSms(smsBody) ?: calculated
                    repository.updateAccount(sourceAccount.copy(amount = finalDebitBal.toString()))
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = sourceAccount.id,
                        accountName = "${sourceAccount.name} -> ${creditAccount?.name ?: "Credit Card ****$creditDigits"}",
                        currency = sourceAccount.currency, userId = userId, timestamp = date,
                        smsId = smsId, balanceAfter = finalDebitBal.toString(), comment = ai.comment
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
        context: Context, repository: FirebaseRepository, userId: String,
        smsId: String, date: Date, ai: ExtractedTransaction, body: String = ""
    ) {
        val accounts = repository.getAccounts().first()
        val creditDigits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0
        val creditAccount = matchAccount(accounts, creditDigits)

        markStatementPaid(repository, context, creditDigits, creditAccount?.id ?: "")

        // Check if the debit-side was already saved (as CardPayment or as a regular Expense/Instapay)
        val existingRecord = repository.findRecentCardPaymentRecord(ai.amount)
            ?: repository.findRecentDebitExpenseRecord(ai.amount)

        if (existingRecord != null) {
            if (existingRecord.accountName.contains("->")) {
                // Already a complete transfer — pure duplicate, skip
                sendNotification(context, "Credit Card Payment Confirmed",
                    "Payment of ${ai.amount} confirmed for card ****$creditDigits", false)
                return
            }
            // Debit expense record exists but hasn't been upgraded to a transfer yet.
            // Restore CC balance and upgrade the record to "DebitAccount -> CreditCard".
            if (creditAccount != null) {
                val calculated = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt
                val finalBal = extractBalanceFromSms(body) ?: calculated
                repository.updateAccount(creditAccount.copy(amount = finalBal.toString()))
            }
            repository.updateRecord(existingRecord.copy(
                category = "Credit Payment",
                accountName = if (existingRecord.accountName.isNotBlank())
                    "${existingRecord.accountName} -> ${creditAccount?.name ?: "Credit Card ****$creditDigits"}"
                else
                    creditAccount?.name ?: "Credit Card ****$creditDigits",
                smsId = smsId
            ))
            markStatementPaid(repository, context, creditDigits, creditAccount?.id ?: "")
            sendNotification(context, "Credit Card Payment Linked",
                "${existingRecord.accountName} → card ****$creditDigits: ${ai.amount}", true)
            return
        }

        // Credit arrives first: restore CC balance, create partial record, store pending flag.
        // Do NOT touch any debit account — the debit SMS will do that when it arrives.
        if (creditAccount != null) {
            val calculated = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt
            val finalBal = extractBalanceFromSms(body) ?: calculated
            repository.updateAccount(creditAccount.copy(amount = finalBal.toString()))
        }
        storePendingPayment(context, ai.amount, creditDigits, smsId)
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

    private suspend fun saveAtmWithdrawal(context: Context, repository: FirebaseRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction, body: String = "") {
        val accounts = repository.getAccounts().first()
        val sourceAccount = matchAccount(accounts, ai.last4Digits?.filter { it.isDigit() } ?: "")
        val amount = ai.amount.toDoubleOrNull() ?: 0.0

        // Deduct from the source bank account if identified
        var finalSourceBal = 0.0
        if (sourceAccount != null) {
            val calculatedSourceBal = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - amount
            finalSourceBal = extractBalanceFromSms(body) ?: calculatedSourceBal
            repository.updateAccount(sourceAccount.copy(amount = finalSourceBal.toString()))
        }

        // Always credit cash — ATM withdrawal always puts money in the user's pocket
        val cashAccount = accounts.find { it.accountType.equals("Cash", ignoreCase = true) }
        if (cashAccount != null) {
            val newCashBal = (cashAccount.amount.toDoubleOrNull() ?: 0.0) + amount
            repository.updateAccount(cashAccount.copy(amount = newCashBal.toString()))
        }

        val sourceName = sourceAccount?.name ?: "Bank"
        repository.addRecord(Record(
            amount = ai.amount, category = "Transfer", type = "Expense",
            accountId = sourceAccount?.id ?: "",
            accountName = if (cashAccount != null) "$sourceName -> Cash" else sourceName,
            currency = sourceAccount?.currency ?: "EGP", userId = userId, timestamp = date,
            smsId = smsId, comment = "ATM Withdrawal",
            balanceAfter = if (sourceAccount != null) finalSourceBal.toString() else ""
        ))
        val notifDetail = if (sourceAccount != null) "Deducted ${ai.amount} from ${sourceAccount.name}" else "ATM Withdrawal of ${ai.amount}"
        sendNotification(context, "ATM Withdrawal Tracked",
            "$notifDetail${if (cashAccount != null) " and added to Cash" else ""}.", true)
    }

    private suspend fun saveRecord(context: Context, repository: FirebaseRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction, body: String = "") {
        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        var targetAccount = matchAccount(accounts, digits)

        if (targetAccount == null && ai.category == "Salary") {
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

            val pending = consumePendingPayment(context, ai.amount)
            if (pending != null) {
                val calculated = (targetAccount.amount.toDoubleOrNull() ?: 0.0) - amountDouble
                val finalBal = extractBalanceFromSms(body) ?: calculated
                repository.updateAccount(targetAccount.copy(amount = finalBal.toString()))
                val partialRecord = repository.findRecentCardPaymentRecord(ai.amount)
                if (partialRecord != null && !partialRecord.accountName.contains("->")) {
                    repository.updateRecord(partialRecord.copy(
                        accountId = targetAccount.id,
                        accountName = "${targetAccount.name} -> ${partialRecord.accountName}",
                        balanceAfter = finalBal.toString(),
                        smsId = smsId
                    ))
                } else {
                    repository.addRecord(Record(
                        amount = ai.amount, category = "Credit Payment", type = "Expense",
                        accountId = targetAccount.id,
                        accountName = "${targetAccount.name} -> Credit Card ****${pending.creditDigits}",
                        currency = targetAccount.currency, userId = userId, timestamp = date,
                        smsId = smsId, balanceAfter = finalBal.toString(), comment = ai.comment
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
            val matchingPending = consumeInstapayPending(context, isOutgoing, ai.amount)
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
                        repository.updateAccount(targetAccount.copy(amount = final.toString()))
                    }
                } else {
                    destName = targetAccount?.name ?: "Account"
                    sourceName = matchingPending.accountName
                    sourceId = matchingPending.accountId
                    sourceCurrency = matchingPending.currency
                    if (targetAccount != null) {
                        val calc = (targetAccount.amount.toDoubleOrNull() ?: 0.0) + amountDouble
                        val final = extractBalanceFromSms(body) ?: calc
                        repository.updateAccount(targetAccount.copy(amount = final.toString()))
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
        val previousBal = targetAccount?.amount?.toDoubleOrNull() ?: 0.0
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
                    repository.updateAccount(targetAccount.copy(amount = smsBalance.toString()))
                    smsBalance.toString()
                }
                canCalculateBalance -> {
                    val calculated = previousBal.let { if (isIncome) it + amountDouble else it - amountDouble }
                    repository.updateAccount(targetAccount.copy(amount = calculated.toString()))
                    calculated.toString()
                }
                egpEquivalent != null -> {
                    val calculated = previousBal.let { if (isIncome) it + egpEquivalent else it - egpEquivalent }
                    repository.updateAccount(targetAccount.copy(amount = calculated.toString()))
                    calculated.toString()
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
            comment = finalComment, balanceAfter = balanceAfter
        )
        repository.addRecord(record)
        sendRecordNotification(context, record)

        // Check budget thresholds for expense records
        if (ai.type == "Expense") {
            val amtDbl = finalAmount.toDoubleOrNull() ?: 0.0
            if (amtDbl > 0) {
                BudgetAlertHelper.checkBudgetAfterTransaction(context, repository, ai.category, amtDbl)
            }
        }
    }

    private suspend fun saveStatement(context: Context, repository: FirebaseRepository, userId: String, smsId: String, ai: ExtractedTransaction) {
        val dueDate = try {
            ai.dueDate?.let {
                SimpleDateFormat(if (it.contains("/")) "dd/MM/yyyy" else "dd-MM-yyyy", Locale.getDefault()).parse(it)
            } ?: Date()
        } catch (e: Exception) { Date() }

        val accounts = repository.getAccounts().first()
        val matchedAccount = matchAccount(accounts, ai.last4Digits?.filter { it.isDigit() } ?: "")
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
    private fun matchAccount(accounts: List<Account>, digits: String): Account? {
        if (digits.isEmpty()) return null
        return accounts.find { acc ->
            val ad = acc.last4Digits.filter { it.isDigit() }
            ad.isNotEmpty() && (ad == digits || digits.endsWith(ad) || ad.endsWith(digits))
        }
    }

    /** Finds a non-credit account whose digits appear in [smsBody]. */
    private fun findSourceAccount(accounts: List<Account>, smsBody: String): Account? {
        if (smsBody.isEmpty()) return null
        return accounts.filter { !it.accountType.contains("Credit", ignoreCase = true) }
            .find { acc ->
                val ad = acc.last4Digits.filter { it.isDigit() }
                ad.length >= 3 && smsBody.contains(ad)
            }
    }

    /**
     * Fallback: when the payment SMS doesn't include source account digits,
     * match by balance — the account whose balance minus the payment amount
     * equals the SMS balance (within 5% or EGP 500).
     */
    private fun findSourceAccountByBalance(accounts: List<Account>, smsBody: String, paymentAmt: Double): Account? {
        val smsBalance = extractBalanceFromSms(smsBody) ?: return null
        return accounts
            .filter { !it.accountType.contains("Credit", ignoreCase = true) }
            .filter { it.currency.equals("EGP", ignoreCase = true) }
            .minByOrNull { acc ->
                val bal = acc.amount.toDoubleOrNull() ?: return@minByOrNull Double.MAX_VALUE
                kotlin.math.abs((bal - paymentAmt) - smsBalance)
            }
            ?.takeIf { acc ->
                val bal = acc.amount.toDoubleOrNull() ?: return@takeIf false
                val diff = kotlin.math.abs((bal - paymentAmt) - smsBalance)
                diff < 500.0 || diff / maxOf(bal, 1.0) < 0.05
            }
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
        repository: FirebaseRepository, context: Context,
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
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (goToRecords) putExtra("navigate_to", "all_records")
        }
        val pi = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi).setAutoCancel(true).build())
    }
}
