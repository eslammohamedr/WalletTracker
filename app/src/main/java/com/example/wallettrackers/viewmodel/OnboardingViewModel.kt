package com.example.wallettrackers.viewmodel

import android.app.Application
import android.util.Log
import com.example.wallettrackers.util.SmsParser
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wallettrackers.BuildConfig
import com.example.wallettrackers.converters.colorToLong
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.service.AiService
import com.example.wallettrackers.ui.theme.pickAutoColor
import java.text.SimpleDateFormat
import com.example.wallettrackers.util.DeviceSms
import com.example.wallettrackers.util.DeviceSmsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
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
    val smsList: List<DeviceSms> = emptyList(),
    val possibleDuplicateDigits: String? = null,
    // Credit card extras
    val creditLimit: Double? = null,
    val pendingStatementAmount: Double? = null,
    val pendingStatementDueDate: Date? = null
)

class OnboardingViewModel(
    application: Application,
    private val userId: String
) : AndroidViewModel(application) {

    private val repository = FirebaseRepository(userId)
    private val aiService = AiService(
        groqApiKey     = BuildConfig.GROQ_API_KEY,
        cerebrasApiKey = BuildConfig.CEREBRAS_API_KEY,
        geminiApiKey   = BuildConfig.GEMINI_API_KEY
    )

    private data class OthersItem(val smsId: String, val smsBody: String, val type: String)

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

    private val _statusMessage = mutableStateOf<String?>(null)
    val statusMessage: State<String?> = _statusMessage

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
                val bankSms = allSms.filter { isBankSms(it.body, it.sender) }

                val groups = mutableMapOf<String, MutableList<DeviceSms>>()
                for (sms in bankSms) {
                    val rawDigits = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
                    val key = resolveGroupKey(groups, rawDigits)
                    groups.getOrPut(key) { mutableListOf() }.add(sms)
                }

                val raw = groups.mapNotNull { (digits, smsList) ->
                    if (digits.isEmpty() && smsList.none { isBankSms(it.body, it.sender) }) return@mapNotNull null
                    val sortedDesc = smsList.sortedByDescending { it.date }
                    val type = inferAccountType(smsList.map { it.body })
                    val bank = inferBankName(smsList.map { it.body + " " + it.sender })
                    val balance = reconstructBalance(smsList)
                    val name = if (digits.isEmpty()) "Cash" else "$bank ****$digits"

                    // Credit-card extras: limit, and most recent unpaid statement
                    val creditLimit = if (type == "Credit Card")
                        sortedDesc.firstNotNullOfOrNull { extractCreditLimit(it.body) } else null

                    val statementSms = if (type == "Credit Card")
                        sortedDesc.firstOrNull { inferType(it.body) == "Statement" } else null
                    val pendingAmt = statementSms?.let { extractAmount(it.body)?.toDoubleOrNull() }
                    val pendingDueStr: String? = statementSms?.let { sms -> extractDueDate(sms.body) }
                    val pendingDue: Date? = pendingDueStr?.let { ds -> parseDueDate(ds) }

                    DiscoveredAccount(
                        last4Digits = digits,
                        inferredType = type,
                        inferredBankName = bank,
                        smsCount = smsList.size,
                        estimatedBalance = balance,
                        confirmedName = name,
                        smsList = sortedDesc,
                        creditLimit = creditLimit,
                        pendingStatementAmount = pendingAmt,
                        pendingStatementDueDate = pendingDue
                    )
                }.sortedByDescending { it.smsCount }

                val discovered = detectPossibleDuplicates(raw)

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

    fun updateCreditLimit(index: Int, limitStr: String) {
        if (index in _discoveredAccounts.indices) {
            _discoveredAccounts[index] = _discoveredAccounts[index].copy(
                creditLimit = limitStr.toDoubleOrNull()
            )
        }
    }

    fun toggleAccountSelection(index: Int) {
        if (index in _discoveredAccounts.indices) {
            _discoveredAccounts[index] = _discoveredAccounts[index].copy(
                selected = !_discoveredAccounts[index].selected
            )
        }
    }

    /**
     * Merges two discovered accounts that represent the same bank account with a renewed card.
     * The newer card's digits become the active last4; SMS histories are combined.
     */
    fun mergeAccounts(keepDigits: String, dropDigits: String) {
        val keepIdx = _discoveredAccounts.indexOfFirst { it.last4Digits == keepDigits }
        val dropIdx = _discoveredAccounts.indexOfFirst { it.last4Digits == dropDigits }
        if (keepIdx == -1 || dropIdx == -1) return

        val keep = _discoveredAccounts[keepIdx]
        val drop = _discoveredAccounts[dropIdx]

        // The card with the most recent SMS is the current active card
        val keepLatest = keep.smsList.maxOfOrNull { it.date } ?: Date(0)
        val dropLatest = drop.smsList.maxOfOrNull { it.date } ?: Date(0)
        val active = if (keepLatest >= dropLatest) keep else drop

        val mergedSmsList = (keep.smsList + drop.smsList).sortedByDescending { it.date }
        val merged = active.copy(
            inferredType = inferAccountType(mergedSmsList.map { it.body }),
            smsCount = mergedSmsList.size,
            estimatedBalance = reconstructBalance(mergedSmsList),
            smsList = mergedSmsList,
            possibleDuplicateDigits = null
        )

        // Remove higher index first so lower index stays valid
        val hi = maxOf(keepIdx, dropIdx)
        val lo = minOf(keepIdx, dropIdx)
        _discoveredAccounts.removeAt(hi)
        _discoveredAccounts.removeAt(lo)
        _discoveredAccounts.add(lo, merged)

        // Clear stale duplicate flags pointing at either removed account
        for (i in _discoveredAccounts.indices) {
            val da = _discoveredAccounts[i]
            if (da.possibleDuplicateDigits == keepDigits || da.possibleDuplicateDigits == dropDigits) {
                _discoveredAccounts[i] = da.copy(possibleDuplicateDigits = null)
            }
        }
    }

    /**
     * Finds the canonical group key for [newDigits] by checking whether any existing key
     * is a suffix of [newDigits] or vice versa (e.g. "001" and "6001" are the same account).
     * Always keeps the longer (more specific) key so subsequent lookups keep matching.
     */
    private fun resolveGroupKey(
        groups: MutableMap<String, MutableList<DeviceSms>>,
        newDigits: String
    ): String {
        if (newDigits.isEmpty()) return newDigits
        val existingKey = groups.keys.find { key ->
            key.isNotEmpty() && (key == newDigits || newDigits.endsWith(key) || key.endsWith(newDigits))
        } ?: return newDigits

        return if (newDigits.length > existingKey.length) {
            // Promote to the longer key — rename the existing group
            val list = groups.remove(existingKey)!!
            groups[newDigits] = list
            newDigits
        } else {
            existingKey
        }
    }

    /**
     * After scanning, flags pairs of accounts that are likely the same bank account
     * with a renewed card: same known bank, same type, and SMS date ranges that don't
     * significantly overlap (≤60 days). The newer card gets a reference to the older one.
     */
    private fun detectPossibleDuplicates(accounts: List<DiscoveredAccount>): List<DiscoveredAccount> {
        val result = accounts.toMutableList()
        val flagged = mutableSetOf<String>()

        for (i in result.indices) {
            val a = result[i]
            if (a.last4Digits in flagged || a.smsList.isEmpty() || a.inferredBankName == "Bank") continue

            for (j in i + 1 until result.size) {
                val b = result[j]
                if (b.last4Digits in flagged || b.smsList.isEmpty()) continue
                if (a.inferredBankName != b.inferredBankName) continue
                if (a.inferredType != b.inferredType) continue

                val aMin = a.smsList.minOf { it.date }
                val aMax = a.smsList.maxOf { it.date }
                val bMin = b.smsList.minOf { it.date }
                val bMax = b.smsList.maxOf { it.date }

                val overlapStart = if (aMin.after(bMin)) aMin else bMin
                val overlapEnd   = if (aMax.before(bMax)) aMax else bMax
                val overlapDays  = if (overlapEnd.after(overlapStart))
                    (overlapEnd.time - overlapStart.time) / 86_400_000L else 0L

                if (overlapDays <= 60) {
                    // Flag the newer card (has the more recent SMS) with a pointer to the older
                    val newerDigits = if (aMax.after(bMax)) a.last4Digits else b.last4Digits
                    val olderDigits = if (aMax.after(bMax)) b.last4Digits else a.last4Digits
                    val newerIdx = result.indexOfFirst { it.last4Digits == newerDigits }
                    if (newerIdx != -1) result[newerIdx] = result[newerIdx].copy(possibleDuplicateDigits = olderDigits)
                    flagged += a.last4Digits
                    flagged += b.last4Digits
                    break
                }
            }
        }
        return result
    }

    fun startImport() {
        _step.value = OnboardingStep.IMPORTING
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>().applicationContext
                val allSms = DeviceSmsReader.readAll(context)
                val bankSms = allSms.filter { isBankSms(it.body, it.sender) }

                val selectedAccounts = _discoveredAccounts.filter { it.selected }
                val digitToId = mutableMapOf<String, String>()
                val digitToAccount = mutableMapOf<String, Account>()

                // Collect colors already in use so each new account gets a distinct palette color
                val usedColors = repository.getAccounts().first().map { it.color }.toMutableList()

                for (da in selectedAccounts) {
                    val color = pickAutoColor(usedColors)
                    val colorLong = colorToLong(color)
                    usedColors += colorLong

                    val account = Account(
                        name = da.confirmedName,
                        accountType = da.inferredType,
                        last4Digits = da.last4Digits,
                        amount = String.format(Locale.US, "%.2f", da.estimatedBalance),
                        currency = inferCurrency(da.smsList.joinToString(" ") { it.body }),
                        color = colorLong,
                        creditLimit = da.creditLimit,
                        userId = userId
                    )
                    val id = repository.addAccountAndGetId(account)
                    if (id != null) {
                        digitToId[da.last4Digits] = id
                        digitToAccount[da.last4Digits] = account.copy(id = id)

                        // Create the pending credit statement so reminders and "Pay" button work
                        if (da.inferredType == "Credit Card" && da.pendingStatementAmount != null) {
                            repository.addCreditStatement(CreditStatement(
                                cardLast4Digits = da.last4Digits,
                                accountId = id,
                                totalAmount = da.pendingStatementAmount,
                                dueDate = da.pendingStatementDueDate ?: Date(),
                                userId = userId,
                                smsId = "onboarding_${da.last4Digits}"
                            ))
                        }
                    }
                }

                // Auto-create a Cash account if none was imported and none already exists
                val hasCashImported = selectedAccounts.any { it.inferredType.equals("Cash", ignoreCase = true) || it.last4Digits.isEmpty() }
                if (!hasCashImported) {
                    val existingCash = repository.getAccounts().first()
                        .any { it.accountType.equals("Cash", ignoreCase = true) }
                    if (!existingCash) {
                        val cashColor = pickAutoColor(usedColors)
                        repository.addAccountAndGetId(Account(
                            name = "Cash",
                            accountType = "Cash",
                            last4Digits = "",
                            amount = "0.00",
                            currency = "EGP",
                            color = colorToLong(cashColor),
                            userId = userId
                        ))
                    }
                }

                fun resolveDigitKey(raw: String): String? {
                    if (raw.isEmpty()) return null
                    return digitToId.keys.find { key ->
                        key == raw || raw.endsWith(key) || key.endsWith(raw)
                    }
                }

                val matchedSms = bankSms.filter { sms ->
                    val raw = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
                    resolveDigitKey(raw) != null
                }
                val unmatchedSms = bankSms.filter { sms ->
                    val raw = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
                    resolveDigitKey(raw) == null && extractAmount(sms.body) != null
                }

                withContext(Dispatchers.Main) {
                    _statusMessage.value = "Phase 1: Importing records..."
                    _importTotal.intValue = matchedSms.size + unmatchedSms.size
                    _importCurrent.intValue = 0
                }

                val runningBalances = digitToId.keys.associateWith { 0.0 }.toMutableMap()
                val othersQueue = mutableListOf<OthersItem>()

                for (sms in matchedSms) {
                    val raw = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
                    val digits = resolveDigitKey(raw) ?: continue
                    val accountId = digitToId[digits] ?: continue
                    val account = digitToAccount[digits] ?: continue
                    val amount = extractAmount(sms.body)?.toDoubleOrNull()
                    if (amount == null) {
                        withContext(Dispatchers.Main) { _importCurrent.intValue++ }
                        continue
                    }
                    val type = inferType(sms.body)
                    if (type == "Statement" || type == "CardPayment" || type == "CreditCardReceived") {
                        withContext(Dispatchers.Main) { _importCurrent.intValue++ }
                        continue
                    }

                    if (type == "AtmWithdrawal") {
                        val newBal = extractBalanceFromSms(sms.body) ?: ((runningBalances[digits] ?: 0.0) - amount)
                        runningBalances[digits] = newBal
                        val alreadyExists = repository.recordWithSmsIdExists(sms.id)
                        if (!alreadyExists) {
                            repository.addRecord(Record(
                                amount = String.format(Locale.US, "%.2f", amount),
                                category = "Transfer",
                                type = "Expense",
                                accountId = accountId,
                                accountName = "${account.name} -> Cash",
                                currency = inferCurrency(sms.body),
                                userId = userId,
                                timestamp = sms.date,
                                smsId = sms.id,
                                balanceAfter = String.format(Locale.US, "%.2f", newBal),
                                comment = "ATM Withdrawal"
                            ))
                        }
                        withContext(Dispatchers.Main) { _importCurrent.intValue++ }
                        continue
                    }

                    val isIncome = type == "Income"
                    val currentBal = runningBalances[digits] ?: 0.0
                    val calculated = if (isIncome) currentBal + amount else currentBal - amount
                    // Prefer the balance the bank printed in this SMS; fall back to running total
                    val newBal = extractBalanceFromSms(sms.body) ?: calculated
                    runningBalances[digits] = newBal

                    val category = if (isIncome) inferIncomeCategory(sms.body) else inferCategory(sms.body)
                    val alreadyExists = repository.recordWithSmsIdExists(sms.id)
                    if (!alreadyExists) {
                        repository.addRecord(Record(
                            amount = String.format(Locale.US, "%.2f", amount),
                            category = category,
                            type = if (isIncome) "Income" else "Expense",
                            accountId = accountId,
                            accountName = account.name,
                            currency = inferCurrency(sms.body),
                            userId = userId,
                            timestamp = sms.date,
                            smsId = sms.id,
                            balanceAfter = String.format(Locale.US, "%.2f", newBal),
                            comment = inferComment(sms.body) ?: ""
                        ))
                        if (category == "Others") othersQueue.add(OthersItem(sms.id, sms.body, if (isIncome) "Income" else "Expense"))
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


                // Import SMS for cards not found during onboarding — save with placeholder account name
                for (sms in unmatchedSms) {
                    val raw = extractLast4Digits(sms.body)?.filter { it.isDigit() } ?: ""
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
                    val category = if (isIncome) inferIncomeCategory(sms.body) else inferCategory(sms.body)
                    val alreadyExists = repository.recordWithSmsIdExists(sms.id)
                    if (!alreadyExists) {
                        val accountLabel = if (raw.isNotEmpty()) "Imported Card ($raw)" else "Unknown"
                        repository.addRecord(Record(
                            amount = String.format(Locale.US, "%.2f", amount),
                            category = category,
                            type = if (isIncome) "Income" else "Expense",
                            accountId = "",
                            accountName = accountLabel,
                            currency = inferCurrency(sms.body),
                            userId = userId,
                            timestamp = sms.date,
                            smsId = sms.id,
                            balanceAfter = "",
                            comment = inferComment(sms.body) ?: ""
                        ))
                        if (category == "Others") othersQueue.add(OthersItem(sms.id, sms.body, if (isIncome) "Income" else "Expense"))
                    }
                    withContext(Dispatchers.Main) { _importCurrent.intValue++ }
                }

                // Phase 2: AI re-categorization of "Others" records
                if (othersQueue.isNotEmpty()) {
                    withContext(Dispatchers.Main) {
                        _statusMessage.value = "Phase 2: Improving ${othersQueue.size} uncategorized records with AI..."
                        _importTotal.intValue = othersQueue.size
                        _importCurrent.intValue = 0
                    }
                    for ((idx, item) in othersQueue.withIndex()) {
                        try {
                            val aiCategory = aiService.inferCategory(item.smsBody)
                            if (aiCategory != null && !aiCategory.equals("Others", ignoreCase = true)) {
                                val record = repository.findRecordBySmsId(item.smsId)
                                if (record != null) {
                                    val correctedType = when {
                                        aiCategory in listOf("Salary", "Instapay income", "Gifts") && item.type == "Expense" -> "Income"
                                        else -> item.type
                                    }
                                    repository.updateRecord(record.copy(category = aiCategory, type = correctedType))
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("OnboardingViewModel", "AI re-categorization failed: ${e.message}")
                        }
                        withContext(Dispatchers.Main) { _importCurrent.intValue = idx + 1 }
                        delay(350)
                    }
                    withContext(Dispatchers.Main) { _statusMessage.value = null }
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

    private fun inferCurrency(body: String): String {
        val b = body.uppercase()
        return when {
            b.contains("USD") || b.contains("\$") -> "USD"
            b.contains("EUR") || b.contains("€")  -> "EUR"
            b.contains("GBP") || b.contains("£")  -> "GBP"
            else -> "EGP"
        }
    }

    private fun isBankSms(body: String, sender: String = "") = SmsParser.isBankSms(body, sender)

    private fun inferType(body: String) = SmsParser.inferType(body)

    private fun inferAccountType(bodies: List<String>): String {
        val combined = bodies.joinToString(" ").lowercase()

        // Hard signals — unambiguously mean the account IS a credit card
        val hardCreditSignals = listOf(
            "min. amt due", "total amt due", "credit limit",
            "minimum payment due", "statement balance",
            "card statement", "credit statement"
        )
        if (hardCreditSignals.any { combined.contains(it) }) return "Credit Card"

        // "credit card" alone is ambiguous — a debit account SMS also says it
        // when the account is PAYING its credit card ("transfer to your credit card").
        // Only classify as Credit Card if there is no payment-to-CC pattern present.
        val payingCCPatterns = listOf(
            "to your credit card", "to credit card",
            "for credit card", "debited for credit card"
        )
        if (combined.contains("credit card") && payingCCPatterns.none { combined.contains(it) }) {
            return "Credit Card"
        }

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
        val sorted = smsList.sortedByDescending { it.date }
        // Prefer the balance figure printed in the most recent SMS
        for (sms in sorted) {
            val smsBalance = extractBalanceFromSms(sms.body)
            if (smsBalance != null) return smsBalance
        }
        // Fallback: use the amount from the most recent SMS that has any amount
        for (sms in sorted) {
            val amount = extractAmount(sms.body)?.toDoubleOrNull()
            if (amount != null) return amount
        }
        return 0.0
    }

    /**
     * Extracts the post-transaction balance printed in the SMS body
     * (e.g. "Avail Bal EGP 10,000.00" / "Available Balance: 5000" / "Avbl Bal: 3000").
     * Returns null when no balance figure is found.
     */
    private fun extractBalanceFromSms(body: String): Double? {
        val num = """([\d,]+(?:\.\d{1,2})?)"""
        val cur = """(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£)?\s*"""
        // QNB format: "bal.EGP7.73" or "bal.EGP 1179.03" (dot separator, no space before currency)
        Regex("""bal\.(?:EGP|USD|EUR|GBP|SAR|AED|LE)\s*$num""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        Regex("""(?:avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)|avbl\.?\s*bal|new\s*bal(?:ance)?|current\s*bal(?:ance)?|bal(?:ance)?\s*after|a/c\s*bal|remaining\s*bal(?:ance)?)\s*(?:[:\-.]|is)?\s*$cur$num""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        Regex("""(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£)\s*$num\s+(?:is\s+)?(?:your\s+)?avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        return null
    }

    private fun extractCreditLimit(body: String): Double? {
        val num = """([\d,]+(?:\.\d{1,2})?)"""
        val cur = """(?:EGP|USD|EUR|GBP|LE|\$|€|£)?\s*"""
        // "Credit Limit: EGP 50,000" / "Cr. Limit EGP 50,000" / "Total Limit: EGP 50,000"
        Regex("""(?:credit\s*limit|cr\.?\s*limit|total\s*(?:credit\s*)?limit)\s*[:\-]?\s*$cur$num""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        // "EGP 50,000 credit limit"
        Regex("""(?:EGP|USD|EUR|LE)\s*$num\s+(?:credit\s*limit|cr\.?\s*limit)""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        return null
    }

    private fun extractDueDate(body: String): String? {
        val regex = Regex("""(?:Due Date|due before)\s*(\d{1,2}[/-]\d{1,2}[/-]\d{2,4})""", RegexOption.IGNORE_CASE)
        return regex.find(body)?.groupValues?.get(1)
    }

    private fun parseDueDate(dateStr: String): Date? = try {
        val fmt = if (dateStr.contains("/")) "dd/MM/yyyy" else "dd-MM-yyyy"
        SimpleDateFormat(fmt, Locale.getDefault()).parse(dateStr)
    } catch (e: Exception) { null }

    private fun inferCategory(body: String) = SmsParser.inferCategory(body)

    private fun inferIncomeCategory(body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("salary") || b.contains("tt payment") -> "Salary"
            b.contains("cashback") -> "Gifts"
            b.contains("ipn inward") || (b.contains("instapay") && b.contains("inward")) -> "Instapay income"
            else -> "Others"
        }
    }

    private fun inferComment(body: String) = SmsParser.inferComment(body)

    private fun extractAmount(body: String): String? {
        val p = """([\d,]+\.\d{2}|[\d\.]+\,\d{2}|\d+[\.,]\d+|\d+)"""
        Regex("""Total Amt Due\s*(?:EGP|USD|EUR|GBP|LE|\$|€|£)?\s*$p""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",", "") }
        Regex("""total\s+(?:EGP|USD|EUR|GBP|LE|\$|€|£)?\s*$p""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",", "") }
        Regex("""(?:EGP|USD|EUR|GBP|LE|\$|€|£|Amount:?|total|Due|Cashback of)\s*$p""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1].replace(",", "") }
        if (Regex("""(?:EGP|USD|EUR|GBP|LE|\$|€|£)""", RegexOption.IGNORE_CASE).containsMatchIn(body))
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
        // QNB IPN format: "from XXXX on DD/MM" or "on XXXX on DD/MM" — account digits before date
        Regex("""(?:from|on)\s+(\d{4})\s+on\s+\d{2}/\d{2}""", RegexOption.IGNORE_CASE).find(body)
            ?.let { return it.groupValues[1] }
        val allFour = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        val yr = Calendar.getInstance().get(Calendar.YEAR)
        return allFour.find { it.toIntOrNull() !in (yr - 2)..(yr + 5) } ?: allFour.firstOrNull()
    }
}
