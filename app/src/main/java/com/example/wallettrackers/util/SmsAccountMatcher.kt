package com.example.wallettrackers.util

import com.example.wallettrackers.model.Account

object SmsAccountMatcher {
    private val institutionAliases = listOf(
        setOf("hsbc"),
        setOf("cib", "commercialinternationalbank"),
        setOf("nbe", "nationalbankofegypt"),
        setOf("qnb", "qnbalahli"),
        setOf("bmisr", "banquemisr"),
        setOf("aaib", "arabafricaninternationalbank"),
        setOf("alexbank", "bankofalexandria"),
        setOf("bdc", "banqueducaire", "banqueducairo"),
        setOf("faisal", "faisalbank"),
        setOf("mashreq"),
        setOf("scb", "standardchartered"),
        setOf("adcb", "abudhabiislamicbank"),
        setOf("emiratesnbd", "enbd")
    )

    fun match(accounts: List<Account>, digits: String, smsBody: String = "", sender: String = ""): Account? {
        if (digits.isBlank()) return null
        val normalizedBody = normalize(smsBody)
        val hasCreditCardEvidence = normalizedBody.contains("creditcard") ||
            (normalizedBody.contains("بطاقة") &&
                (normalizedBody.contains("ائتمانية") || normalizedBody.contains("ائتمان")))
        val candidateAccounts = if (hasCreditCardEvidence && accounts.any { !it.accountType.contains("Credit", ignoreCase = true) } &&
            accounts.any { it.accountType.contains("Credit", ignoreCase = true) }) {
            accounts.filter { it.accountType.contains("Credit", ignoreCase = true) }
        } else accounts
        val candidates = matchingAccounts(candidateAccounts, digits)
        if (candidates.size <= 1) return candidates.singleOrNull()

        val evidence = normalize("$sender $smsBody")
        val identifiedCandidates = candidates.filter { account ->
            val name = normalize(account.name)
            institutionAliases.any { aliases ->
                aliases.any { alias -> name.contains(alias) } &&
                    aliases.any { alias -> evidence.contains(alias) }
            }
        }
        return identifiedCandidates.singleOrNull()
    }

    fun hasAmbiguousMatch(accounts: List<Account>, digits: String): Boolean =
        matchingAccounts(accounts, digits).size > 1

    fun matchByPrintedBalance(accounts: List<Account>, paymentAmount: Double, printedBalance: Double): Account? {
        val matches = accounts.filter { account ->
            !account.accountType.contains("Credit", ignoreCase = true) &&
                account.currency.equals("EGP", ignoreCase = true)
        }.filter { account ->
            val currentBalance = account.amount.toDoubleOrNull() ?: return@filter false
            kotlin.math.abs((currentBalance - paymentAmount) - printedBalance) <= 0.02
        }
        return matches.singleOrNull()
    }

    private fun matchingAccounts(accounts: List<Account>, digits: String): List<Account> =
        accounts.filter { account ->
            val accountDigits = account.last4Digits.filter(Char::isDigit)
            accountDigits.isNotEmpty() &&
                (accountDigits == digits || digits.endsWith(accountDigits) || accountDigits.endsWith(digits))
        }

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)
}
