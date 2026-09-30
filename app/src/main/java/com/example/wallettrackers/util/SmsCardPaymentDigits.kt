package com.example.wallettrackers.util

data class CardPaymentDigits(val sourceDigits: String?, val creditCardDigits: String?) {
    fun resolveCreditCardDigits(aiLast4Digits: String?): String =
        creditCardDigits
            ?: aiLast4Digits?.filter { it.isDigit() }?.takeUnless { it == sourceDigits }
            ?: ""
}

object SmsCardPaymentDigits {
    fun parse(body: String): CardPaymentDigits {
        val creditDigits = Regex(
            """(?:to\s+)?(?:(?:credit\s+)?card|Ø¨Ø·Ø§Ù‚Ø©(?:\s+Ø§Ø¦ØªÙ…Ø§Ù†ÙŠØ©?)?)\s*(?:ending\s+(?:with\s+)?)?\*{0,4}\s*(\d{3,4})\b""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)

        val sourceDigits = Regex(
            """(?:from\s+)?(?:account|a/c|acc\.?|Ø­Ø³Ø§Ø¨Ùƒ?|Ø­Ø³Ø§Ø¨)\s*(?:no\.?\s*)?\*{0,4}\s*(\d{3,4})\b""",
            RegexOption.IGNORE_CASE
        ).find(body)?.groupValues?.get(1)

        return CardPaymentDigits(sourceDigits, creditDigits)
    }
}
