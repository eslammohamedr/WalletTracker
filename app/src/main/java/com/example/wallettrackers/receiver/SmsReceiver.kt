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
import com.example.wallettrackers.util.ReminderManager
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
    private val CHANNEL_ID = "transaction_alerts"
    private val aiService = AiService(BuildConfig.GEMINI_API_KEY)

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
                                sms.timestampMillis.toString(), Date(sms.timestampMillis))
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

    private suspend fun processSms(context: Context, userId: String, body: String, smsId: String, date: Date) {
        val repository = FirebaseRepository(userId)

        if (repository.recordWithSmsIdExists(smsId) || repository.statementWithSmsIdExists(smsId)) {
            Log.d("SmsReceiver", "SMS already processed, skipping: $smsId")
            return
        }

        if (isBankSms(body)) {
            val amount = extractAmount(body)
            val type = inferType(body)

            if (amount != null) {
                val comment = inferComment(body)
                val digits = extractLast4Digits(body)
                val tx = ExtractedTransaction(
                    amount = amount, category = if (type == "Statement") "Credit Card" else inferCategory(body),
                    type = type, isBankRelated = true, last4Digits = digits,
                    isStatement = type == "Statement", dueDate = extractDueDate(body),
                    comment = comment ?: ""
                )
                when (type) {
                    "Statement"            -> { saveStatement(context, repository, userId, smsId, tx); return }
                    "AtmWithdrawal"        -> { saveAtmWithdrawal(context, repository, userId, smsId, date, tx, body); return }
                    "CardPayment"          -> { saveCardPayment(context, repository, userId, smsId, date, tx, body); return }
                    "CreditCardReceived"   -> { saveCreditCardReceived(context, repository, userId, smsId, date, tx, body); return }
                    else -> {
                        val accounts = repository.getAccounts().first()
                        val d = digits?.filter { it.isDigit() } ?: ""
                        val matched = accounts.find { acc ->
                            val ad = acc.last4Digits.filter { it.isDigit() }
                            ad.isNotEmpty() && d.isNotEmpty() && (ad == d || d.endsWith(ad) || ad.endsWith(d))
                        }
                        if (matched != null || tx.category == "Salary") { saveRecord(context, repository, userId, smsId, date, tx, body); return }
                    }
                }
            }
        }

        // AI fallback
        try {
            val result = aiService.analyzeSms(body)
            if (result != null && result.isBankRelated) {
                when (result.type) {
                    "Statement"          -> saveStatement(context, repository, userId, smsId, result)
                    "CardPayment"        -> saveCardPayment(context, repository, userId, smsId, date, result, body)
                    "CreditCardReceived" -> saveCreditCardReceived(context, repository, userId, smsId, date, result, body)
                    "AtmWithdrawal"      -> saveAtmWithdrawal(context, repository, userId, smsId, date, result, body)
                    else                 -> saveRecord(context, repository, userId, smsId, date, result, body)
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "AI fallback failed", e)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Dual-SMS credit card payment handlers
    // ──────────────────────────────────────────────────────────────

    /**
     * Handles the DEBIT-SIDE SMS ("your account was debited for credit card payment").
     *
     * Scenario A — no prior record (normal / debit arrives first):
     *   • Deduct from debit account
     *   • Restore credit card available balance
     *   • Create a full record: "DebitAcc -> CreditCard"
     *   • Mark statement paid
     *
     * Scenario B — credit-side record already exists (credit SMS arrived first):
     *   • Credit card was already restored; only deduct from debit account
     *   • Upgrade the existing partial record to a full "Debit -> Credit" record
     *   • No duplicate record created
     *
     * Scenario C — full record already exists (duplicate debit SMS):
     *   • Skip everything
     */
    private suspend fun saveCardPayment(
        context: Context, repository: FirebaseRepository, userId: String,
        smsId: String, date: Date, ai: ExtractedTransaction, smsBody: String = ""
    ) {
        val accounts = repository.getAccounts().first()
        val creditDigits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0

        val creditAccount = matchAccount(accounts, creditDigits)

        // Mark statement paid regardless of dedup result
        markStatementPaid(repository, context, creditDigits)

        // Check for a record created by the credit-side SMS (arrived earlier)
        val existingRecord = repository.findRecentCardPaymentRecord(ai.amount)

        when {
            // Scenario C: full record exists (has "->") — duplicate SMS, do nothing
            existingRecord != null && existingRecord.accountName.contains("->") -> {
                Log.d("SmsReceiver", "Duplicate debit SMS for credit payment, skipping")
            }

            // Scenario B: partial credit-only record exists — complete it by adding debit side
            existingRecord != null -> {
                val sourceAccount = findSourceAccount(accounts, smsBody)
                if (sourceAccount != null) {
                    val calculated = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - paymentAmt
                    val finalDebitBal = extractBalanceFromSms(smsBody) ?: calculated
                    repository.updateAccount(sourceAccount.copy(amount = finalDebitBal.toString()))
                    repository.updateRecord(existingRecord.copy(
                        accountId = sourceAccount.id,
                        accountName = "${sourceAccount.name} -> ${existingRecord.accountName}",
                        balanceAfter = finalDebitBal.toString(),
                        smsId = smsId
                    ))
                    sendNotification(context, "Credit Card Payment Complete",
                        "${sourceAccount.name} paid ${ai.amount} to card ****$creditDigits", true)
                }
            }

            // Scenario A: no prior record — full operation
            else -> {
                if (creditAccount != null) {
                    repository.updateAccount(creditAccount.copy(
                        amount = ((creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt).toString()
                    ))
                }
                val sourceAccount = findSourceAccount(accounts, smsBody)
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
                        currency = creditAccount?.currency ?: "EGP", userId = userId, timestamp = date,
                        smsId = smsId, balanceAfter = "", comment = ai.comment
                    ))
                }
                sendNotification(context, "Credit Card Payment Tracked",
                    "Payment of ${ai.amount} to card ****$creditDigits", true)
            }
        }
    }

    /**
     * Handles the CREDIT-SIDE SMS ("payment received by your credit card").
     *
     * Scenario A — no prior record (credit SMS arrives first or bank sends only one SMS):
     *   • Restore credit card available balance
     *   • Create a partial record (credit card name only, no "->")
     *   • Mark statement paid
     *   • When debit SMS later arrives, saveCardPayment() will complete the record (Scenario B above)
     *
     * Scenario B — debit-side record already exists (normal case, debit arrived first):
     *   • Credit card was already restored by saveCardPayment; don't touch balances
     *   • Send a "payment confirmed" notification only
     */
    private suspend fun saveCreditCardReceived(
        context: Context, repository: FirebaseRepository, userId: String,
        smsId: String, date: Date, ai: ExtractedTransaction, body: String = ""
    ) {
        val accounts = repository.getAccounts().first()
        val creditDigits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        val paymentAmt = ai.amount.toDoubleOrNull() ?: 0.0

        val creditAccount = matchAccount(accounts, creditDigits)

        markStatementPaid(repository, context, creditDigits)

        val existingRecord = repository.findRecentCardPaymentRecord(ai.amount)

        if (existingRecord != null) {
            sendNotification(context, "Credit Card Payment Confirmed",
                "Payment of ${ai.amount} confirmed for card ****$creditDigits", false)
        } else {
            if (creditAccount != null) {
                val calculated = (creditAccount.amount.toDoubleOrNull() ?: 0.0) + paymentAmt
                val finalBal = extractBalanceFromSms(body) ?: calculated
                repository.updateAccount(creditAccount.copy(amount = finalBal.toString()))
            }
            repository.addRecord(Record(
                amount = ai.amount, category = "Credit Payment", type = "Expense",
                accountId = creditAccount?.id ?: "",
                accountName = creditAccount?.name ?: "Credit Card ****$creditDigits",
                currency = creditAccount?.currency ?: "EGP", userId = userId, timestamp = date,
                smsId = smsId, balanceAfter = "", comment = ai.comment
            ))
            sendNotification(context, "Credit Card Payment Received",
                "Card ****$creditDigits received payment of ${ai.amount}", false)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Other save functions
    // ──────────────────────────────────────────────────────────────

    private suspend fun saveAtmWithdrawal(context: Context, repository: FirebaseRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction, body: String = "") {
        val accounts = repository.getAccounts().first()
        val sourceAccount = matchAccount(accounts, ai.last4Digits?.filter { it.isDigit() } ?: "")
        val cashAccount = accounts.find { it.accountType.equals("Cash", ignoreCase = true) }

        if (sourceAccount != null && cashAccount != null) {
            val amount = ai.amount.toDoubleOrNull() ?: 0.0
            val calculatedSourceBal = (sourceAccount.amount.toDoubleOrNull() ?: 0.0) - amount
            val finalSourceBal = extractBalanceFromSms(body) ?: calculatedSourceBal
            val newCashBal = (cashAccount.amount.toDoubleOrNull() ?: 0.0) + amount
            repository.updateAccount(sourceAccount.copy(amount = finalSourceBal.toString()))
            repository.updateAccount(cashAccount.copy(amount = newCashBal.toString()))
            repository.addRecord(Record(
                amount = ai.amount, category = "Others", type = "Expense",
                accountId = sourceAccount.id, accountName = "${sourceAccount.name} -> Cash",
                currency = sourceAccount.currency, userId = userId, timestamp = date,
                smsId = smsId, comment = "ATM Withdrawal", balanceAfter = finalSourceBal.toString()
            ))
            sendNotification(context, "ATM Withdrawal Tracked",
                "Deducted ${ai.amount} from ${sourceAccount.name} and added to Cash.", true)
        }
    }

    private suspend fun saveRecord(context: Context, repository: FirebaseRepository, userId: String, smsId: String, date: Date, ai: ExtractedTransaction, body: String = "") {
        val accounts = repository.getAccounts().first()
        val digits = ai.last4Digits?.filter { it.isDigit() } ?: ""
        var targetAccount = matchAccount(accounts, digits)

        if (targetAccount == null && ai.category == "Salary") {
            targetAccount = accounts.maxByOrNull { it.amount.toDoubleOrNull() ?: 0.0 }
        }

        val amountDouble = ai.amount.toDoubleOrNull() ?: 0.0
        val isIncome = ai.type == "Income"
        val balanceAfter = if (targetAccount != null) {
            val calculated = (targetAccount.amount.toDoubleOrNull() ?: 0.0)
                .let { if (isIncome) it + amountDouble else it - amountDouble }
            val finalBal = extractBalanceFromSms(body) ?: calculated
            repository.updateAccount(targetAccount.copy(amount = finalBal.toString()))
            finalBal.toString()
        } else ""

        val record = Record(
            amount = ai.amount, category = ai.category, type = ai.type,
            accountId = targetAccount?.id ?: "",
            accountName = targetAccount?.name ?: "Imported Card (${ai.last4Digits})",
            currency = targetAccount?.currency ?: "EGP",
            userId = userId, timestamp = date, smsId = smsId,
            comment = ai.comment, balanceAfter = balanceAfter
        )
        repository.addRecord(record)
        sendRecordNotification(context, record)
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

    /** Marks the unpaid statement for [creditDigits] as paid and cancels its reminders. */
    private suspend fun markStatementPaid(repository: FirebaseRepository, context: Context, creditDigits: String) {
        val statements = repository.getCreditStatements().first()
        val unpaid = statements.find { it.cardLast4Digits == creditDigits && !it.isPaid }
        if (unpaid != null) {
            repository.updateCreditStatement(unpaid.copy(isPaid = true))
            ReminderManager.cancelReminders(context, unpaid.smsId)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Classification
    // ──────────────────────────────────────────────────────────────

    private fun isBankSms(body: String): Boolean {
        if (isPromotionalSms(body)) return false
        val b = body.lowercase()

        val hasAmount = Regex("""(EGP|USD|EUR|LE)\s*[\d,]+""", RegexOption.IGNORE_CASE).containsMatchIn(b)

        // Without a currency amount only hard keywords qualify
        if (!hasAmount) {
            return listOf("salary", "instapay", "ipn inward", "ipn outward").any { b.contains(it) }
        }

        // Amount alone is insufficient — require at least one concrete transaction signal
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
        val b = body.lowercase()

        if (b.contains("cashback") && (b.contains("credited") || b.contains("earned"))) return "Income"

        if (b.contains("total amt due") || b.contains("min. amt due") ||
            b.contains("statement is issued") || b.contains("statement date")) return "Statement"

        if (b.contains("statement") || b.contains("due before") || b.contains("due date")) {
            if (b.contains("amt due") || b.contains("total egp")) return "Statement"
            if (b.contains("check your statement") || b.contains("log on to")) {
                return if (listOf("credited","received","earned").any { b.contains(it) }) "Income" else "Expense"
            }
            return "Statement"
        }

        // Credit-SIDE of a CC payment — the credit card bank confirms it received the money.
        // Must be checked BEFORE the generic "CardPayment" patterns below.
        val creditReceivedPatterns = listOf(
            "payment received for your credit card",
            "payment received for card",
            "payment credited to your credit card",
            "credited to your credit card",
            "credit card payment received",
            "card payment received",
            "received for your card ending",
            "payment.*received.*card"   // handled via contains checks above; kept for clarity
        )
        if ((b.contains("payment received") || b.contains("payment credited")) &&
            (b.contains("credit card") || b.contains("your card")) &&
            !b.contains("cashback")) return "CreditCardReceived"

        // Debit-SIDE of a CC payment — the debit bank debited the account to pay a credit card.
        if (b.contains("made to credit card") ||
            b.contains("for credit card") ||
            (b.contains("transfer") && b.contains("credit card")) ||
            (b.contains("debited") && b.contains("credit card"))) return "CardPayment"

        if (b.contains("withdrawal")) return "AtmWithdrawal"

        val incomeKw = listOf("credited", "received", "deposit", "returned",
            "salary", "tt payment", "ipn inward", "earned cashback")
        if (incomeKw.any { b.contains(it) }) return "Income"

        return "Expense"
    }

    private fun inferCategory(body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("cashback") -> "Others"
            b.contains("ipn outward") || (b.contains("instapay") && b.contains("outward")) -> "Instapay outcome"
            b.contains("ipn inward")  || (b.contains("instapay") && b.contains("inward"))  -> "Instapay income"
            b.contains("salary") || b.contains("tt payment") -> "Salary"
            b.contains("beet elgomla") || b.contains("carrefour") || b.contains("metro market") ||
                b.contains("kheir zaman") || b.contains("lulu") || b.contains("panda") -> "Groceries"
            b.contains("uber") || b.contains("careem") || b.contains("indrive") -> "Uber"
            b.contains("netflix") || b.contains("youtube") || b.contains("amazon") ||
                b.contains("spotify") || b.contains("disney") || b.contains("yango") -> "Subscriptions"
            b.contains("vodafone") || b.contains("orange") || b.contains("etisalat") ||
                b.contains("we telecom") || b.contains("fawry") -> "Mobile"
            b.contains("kfc") || b.contains("mcdonalds") || b.contains("pizza") ||
                b.contains("restaurant") -> "Restaurants"
            b.contains("cafe") || b.contains("coffee") || b.contains("starbucks") -> "Cafe"
            b.contains("pharmacy") || b.contains("el ezaby") || b.contains("almokhtbr") ||
                b.contains("el borg") -> "Health and beauty"
            b.contains("fuel") || b.contains("petrol") || b.contains("gas station") -> "Car"
            else -> "Others"
        }
    }

    private fun inferComment(body: String): String? {
        if (body.contains("cashback", ignoreCase = true)) return "Cashback"
        val toName    = Regex("""to\s+(.*?)\s+with\s+reference""",       RegexOption.IGNORE_CASE)
        val fromName  = Regex("""from\s+(.*?)\s+with\s+reference""",     RegexOption.IGNORE_CASE)
        val atMerchant = Regex("""at\s+(.*?)(?:\.|\s+on|\s+Your|$)""",  RegexOption.IGNORE_CASE)
        return toName.find(body)?.groupValues?.get(1)?.trim()
            ?: fromName.find(body)?.groupValues?.get(1)?.trim()
            ?: atMerchant.find(body)?.groupValues?.get(1)?.trim()
    }

    private fun extractAmount(body: String): String? {
        val p = """([\d,]+\.\d{2}|[\d\.]+\,\d{2}|\d+[\.,]\d+|\d+)"""
        Regex("""Total Amt Due\s*(?:EGP|USD|EUR|LE)?\s*$p""",                        RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",","") }
        Regex("""total\s+(?:EGP|USD|EUR|LE)?\s*$p""",                                RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",","") }
        Regex("""(?:EGP|USD|EUR|LE|Amount:?|total|Due|Cashback of)\s*$p""",           RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",","") }
        if (Regex("""(EGP|USD|EUR|LE|\$|£)""", RegexOption.IGNORE_CASE).containsMatchIn(body))
            return Regex(p).find(body)?.value?.replace(",","")
        return null
    }

    private fun extractLast4Digits(body: String): String? {
        val pattern = """(?:\*+|card|A/c|ending|acc\.?|account|visa|mastercard)\s*[-]?\s*(\d{3,4})\b"""
        val matches = Regex(pattern, RegexOption.IGNORE_CASE).findAll(body).toList()
        if (matches.isNotEmpty()) {
            val starred = matches.find { it.value.contains("*") }
            return starred?.groupValues?.get(1) ?: matches.last().groupValues[1]
        }
        val allFour = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        val yr = Calendar.getInstance().get(Calendar.YEAR)
        return allFour.find { it.toIntOrNull() !in (yr - 2)..(yr + 5) } ?: allFour.firstOrNull()
    }

    private fun extractDueDate(body: String): String? {
        val regex = Regex("""(?:Due Date|due before)\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1)
    }

    /**
     * Tries to read the post-transaction balance that many banks print in their SMS
     * (e.g. "Avail Bal EGP 10,000.00" / "Available Balance: EGP 5,000" / "Avbl Bal: 3000").
     * Returns null when no balance figure is found so callers can fall back to calculation.
     */
    private fun extractBalanceFromSms(body: String): Double? {
        val num = """([\d,]+(?:\.\d{1,2})?)"""
        val cur = """(?:EGP|USD|EUR|LE)?\s*"""
        // "Avail Bal EGP 10,000" / "Available Balance: 5000" / "Bal after txn: 3000"
        Regex("""(?:avail(?:able)?\s*(?:bal(?:ance)?|credit)|avbl\.?\s*bal|new\s*bal(?:ance)?|current\s*bal(?:ance)?|bal(?:ance)?\s*after|a/c\s*bal|remaining\s*bal(?:ance)?)\s*[:\-]?\s*$cur$num""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        // "EGP 10,000 available balance"
        Regex("""(?:EGP|USD|EUR|LE)\s*$num\s+(?:is\s+your\s+)?avail(?:able)?\s*(?:bal(?:ance)?|credit)""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        return null
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
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Transaction Alerts", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (goToRecords) putExtra("navigate_to", "all_records")
        }
        val pi = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title).setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi).setAutoCancel(true).build())
    }
}
