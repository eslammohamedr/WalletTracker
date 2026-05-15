package com.example.wallettrackers.util

import java.util.Calendar

object SmsParser {

    fun isDeclinedTransaction(body: String): Boolean {
        val b = body.lowercase()
        return listOf(
            "transaction declined", "has been declined", "was declined",
            "card declined", "purchase declined", "payment declined",
            "transaction unsuccessful", "transaction failed",
            "payment unsuccessful", "insufficient funds",
            "unable to process your", "could not be processed"
        ).any { b.contains(it) }
    }

    fun isPromotionalSms(body: String): Boolean {
        val b = body.lowercase()
        val promoSignals = listOf(
            // English promo signals
            "t&cs apply", "t&c apply", "terms & conditions", "terms and conditions",
            "installment plan", "no processing fee", "discounted interest",
            "special offer", "limited time", "enjoy up to",
            "apply now", "click here", "for more info", "to know more",
            "call us at", "visit our branch", "download our app",
            // Arabic promo signals
            "زور أقرب فرع", "زور فرع", "افتح محفظة", "سجل الآن", "سجل الان",
            "استمتع ب", "إستمتع ب", "احصل على", "اشترك الآن", "اشترك الان",
            "عرض خاص", "عرض محدود", "قبل تاريخ", "لمزيد من المعلومات",
            "حمل التطبيق", "تنزيل التطبيق"
        )
        val transactionSignals = listOf(
            "debited", "credited", "your account", "avail bal", "available balance",
            "card ending", "a/c no", "withdrawal", "ref no", "transaction id",
            "statement is issued", "minimum due", "due before", "total due", "min. amt due",
            // Arabic transaction signals
            "تم خصم", "تم إيداع", "تم السداد", "رصيد حسابك", "الرصيد المتاح"
        )
        val hasPromo = promoSignals.any { b.contains(it) }
        val hasTransaction = transactionSignals.any { b.contains(it) }
        return hasPromo && !hasTransaction
    }

    fun isNonBankSms(body: String): Boolean {
        val b = body.lowercase()
        val telecomPatterns = listOf(
            // English telecom patterns
            "recharged with", "recharge successful", "recharge of egp", "recharge done",
            "your vodafone", "your orange", "your etisalat", "your we account",
            "data package", "data bundle", "internet bundle", "internet package",
            "mb remaining", "gb remaining", "minutes remaining", "mins remaining",
            "your line has been", "validity extended", "valid until",
            "package activated", "we data", "telecom egypt",
            "scratch card", "nafezni",
            // Arabic telecom patterns
            "فودافون", "أورنج", "اتصالات مصر", "المحمول المصري",
            "اشتراك", "رصيدك", "باقة", "وحدات", "دقائق مجانية",
            "ميجابايت", "ميجابيتس", "جيجابايت",
            "فودافون كاش", "اورنج موني", "محفظة فودافون", "محفظة اورنج"
        )
        if (telecomPatterns.any { b.contains(it) }) return true

        // OTP / verification codes — never financial transactions
        val otpPatterns = listOf(
            "your otp", "your code is", "verification code", "one-time password",
            "رمز التحقق", "كود التفعيل", "رمز المرور", "رمز التأكيد"
        )
        if (otpPatterns.any { b.contains(it) }) return true

        // PIN authorization request — bank is asking the user to approve a pending transaction.
        // The transaction has NOT been completed yet; saving it would be a false record.
        val authRequestPatterns = listOf(
            "use pin", "use your pin", "pin to pay", "enter pin",
            "if you didn't do this", "if you did not do this",
            "if you didn't initiate", "if you did not initiate",
            "if you didn't make this", "if you did not make this",
            "didn't request this", "did not request this",
            "لم تقم بهذه", "لم تطلب هذه", "إذا لم تكن أنت"
        )
        if (authRequestPatterns.any { b.contains(it) }) return true

        return false
    }

    fun isBankSms(body: String): Boolean {
        if (isNonBankSms(body)) return false
        if (isPromotionalSms(body)) return false
        if (isDeclinedTransaction(body)) return false
        val b = body.lowercase()

        val hasAmount = Regex(
            """(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)\s*[\d,]+|[\d,]+\s*(?:EGP|USD|EUR|GBP|SAR|AED|LE)""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(b)

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
            "avail bal", "available balance", "available credit", "available limit", "available now",
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

    fun inferType(body: String): String {
        val b = body.lowercase()

        if (b.contains("cashback") && (b.contains("credited") || b.contains("earned"))) return "Income"

        if (b.contains("total amt due") || b.contains("min. amt due") ||
            b.contains("statement is issued") || b.contains("statement date")) return "Statement"

        if (b.contains("statement") || b.contains("due before") || b.contains("due date")) {
            if (b.contains("amt due") || b.contains("total egp")) return "Statement"
            if (b.contains("check your statement") || b.contains("log on to")) {
                return if (listOf("credited", "received", "earned").any { b.contains(it) }) "Income" else "Expense"
            }
            return "Statement"
        }

        if (!b.contains("cashback")) {
            val hasPaymentAction = b.contains("payment received") || b.contains("payment credited") ||
                b.contains("has been credited") || b.contains("was credited") ||
                b.contains("credited to") || b.contains("received for") ||
                b.contains("was made to") || b.contains("made to your") ||
                b.contains("payment of") && (b.contains("received") || b.contains("credited"))
            val hasCreditRef = b.contains("credit card") || b.contains("your card") ||
                b.contains("credit limit") || b.contains("available credit") ||
                b.contains("bm credit") || b.contains("banq masr")
            if (hasPaymentAction && hasCreditRef) return "CreditCardReceived"
        }

        if (b.contains("made to credit card") ||
            b.contains("for credit card") ||
            (b.contains("transfer") && b.contains("credit card")) ||
            (b.contains("instapay") && b.contains("credit card"))) return "CardPayment"

        if (b.contains("deposit") && b.contains("credit card") && !b.contains("cashback")) return "CreditCardReceived"

        // ATM cash withdrawal — either "withdrawal" keyword or @ATM merchant prefix (QNB format)
        if (b.contains("withdrawal") || Regex("""@atm\b""").containsMatchIn(b)) return "AtmWithdrawal"

        // IPN transfer sent = Expense; received = Income (QNB format uses sent/received, not outward/inward)
        if (b.contains("ipn") && b.contains("sent")) return "Expense"
        if (b.contains("ipn") && b.contains("received")) return "Income"

        val incomeKw = listOf("credited", "received", "deposit", "returned",
            "salary", "tt payment", "ipn inward", "earned cashback")
        if (incomeKw.any { b.contains(it) }) return "Income"

        return "Expense"
    }

    fun inferCategory(body: String): String {
        val b = body.lowercase()
        return when {
            b.contains("cashback") -> "Gifts"
            // Instapay / IPN — QNB uses "sent/received"; other banks use "outward/inward"
            (b.contains("ipn") || b.contains("instapay")) &&
                (b.contains("sent") || b.contains("outward")) -> "Instapay outcome"
            (b.contains("ipn") || b.contains("instapay")) &&
                (b.contains("received") || b.contains("inward")) -> "Instapay income"
            b.contains("salary") || b.contains("tt payment") -> "Salary"
            b.contains("beet elgomla") || b.contains("carrefour") || b.contains("metro market") ||
                b.contains("kheir zaman") || b.contains("lulu") || b.contains("panda") ||
                b.contains("aswak") || b.contains("seoudi") || b.contains("spinneys") -> "Groceries"
            b.contains("uber") || b.contains("careem") || b.contains("indrive") -> "Uber"
            b.contains("netflix") || b.contains("youtube") || b.contains("amazon") ||
                b.contains("spotify") || b.contains("disney") || b.contains("yango") -> "Subscriptions"
            b.contains("flash tech") -> "Electronics"
            b.contains("vodafone") || b.contains("orange") || b.contains("etisalat") ||
                b.contains("we telecom") || b.contains("we-mobile") || b.contains("we-fbb") ||
                b.contains("we-fv") || b.contains("fawry") -> "Mobile"
            b.contains("talabat") -> "Food Delivery"
            b.contains("kfc") || b.contains("mcdonalds") || b.contains("pizza") ||
                b.contains("restaurant") -> "Restaurants"
            b.contains("cafe") || b.contains("coffee") || b.contains("starbucks") -> "Cafe"
            b.contains("pharmacy") || b.contains("el ezaby") || b.contains("elezaby") ||
                b.contains("almokhtbr") || b.contains("el borg") -> "Health and beauty"
            b.contains("fuel") || b.contains("petrol") || b.contains("gas station") -> "Fuel"
            else -> "Others"
        }
    }

    fun inferCurrency(body: String): String {
        val b = body.uppercase()
        return when {
            b.contains("USD") || b.contains("\$") -> "USD"
            b.contains("EUR") || b.contains("€") -> "EUR"
            b.contains("GBP") || b.contains("£") -> "GBP"
            b.contains("SAR") || b.contains("﷼") -> "SAR"
            b.contains("AED") -> "AED"
            else -> "EGP"
        }
    }

    fun inferComment(body: String): String? {
        if (body.contains("cashback", ignoreCase = true)) return "Cashback"
        val toName = Regex("""to\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val fromName = Regex("""from\s+(.*?)\s+with\s+reference""", RegexOption.IGNORE_CASE)
        val atMerchant = Regex("""at\s+(.*?)(?:\.|\s+on|\s+Your|$)""", RegexOption.IGNORE_CASE)
        return toName.find(body)?.groupValues?.get(1)?.trim()
            ?: fromName.find(body)?.groupValues?.get(1)?.trim()
            ?: atMerchant.find(body)?.groupValues?.get(1)?.trim()
    }

    fun extractAmount(body: String): String? {
        val p = """([\d,]+\.\d{2}|\d+[\.,]\d+|[\d\.]+\,\d{2}|\d+)"""
        Regex("""Total Amt Due\s*(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)?\s*$p""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "") }
        Regex("""total\s+(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)?\s*$p""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "") }
        Regex("""(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼|Amount:?|total|Due|Cashback of)\s*$p""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "") }
        if (Regex("""(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£|﷼)""", RegexOption.IGNORE_CASE).containsMatchIn(body))
            return Regex(p).find(body)?.value?.replace(",", "")
        return null
    }

    fun extractLast4Digits(body: String): String? {
        Regex("""Credit Card ending with\s+\*+\s*(\d{4})\b""", RegexOption.IGNORE_CASE).find(body)
            ?.let { return it.groupValues[1] }
        Regex("""Credit Card ending with\s+(\d{4})\b""", RegexOption.IGNORE_CASE).find(body)
            ?.let { return it.groupValues[1] }
        val pattern = """(?:\*+|card|A/c|ending|acc\.?|account|visa|mastercard)\s*[-]?\s*(\d{3,4})\b"""
        val matches = Regex(pattern, RegexOption.IGNORE_CASE).findAll(body).toList()
        if (matches.isNotEmpty()) {
            val starred = matches.find { it.value.contains("*") }
            return starred?.groupValues?.get(1) ?: matches.last().groupValues[1]
        }
        // QNB IPN format: "from XXXX on DD/MM" or "on XXXX on DD/MM" — extract account digits before the date
        Regex("""(?:from|on)\s+(\d{4})\s+on\s+\d{2}/\d{2}""", RegexOption.IGNORE_CASE).find(body)
            ?.let { return it.groupValues[1] }
        val allFour = Regex("""\b\d{4}\b""").findAll(body).map { it.value }.toList()
        val yr = Calendar.getInstance().get(Calendar.YEAR)
        return allFour.find { it.toIntOrNull() !in (yr - 2)..(yr + 5) } ?: allFour.firstOrNull()
    }

    fun extractDueDate(body: String): String? {
        Regex("""[Dd]ue\s+[Dd]ate\s+(\d{1,2}/\d{1,2}/\d{4})""").find(body)
            ?.let { return it.groupValues[1] }
        Regex("""due\s+before\s+(\d{1,2}[/-]\d{1,2}[/-]\d{4})""", RegexOption.IGNORE_CASE).find(body)
            ?.let { return it.groupValues[1] }
        return null
    }

    fun extractBalanceFromSms(body: String): Double? {
        val num = """([\d,]+(?:\.\d{1,2})?)"""
        val cur = """(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£)?\s*"""
        // QNB format: "bal.EGP7.73" or "bal.EGP 1179.03" (dot as separator, no space before currency)
        Regex("""bal\.(?:EGP|USD|EUR|GBP|SAR|AED|LE)\s*$num""", RegexOption.IGNORE_CASE)
            .find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        Regex(
            """(?:avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)|avbl\.?\s*bal|new\s*bal(?:ance)?|current\s*bal(?:ance)?|bal(?:ance)?\s*after|a/c\s*bal|remaining\s*bal(?:ance)?)\s*(?:[:\-.]|is)?\s*$cur$num""",
            RegexOption.IGNORE_CASE
        ).find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        Regex(
            """(?:EGP|USD|EUR|GBP|SAR|AED|LE|\$|€|£)\s*$num\s+(?:is\s+)?(?:your\s+)?avail(?:able)?\s*(?:bal(?:ance)?|credit|limit|now)""",
            RegexOption.IGNORE_CASE
        ).find(body)?.let { return it.groupValues[1].replace(",", "").toDoubleOrNull() }
        return null
    }
}
