package com.example.wallettrackers.viewmodel

import android.app.Application
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.util.DeviceSms
import com.example.wallettrackers.util.DeviceSmsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

enum class OnboardingStep { WELCOME, SCANNING, ACCOUNTS_FOUND, IMPORTING, DONE }

data class DiscoveredAccount(
    val last4Digits: String,
    val inferredType: String,
    val inferredBankName: String,
    val smsCount: Int,
    val estimatedBalance: Double,
    val confirmedName: String,
    val selected: Boolean = true,
    val smsList: List<DeviceSms> = emptyList()
)

class OnboardingViewModel(
    application: Application,
    private val userId: String
) : AndroidViewModel(application) {

    private val repository = FirebaseRepository(userId)

    private val _step = mutableStateOf(OnboardingStep.WELCOME)
    val step: State<OnboardingStep> = _step

    private val _discoveredAccounts = mutableStateListOf<DiscoveredAccount>()
    val discoveredAccounts: List<DiscoveredAccount> = _discoveredAccounts

    private val _importTotal = mutableIntStateOf(0)
    val importTotal: State<Int> = _importTotal

    private val _importCurrent = mutableIntStateOf(0)
    val importCurrent: State<Int> = _importCurrent

    private val _errorMessage = mutableStateOf<String?>(null)
    val errorMessage: State<String?> = _errorMessage

    private val _smsSheetAccount = mutableStateOf<DiscoveredAccount?>(null)
    val smsSheetAccount: State<DiscoveredAccount?> = _smsSheetAccount

    fun openSmsSheet(account: DiscoveredAccount) { _smsSheetAccount.value = account }
    fun closeSmsSheet() { _smsSheetAccount.value = null }

    fun startScan() {
        _step.value = OnboardingStep.SCANNING
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val allSms = DeviceSmsReader.readAll(context)
                val bankSms = allSms.filter { isBankSms(it.body) }

                val groups = mutableMapOf<String, MutableList<DeviceSms>>()
                for (sms in bankSms) {
                    val digits = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
                    groups.getOrPut(digits) { mutableListOf() }.add(sms)
                }

                val discovered = groups.mapNotNull { (digits, smsList) ->
                    if (digits.isEmpty() && smsList.none { isBankSms(it.body) }) return@mapNotNull null
                    val type = inferAccountType(smsList.map { it.body })
                    val bank = inferBankName(smsList.map { it.body + " " + it.sender })
                    val balance = reconstructBalance(smsList)
                    val name = if (digits.isEmpty()) "Cash" else "$bank ****$digits"
                    DiscoveredAccount(
                        last4Digits = digits,
                        inferredType = type,
                        inferredBankName = bank,
                        smsCount = smsList.size,
                        estimatedBalance = balance,
                        confirmedName = name,
                        smsList = smsList.sortedByDescending { it.date }
                    )
                }.sortedByDescending { it.smsCount }

                withContext(Dispatchers.Main) {
                    _discoveredAccounts.clear()
                    _discoveredAccounts.addAll(discovered)
                    _step.value = OnboardingStep.ACCOUNTS_FOUND
                }
            } catch (e: Exception) {
                Log.e("OnboardingViewModel", "Scan failed", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Scan failed: ${e.message}"
                    _step.value = OnboardingStep.WELCOME
                }
            }
        }
    }

    fun updateAccountName(index: Int, newName: String) {
        if (index in _discoveredAccounts.indices) {
            _discoveredAccounts[index] = _discoveredAccounts[index].copy(confirmedName = newName)
        }
    }

    fun toggleAccountSelection(index: Int) {
        if (index in _discoveredAccounts.indices) {
            _discoveredAccounts[index] = _discoveredAccounts[index].copy(
                selected = !_discoveredAccounts[index].selected
            )
        }
    }

    fun startImport() {
        _step.value = OnboardingStep.IMPORTING
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val allSms = DeviceSmsReader.readAll(context)
                val bankSms = allSms.filter { isBankSms(it.body) }

                val selectedAccounts = _discoveredAccounts.filter { it.selected }
                val digitToId = mutableMapOf<String, String>()
                val digitToAccount = mutableMapOf<String, Account>()

                for (da in selectedAccounts) {
                    val account = Account(
                        name = da.confirmedName,
                        accountType = da.inferredType,
                        last4Digits = da.last4Digits,
                        amount = String.format(Locale.US, "%.2f", da.estimatedBalance),
                        currency = "EGP",
                        userId = userId
                    )
                    val id = repository.addAccountAndGetId(account)
                    if (id != null) {
                        digitToId[da.last4Digits] = id
                        digitToAccount[da.last4Digits] = account.copy(id = id)
                    }
                }

                val importableSms = bankSms.filter { sms ->
                    val digits = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
                    digitToId.containsKey(digits)
                }

                withContext(Dispatchers.Main) {
                    _importTotal.intValue = importableSms.size
                    _importCurrent.intValue = 0
                }

                val runningBalances = digitToId.keys.associateWith { 0.0 }.toMutableMap()

                for (sms in importableSms) {
                    val digits = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
                    val accountId = digitToId[digits] ?: continue
                    val account = digitToAccount[digits] ?: continue
                    val amount = extractAmount(sms.body)?.toDoubleOrNull()
                    if (amount == null) {
                        withContext(Dispatchers.Main) { _importCurrent.intValue++ }
                        continue
                    }
                    val type = inferType(sms.body)
                    if (type == "Statement" || type == "CardPayment" || type == "CreditCardReceived" || type == "AtmWithdrawal") {
                        withContext(Dispatchers.Main) { _importCurrent.intValue++ }
                        continue
                    }
                    val isIncome = type == "Income"
                    val currentBal = runningBalances[digits] ?: 0.0
                    val calculated = if (isIncome) currentBal + amount else currentBal - amount
                    // Prefer the balance the bank printed in this SMS; fall back to running total
                    val newBal = extractBalanceFromSms(sms.body) ?: calculated
                    runningBalances[digits] = newBal

                    val alreadyExists = repository.recordWithSmsIdExists(sms.id)
                    if (!alreadyExists) {
                        repository.addRecord(Record(
                            amount = String.format(Locale.US, "%.2f", amount),
                            category = if (isIncome) inferIncomeCategory(sms.body) else inferCategory(sms.body),
                            type = if (isIncome) "Income" else "Expense",
                            accountId = accountId,
                            accountName = account.name,
                            currency = "EGP",
                            userId = userId,
                            timestamp = sms.date,
                            smsId = sms.id,
                            balanceAfter = String.format(Locale.US, "%.2f", newBal),
                            comment = inferComment(sms.body) ?: ""
                        ))
                    }
                    withContext(Dispatchers.Main) { _importCurrent.intValue++ }
                }

                // Final sync: update each account balance to the value from its most recent SMS
                // This corrects any drift and accounts for SMS that were deleted from the inbox.
                for (da in selectedAccounts) {
                    val lastSmsBalance = da.smsList // already sorted newest-first
                        .firstNotNullOfOrNull { extractBalanceFromSms(it.body) }
                    val accountId = digitToId[da.last4Digits] ?: continue
                    val account = digitToAccount[da.last4Digits] ?: continue
                    val finalBalance = lastSmsBalance ?: runningBalances[da.last4Digits] ?: continue
                    repository.updateAccount(account.copy(
                        amount = String.format(Locale.US, "%.2f", finalBalance)
                    ))
                }

                withContext(Dispatchers.Main) { _step.value = OnboardingStep.DONE }
            } catch (e: Exception) {
                Log.e("OnboardingViewModel", "Import failed", e)
                withContext(Dispatchers.Main) {
                    _errorMessage.value = "Import failed: ${e.message}"
                    _step.value = OnboardingStep.ACCOUNTS_FOUND
                }
            }
        }
    }

    fun skipOnboarding() {
        _step.value = OnboardingStep.DONE
    }

    fun clearError() {
        _errorMessage.value = null
    }

    // ─── Classification helpers ──────────────────────────────────────────────

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
        val b = body.lowercase()
        if (b.contains("cashback") && (b.contains("credited") || b.contains("earned"))) return "Income"
        if (b.contains("total amt due") || b.contains("min. amt due") ||
            b.contains("statement is issued") || b.contains("statement date")) return "Statement"
        if (b.contains("statement") || b.contains("due before") || b.contains("due date")) {
            if (b.contains("amt due") || b.contains("total egp")) return "Statement"
            return "Statement"
        }
        if ((b.contains("payment received") || b.contains("payment credited")) &&
            (b.contains("credit card") || b.contains("your card")) &&
            !b.contains("cashback")) return "CreditCardReceived"
        if (b.contains("made to credit card") || b.contains("for credit card") ||
            (b.contains("transfer") && b.contains("credit card")) ||
            (b.contains("debited") && b.contains("credit card"))) return "CardPayment"
        if (b.contains("withdrawal")) return "AtmWithdrawal"
        val incomeKw = listOf("credited", "received", "deposit", "returned",
            "salary", "tt payment", "ipn inward", "earned cashback")
        if (incomeKw.any { b.contains(it) }) return "Income"
        return "Expense"
    }

    private fun inferAccountType(bodies: List<String>): String {
        val combined = bodies.joinToString(" ").lowercase()
        val creditSignals = listOf("credit card", "statement", "min. amt due", "total amt due", "credit limit")
        if (creditSignals.any { combined.contains(it) }) return "Credit Card"
        return "Debit"
    }

    private fun inferBankName(texts: List<String>): String {
        val combined = texts.joinToString(" ").lowercase()
        return when {
            combined.contains("cib") -> "CIB"
            combined.contains("nbe") || combined.contains("national bank") -> "NBE"
            combined.contains("qnb") -> "QNB"
            combined.contains("banque misr") || combined.contains(" bm ") -> "BM"
            combined.contains("alex bank") || combined.contains("alexbank") -> "AlexBank"
            combined.contains("hsbc") -> "HSBC"
            combined.contains("faisal") -> "Faisal"
            combined.contains("arab african") || combined.contains("aaib") -> "AAIB"
            combined.contains("emirates") || combined.contains("enbd") -> "Emirates NBD"
            combined.contains("vodafone cash") -> "Vodafone Cash"
            combined.contains("instapay") -> "InstaPay"
            else -> "Bank"
        }
    }

    private fun reconstructBalance(smsList: List<DeviceSms>): Double {
        // Prefer the balance figure printed in the most recent SMS (most accurate, no drift)
        for (sms in smsList.sortedByDescending { it.date }) {
            val smsBalance = extractBalanceFromSms(sms.body)
            if (smsBalance != null) return smsBalance
        }
        // Fallback: replay transactions chronologically
        var balance = 0.0
        for (sms in smsList.sortedBy { it.date }) {
            val amount = extractAmount(sms.body)?.toDoubleOrNull() ?: continue
            val type = inferType(sms.body)
            if (type == "Statement" || type == "CardPayment" || type == "CreditCardReceived" || type == "AtmWithdrawal") continue
            balance = if (type == "Income") balance + amount else balance - amount
        }
        return balance
    }

    /**
     * Extracts the post-transaction balance printed in the SMS body
     * (e.g. "Avail Bal EGP 10,000.00" / "Available Balance: 5000" / "Avbl Bal: 3000").
     * Returns null when no balance figure is found.
     */
    private fun extractBalanceFromSms(body: String): Double? {
        val num = """([\d,]+(?:\.\d{1,2})?)"""
        val cur = """(?:EGP|USD|EUR|LE)?\s*"""
        Regex("""(?:avail(?:able)?\s*(?:bal(?:ance)?|credit)|avbl\.?\s*bal|new\s*bal(?:ance)?|current\s*bal(?:ance)?|bal(?:ance)?\s*after|a/c\s*bal|remaining\s*bal(?:ance)?)\s*[:\-]?\s*$cur$num""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        Regex("""(?:EGP|USD|EUR|LE)\s*$num\s+(?:is\s+your\s+)?avail(?:able)?\s*(?:bal(?:ance)?|credit)""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        return null
    }

    private fun inferCategory(body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("cashback") -> "Others"
            b.contains("ipn outward") || (b.contains("instapay") && b.contains("outward")) -> "Instapay outcome"
            b.contains("ipn inward") || (b.contains("instapay") && b.contains("inward")) -> "Instapay income"
            b.contains("salary") || b.contains("tt payment") -> "Salary"
            b.contains("carrefour") || b.contains("metro") || b.contains("kheir zaman") -> "Groceries"
            b.contains("uber") || b.contains("careem") -> "Uber"
            b.contains("netflix") || b.contains("youtube") || b.contains("amazon") || b.contains("spotify") -> "Subscriptions"
            b.contains("vodafone") || b.contains("orange") || b.contains("fawry") -> "Mobile"
            b.contains("kfc") || b.contains("mcdonalds") || b.contains("pizza") || b.contains("restaurant") -> "Restaurants"
            b.contains("cafe") || b.contains("coffee") || b.contains("starbucks") -> "Cafe"
            b.contains("pharmacy") || b.contains("el ezaby") -> "Health and beauty"
            b.contains("fuel") || b.contains("petrol") -> "Car"
            else -> "Others"
        }
    }

    private fun inferIncomeCategory(body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("salary") || b.contains("tt payment") -> "Salary"
            b.contains("cashback") -> "Others"
            b.contains("ipn inward") || (b.contains("instapay") && b.contains("inward")) -> "Instapay income"
            else -> "Others"
        }
    }

    private fun inferComment(body: String): String? {
        if (body.contains("cashback", ignoreCase = true)) return "Cashback"
        val toName = Regex("""to\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val fromName = Regex("""from\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val atMerchant = Regex("""at\s+(.*?)(?:\.|\s+on|\s+Your|$)""", RegexOption.IGNORE_CASE)
        return toName.find(body)?.groupValues?.get(1)?.trim()
            ?: fromName.find(body)?.groupValues?.get(1)?.trim()
            ?: atMerchant.find(body)?.groupValues?.get(1)?.trim()
    }

    private fun extractAmount(body: String): String? {
        val p = """([\d,]+\.\d{2}|[\d\.]+\,\d{2}|\d+[\.,]\d+|\d+)"""
        Regex("""Total Amt Due\s*(?:EGP|USD|EUR|LE)?\s*$p""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",", "") }
        Regex("""total\s+(?:EGP|USD|EUR|LE)?\s*$p""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",", "") }
        Regex("""(?:EGP|USD|EUR|LE|Amount:?|total|Due|Cashback of)\s*$p""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",", "") }
        if (Regex("""(EGP|USD|EUR|LE|\$|£)""", RegexOption.IGNORE_CASE).containsMatchIn(body))
            return Regex(p).find(body)?.value?.replace(",", "")
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
}
