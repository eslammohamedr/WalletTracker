package com.example.wallettrackers.viewmodel

import android.app.Application
import android.provider.Telephony
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.wallettrackers.model.Account
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.model.SmsMessage
import com.example.wallettrackers.repository.FirebaseRepository
import com.example.wallettrackers.service.AiService
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

class SmsViewModel(application: Application, private val userId: String) : AndroidViewModel(application) {

    private val _smsMessages = mutableStateOf<List<SmsMessage>>(emptyList())
    val smsMessages: State<List<SmsMessage>> = _smsMessages

    private val _accounts = mutableStateOf<List<Account>>(emptyList())
    val accounts: State<List<Account>> = _accounts

    private val _loadingSmsIds = mutableStateListOf<String>()
    val loadingSmsIds: List<String> = _loadingSmsIds

    private val _toastMessage = mutableStateOf<String?>(null)
    val toastMessage: State<String?> = _toastMessage

    private val repository = FirebaseRepository(userId)
    
    private val GEMINI_API_KEY = "AIzaSyAxdeJgJcVOe36H2BT6PQ-IU3hYhv4k0Pg"

    init {
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            repository.getAccounts().combine(repository.getRecords()) { accounts, records ->
                _accounts.value = accounts
                val rawSms = fetchRawSmsFromInbox()
                matchSmsWithData(rawSms, accounts, records)
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

    private fun matchSmsWithData(rawSms: List<SmsMessage>, currentAccounts: List<Account>, currentRecords: List<Record>) {
        val processedMessages = rawSms.map { sms ->
            val isBank = isBankSms(sms.body)
            val linkedRecord = currentRecords.find { 
                it.smsId == sms.id || (it.amount.isNotEmpty() && sms.body.contains(it.amount)) 
            }
            
            var missingReason: String? = null
            var extractedAmt: String? = null
            var digits: String? = null
            var category: String? = null
            var type: String? = null

            if (isBank && linkedRecord == null) {
                extractedAmt = extractAmount(sms.body)
                digits = extractLast4Digits(sms.body)
                category = "Others" 
                type = inferType(sms.body)
                
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
                digits = extractLast4Digits(sms.body) ?: linkedRecord.accountName.takeLast(4)
                category = linkedRecord.category
                type = linkedRecord.type
            }

            sms.copy(
                isBankRelated = isBank,
                hasRecordAdded = linkedRecord != null,
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
                val aiService = AiService(GEMINI_API_KEY)
                val result = aiService.analyzeSms(message.body)

                if (result != null && result.isBankRelated) {
                    val accounts = repository.getAccounts().first()
                    val smsDigitsOnly = result.last4Digits?.filter { it.isDigit() } ?: ""
                    
                    val targetAccount = accounts.find { acc ->
                        val accDigitsOnly = acc.last4Digits.filter { it.isDigit() }
                        accDigitsOnly.isNotEmpty() && smsDigitsOnly.isNotEmpty() && (
                            accDigitsOnly == smsDigitsOnly || 
                            smsDigitsOnly.endsWith(accDigitsOnly) || 
                            accDigitsOnly.endsWith(smsDigitsOnly)
                        )
                    }

                    if (targetAccount != null) {
                        val record = Record(
                            amount = result.amount,
                            category = result.category,
                            type = result.type,
                            accountId = targetAccount.id,
                            accountName = targetAccount.name,
                            currency = targetAccount.currency,
                            userId = userId,
                            timestamp = message.timestamp,
                            smsId = message.id
                        )
                        repository.addRecord(record)
                        _toastMessage.value = "Record added to ${targetAccount.name}!"
                    } else {
                        val record = Record(
                            amount = result.amount,
                            category = result.category,
                            type = result.type,
                            accountName = "Imported: ${result.last4Digits ?: "Unknown Card"}",
                            currency = "EGP",
                            userId = userId,
                            timestamp = message.timestamp,
                            smsId = message.id
                        )
                        repository.addRecord(record)
                        _toastMessage.value = "Account mismatch, added as generic record."
                    }
                } else {
                    _toastMessage.value = "AI did not detect a transaction."
                }
            } catch (e: Exception) {
                _toastMessage.value = "Error: ${e.message}"
            } finally {
                _loadingSmsIds.remove(message.id)
            }
        }
    }

    fun onToastShown() {
        _toastMessage.value = null
    }

    fun fetchSms() {
        observeData()
    }

    private fun isBankSms(body: String): Boolean {
        val keywords = listOf("bank", "debited", "credited", "spent", "transaction", "otp", "account", "visa", "mastercard", "purchase", "transfer", "paid", "egp")
        return keywords.any { body.contains(it, ignoreCase = true) }
    }

    private fun inferType(body: String): String {
        val incomeKeywords = listOf("credited", "received", "deposit", "returned", "salary")
        return if (incomeKeywords.any { body.contains(it, ignoreCase = true) }) "Income" else "Expense"
    }

    private fun extractAmount(body: String): String? {
        val regex = Regex("""(?:EGP|USD|EUR|LE|Amount:?)\s*(\d+[\.,]\d+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(body)
        if (match != null) return match.groupValues[1]
        return Regex("""(\d+[\.,]\d+)""").find(body)?.value
    }

    private fun extractLast4Digits(body: String): String? {
        Regex("""[^0-9\s]{2,}(\d{4})""").find(body)?.let { return it.groupValues[1] }
        Regex("""(?:Account|card|A/c|ending|no\.?)\s*(?:[^0-9\s]+)?(\d{4})""", RegexOption.IGNORE_CASE).find(body)?.let { return it.groupValues[1] }
        val allFourDigits = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        return allFourDigits.find { it.toIntOrNull() !in 1900..2100 } ?: allFourDigits.firstOrNull()
    }
}
