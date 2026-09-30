package com.example.wallettrackers

import com.example.wallettrackers.model.Categories
import com.example.wallettrackers.util.SmsParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Data-driven regression test over the FULL real export (`app/src/test/resources/sms_export.txt`,
 * 1 088 real bank SMS).
 *
 * Unlike [RealSmsExportCorpusTest] (which hard-codes a hand-picked subset), this test parses every
 * message in the export and runs the live parser over each one to surface defects in bulk:
 *   - invariants that must hold for ALL bodies (e.g. never emit a category the app doesn't have —
 *     this is exactly the class of bug where `inferCategory` returned "Car", which isn't a real
 *     category), and
 *   - regressions against the export's OBJECTIVE fields (amount, last-4 digits), which are
 *     deterministic and independent of AI/user-rule classification.
 *
 * Category equality is intentionally NOT asserted: the export's "Category" reflects whatever the app
 * saved (AI- or rule-adjusted, e.g. "Lending"/"Rent"/"Transfer"/"Credit Card") and cannot be
 * reproduced by the keyword parser alone.
 */
class RealSmsExportFileTest {

    // ── Parsed representation of one SMS block in the export ──────────────────
    private data class Entry(
        val number: Int,
        val sender: String,
        val tracked: Boolean,
        val body: String,
        val expType: String,
        val expCategory: String,
        val expAmount: String,
        val expDigits: String,
    )

    private val entries: List<Entry> by lazy { loadEntries() }

    private fun loadEntries(): List<Entry> {
        val text = javaClass.classLoader!!.getResourceAsStream("sms_export.txt")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }

        fun field(src: String, key: String): String =
            Regex("(?m)^${Regex.escape(key)}\\s*:\\s*(.*)$").find(src)?.groupValues?.get(1)?.trim() ?: ""

        // Blocks are delimited by a line of U+2500 box-drawing chars ("──────…").
        val chunks = text.split(Regex("(?m)^─{3,}\\s*$"))
        val result = mutableListOf<Entry>()
        for (chunk in chunks) {
            val number = Regex("SMS #(\\d+)").find(chunk)?.groupValues?.get(1)?.toIntOrNull()
                ?: continue // skip the file header chunk

            val parts = chunk.split("--- App extracted ---", limit = 2)
            val head = parts[0]
            val tail = parts.getOrElse(1) { "" }

            val body = Regex("(?ms)^Body\\s*:\\s*\\n(.*)$").find(head)?.groupValues?.get(1)?.trim() ?: ""
            result += Entry(
                number = number,
                sender = field(head, "Sender"),
                tracked = field(head, "Status").contains("✓"),
                body = body,
                expType = field(tail, "Type"),
                expCategory = field(tail, "Category"),
                expAmount = field(tail, "Amount"),
                expDigits = field(tail, "Digits"),
            )
        }
        return result
    }

    // Every category name the app actually offers (top-level + sub), plus the synthetic
    // categories the special save-paths assign for non-inferCategory transaction types.
    private val knownCategories: Set<String> =
        (Categories.list.flatMap { listOf(it.name) + it.subCategories.map { s -> s.name } }).toSet()

    private val validTypes = setOf(
        "Income", "Expense", "Statement", "CardPayment", "CreditCardReceived", "AtmWithdrawal"
    )
    private val validCurrencies = setOf("EGP", "USD", "EUR", "GBP", "SAR", "AED")

    @Test
    fun `write full SMS export parser evaluation`() {
        fun quote(value: String?): String = value?.let {
            "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n") + "\""
        } ?: "null"

        val output = File("build/reports/sms-export-evaluation.json")
        requireNotNull(output.parentFile).mkdirs()
        output.writeText(buildString {
            appendLine("{")
            appendLine("  \"source\": \"app/src/test/resources/sms_export.txt\",")
            appendLine("  \"message_count\": ${entries.size},")
            appendLine("  \"interpretation\": \"Archived App extracted fields are historical outputs, not independent ground truth. Deltas require review.\",")
            appendLine("  \"messages\": [")
            entries.forEachIndexed { index, entry ->
                val amount = SmsParser.extractAmount(entry.body)
                appendLine("    {")
                appendLine("      \"number\": ${entry.number},")
                appendLine("      \"sender\": ${quote(entry.sender)},")
                appendLine("      \"tracked_in_export\": ${entry.tracked},")
                appendLine("      \"body\": ${quote(entry.body)},")
                appendLine("      \"expected\": {\"type\": ${quote(entry.expType)}, \"category\": ${quote(entry.expCategory)}, \"amount\": ${quote(entry.expAmount)}, \"digits\": ${quote(entry.expDigits)}},")
                appendLine("      \"actual\": {\"bank_sms\": ${SmsParser.isBankSms(entry.body, entry.sender)}, \"promotional\": ${SmsParser.isPromotionalSms(entry.body)}, \"non_bank\": ${SmsParser.isNonBankSms(entry.body)}, \"declined\": ${SmsParser.isDeclinedTransaction(entry.body)}, \"type\": ${quote(SmsParser.inferType(entry.body))}, \"category\": ${quote(SmsParser.inferCategory(entry.body))}, \"amount\": ${quote(amount)}, \"currency\": ${quote(SmsParser.inferCurrency(entry.body))}, \"digits\": ${quote(SmsParser.extractLast4Digits(entry.body))}, \"comment\": ${quote(SmsParser.inferComment(entry.body))}, \"balance\": ${SmsParser.extractBalanceFromSms(entry.body) ?: "null"}},")
                appendLine("      \"comparison\": {\"bank_status_differs\": ${entry.tracked != SmsParser.isBankSms(entry.body, entry.sender)}, \"type_differs\": ${entry.expType.isNotBlank() && entry.expType != SmsParser.inferType(entry.body)}, \"category_differs\": ${entry.expCategory.isNotBlank() && entry.expCategory != SmsParser.inferCategory(entry.body)}, \"amount_differs\": ${entry.expAmount.isNotBlank() && entry.expAmount.replace(",", "").toDoubleOrNull() != amount?.replace(",", "")?.toDoubleOrNull()}, \"digits_differs\": ${entry.expDigits.filter { it.isDigit() }.isNotBlank() && entry.expDigits.filter { it.isDigit() } != SmsParser.extractLast4Digits(entry.body)?.filter { it.isDigit() }}}")
                append("    }")
                appendLine(if (index == entries.lastIndex) "" else ",")
            }
            appendLine("  ]")
            appendLine("}")
        }, Charsets.UTF_8)
        println("Wrote ${entries.size} per-message comparisons to ${output.absolutePath}")
        assertEquals("Expected all messages from the SMS export to be evaluated", 1088, entries.size)
    }

    // ── Sanity: the export parsed ────────────────────────────────────────────

    @Test
    fun `export file parses into the expected number of messages`() {
        // The header says "Total messages: 1088"
        assertTrue("Parsed only ${entries.size} entries", entries.size >= 1000)
        assertTrue("Some bodies came back blank", entries.all { it.body.isNotBlank() })
    }

    // ── Invariant: parser never crashes on real input ────────────────────────

    @Test
    fun `no parser function throws on any real body`() {
        val failures = mutableListOf<String>()
        for (e in entries) {
            try {
                SmsParser.isBankSms(e.body, e.sender)
                SmsParser.isPromotionalSms(e.body)
                SmsParser.isNonBankSms(e.body)
                SmsParser.isDeclinedTransaction(e.body)
                SmsParser.inferType(e.body)
                SmsParser.inferCategory(e.body)
                SmsParser.inferCurrency(e.body)
                SmsParser.inferComment(e.body)
                SmsParser.extractAmount(e.body)
                SmsParser.extractLast4Digits(e.body)
                SmsParser.extractDueDate(e.body)
                SmsParser.extractBalanceFromSms(e.body)
            } catch (t: Throwable) {
                failures += "SMS #${e.number}: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        assertTrue("Parser threw on:\n${failures.joinToString("\n")}", failures.isEmpty())
    }

    // ── Advertising / marketing SMS must NOT be treated as bank transactions ──

    @Test
    fun `bank advertising SMS are rejected by isBankSms`() {
        val ads = listOf(
            "Dear customer, you are eligible for a personal loan up to EGP 500,000 with 0% interest. Apply now, call 19888",
            "Congratulations! Your card ****7000 is pre-approved for a credit limit increase. Apply now at https://bnkmsr.com",
            "Special offer! Pay your purchases in installments with 0% interest. Use code SAVE now.",
            "Win a brand new car! Use your CIB credit card and enter the lucky draw. T&Cs apply.",
            "عميلنا العزيز، أنت مؤهل للحصول على قرض شخصي حتى 500,000 جنيه بدون فوائد. اتصل الآن",
            "احصل على بطاقة ائتمان بنك مصر بالتقسيط وبدون مقدم. عرض خاص لفترة محدودة",
        )
        val notRejected = ads.filter { SmsParser.isBankSms(it, "Banque Misr") }
        assertTrue("These ads were wrongly accepted as bank SMS:\n${notRejected.joinToString("\n")}",
            notRejected.isEmpty())
    }

    @Test
    fun `real transactions and statements are not mistaken for ads`() {
        val legit = listOf(
            "Thank you for using BM credit card *****7000 now debited by EGP 415.25 at BEET ELGOMLA on 01/05/2026 , available now EGP 2786.31 For more info join https://bnkmsr.com/online",
            "Dear customer, your card ****7000 statement is issued with total 8039.88 EGP, minimum due is 401.99 EGP, due before 26-05-2026 For more info, https://bnkmsr.com",
            "Your Credit Card ending with *** 2601 has been used for EGP 170.00 on 10/04/2026 at Netflix.com. Your available limit is EGP 115054.05",
            "Your HSBC Account ********3001 was credited with IPN inward transfer for EGP 1,080.00 on 03-05-2026 from x@instapay with reference 208ad9e4",
        )
        val wronglyFlagged = legit.filter { SmsParser.isAdvertisement(it) }
        assertTrue("These real messages were wrongly flagged as ads:\n${wronglyFlagged.joinToString("\n")}",
            wronglyFlagged.isEmpty())
    }

    // ── Invariant: inferCategory only ever returns a real category ───────────
    // This is the exact bug class we hit with "Car" (not a category in the model).

    @Test
    fun `inferCategory only returns categories that exist in the app`() {
        val bad = entries
            .map { it to SmsParser.inferCategory(it.body) }
            .filter { (_, cat) -> cat !in knownCategories }
            .map { (e, cat) -> "SMS #${e.number}: '$cat'  — ${e.body.take(70)}" }
            .distinct()
        assertTrue(
            "inferCategory produced ${bad.size} unknown categories:\n${bad.joinToString("\n")}",
            bad.isEmpty()
        )
    }

    // Merchant keyword coverage — real Egyptian merchants that previously fell into "Others".
    // Format mirrors the real HSBC credit-card SMS in the export.
    @Test
    fun `inferCategory maps known Egyptian merchants`() {
        fun cc(merchant: String) =
            "Your Credit Card ending with *** 2505 has been used for EGP 120.00 on 01/05/2026 at $merchant. Your available limit is EGP 9000.00"
        val cases = mapOf(
            "Best Way" to "Snacks",
            "DEFACTO-ALEXANDRIA CITY" to "Clothes",
            "LC WAIKIKI - ALEXANDRIA" to "Clothes",
            "PAYMOB*SEVEN SECRETS" to "Clothes",
            "NOON EGYPT" to "Shopping",
            "UDEMY SUBSCRIPTION" to "Courses",
            "BTECH" to "Electronics",
            "2B SAN STEFANO MALL" to "Electronics",
            "MYFATOORAHH*ZYDA" to "Food Delivery",
            "WAFFARHA" to "Food Delivery",
            "HUNGERSTATION" to "Food Delivery",
            "DUNKIN DONUTS GATE MALL" to "Cafe",
            "CINNABON DANDY MALL" to "Cafe",
            "BALBAA VILLAGE" to "Restaurants",
            "GEIDEA*AL ASDKAA RES" to "Restaurants",
            "ROFAYDA HOSPITAL" to "Hospital",
            "EA *ELECTRONIC ARTS" to "Games",
            "BAIT ELGOMLA 30TH ST" to "Groceries",
            "BIN DAWOOD STORES" to "Groceries",
        )
        val wrong = cases.entries.mapNotNull { (merchant, expected) ->
            val got = SmsParser.inferCategory(cc(merchant))
            if (got != expected) "'$merchant' -> got '$got', expected '$expected'" else null
        }
        assertTrue("Merchant mapping mismatches:\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun `inferType only returns valid type tokens`() {
        val bad = entries
            .map { it to SmsParser.inferType(it.body) }
            .filter { (_, t) -> t !in validTypes }
            .map { (e, t) -> "SMS #${e.number}: '$t'" }
        assertTrue("inferType produced invalid tokens:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    @Test
    fun `inferCurrency only returns valid ISO codes`() {
        val bad = entries
            .map { it to SmsParser.inferCurrency(it.body) }
            .filter { (_, c) -> c !in validCurrencies }
            .map { (e, c) -> "SMS #${e.number}: '$c'" }
        assertTrue("inferCurrency produced invalid codes:\n${bad.joinToString("\n")}", bad.isEmpty())
    }

    // ── Amount invariant ─────────────────────────────────────────────────────
    // The export's stored Amount is NOT trustworthy ground truth: in real data it sometimes
    // captured the available limit/balance (e.g. SMS #946 "available limit is EGP 5980.69")
    // instead of the transaction amount. So we assert objective invariants instead:
    //   1. tracked Income/Expense messages must yield SOME amount (else the record is dropped), and
    //   2. whatever amount is returned must actually be a number present in the body.

    @Test
    fun `extractAmount returns a body-present number for tracked transactions`() {
        val drops = mutableListOf<String>()
        val phantom = mutableListOf<String>()
        var checked = 0
        for (e in entries) {
            if (!e.tracked) continue
            if (e.expType != "Income" && e.expType != "Expense") continue
            val b = e.body.lowercase()
            if (b.contains("use pin") || b.contains("use your pin")) continue // not completed yet
            checked++
            val actual = SmsParser.extractAmount(e.body)
            if (actual == null) {
                drops += "SMS #${e.number}: ${e.body.take(80)}"
                continue
            }
            val digitsOnly = e.body.replace(",", "")
            if (!digitsOnly.contains(actual.replace(",", ""))) {
                phantom += "SMS #${e.number}: got='$actual' not in body — ${e.body.take(80)}"
            }
        }
        println("extractAmount invariant: checked=$checked drops=${drops.size} phantom=${phantom.size}")
        assertTrue("extractAmount returned null for tracked transactions:\n" +
            drops.take(40).joinToString("\n"), drops.isEmpty())
        assertTrue("extractAmount returned a value not present in the body:\n" +
            phantom.take(40).joinToString("\n"), phantom.isEmpty())
    }

    @Test
    fun `reviewed historical amount mismatches resolve to transaction amounts`() {
        val reviewedAmounts = mapOf(
            883 to "6700", 935 to "6630", 946 to "55.00", 947 to "276.00",
            948 to "131.50", 950 to "60.00", 956 to "520.06", 957 to "395.00",
            958 to "395.00", 959 to "395.00", 960 to "395.00", 989 to "6700",
            1049 to "6630"
        )
        val mismatches = entries.filter { entry ->
            val exported = entry.expAmount.replace(",", "").toDoubleOrNull()
            val parsed = SmsParser.extractAmount(entry.body)?.replace(",", "")?.toDoubleOrNull()
            exported != null && parsed != exported
        }.associate { it.number to it }

        assertEquals("Unexpected set of historical amount deltas", reviewedAmounts.keys, mismatches.keys)
        reviewedAmounts.forEach { (number, expectedAmount) ->
            assertEquals("Wrong transaction amount for SMS #$number", expectedAmount.toDouble(),
                SmsParser.extractAmount(mismatches.getValue(number).body)?.toDoubleOrNull() ?: Double.NaN, 0.001)
        }
    }

    @Test
    fun `reviewed historical type deltas map to valid specialized transaction types`() {
        val mismatches = entries.filter { entry ->
            entry.expType.isNotBlank() && entry.expType != SmsParser.inferType(entry.body)
        }
        val byCurrentType = mismatches.groupBy { SmsParser.inferType(it.body) }

        assertEquals(82, mismatches.size)
        assertEquals(74, byCurrentType["AtmWithdrawal"]?.size)
        assertEquals(4, byCurrentType["Income"]?.size)
        assertEquals(4, byCurrentType["CreditCardReceived"]?.size)
        assertTrue(byCurrentType.getValue("AtmWithdrawal").all {
            it.body.contains("ATM", ignoreCase = true) && it.body.contains("withdraw", ignoreCase = true)
        })
        assertTrue(byCurrentType.getValue("Income").all { it.body.contains("Transfer to", ignoreCase = true) && it.body.contains("+") })
        assertTrue(byCurrentType.getValue("CreditCardReceived").all {
            it.expDigits == "7000" && SmsParser.extractAmount(it.body) in setOf("6700", "6630")
        })
    }

    // ── Regression vs OBJECTIVE ground truth: last-4 digits ──────────────────

    @Test
    fun `extractLast4Digits reproduces the exported digits`() {
        val mismatches = mutableListOf<String>()
        var checked = 0
        for (e in entries) {
            val expected = e.expDigits.filter { it.isDigit() }
            if (expected.isEmpty()) continue
            // Only assert when the exported digits actually appear in the body. Some exports carry
            // the linked account's digits (resolved via account matching), which need not be the
            // 3-4 digits printed in this particular SMS.
            if (!e.body.replace(" ", "").contains(expected)) continue
            checked++
            val actual = SmsParser.extractLast4Digits(e.body)?.filter { it.isDigit() } ?: ""
            if (actual != expected) {
                mismatches += "SMS #${e.number}: expected=$expected got='$actual' — ${e.body.take(80)}"
            }
        }
        println("extractLast4Digits: checked=$checked mismatches=${mismatches.size}")
        assertTrue(
            "extractLast4Digits diverged from the export on ${mismatches.size}/$checked messages:\n" +
                mismatches.take(60).joinToString("\n"),
            mismatches.isEmpty()
        )
    }

    // ── Focused regression: real Arabic BM deposit (SMS #883) ────────────────
    // Was misclassified as promotional (rejected) AND its amount was read as the printed
    // balance. Lock in both fixes.

    @Test
    fun `arabic BM deposit is tracked and amount is the deposit not the balance`() {
        val body = "شكرا لاستخدامكم بطاقة بنك مصر الائتمانية ****7000   تم ايداع 6700 EGP   " +
            "فى BM-Online يوم  25/03/2025 متاح الان EGP  106280.69   للمزيد من المعلومات join https://bnkmsr.com/online"
        assertTrue("Arabic BM deposit should be recognised as a bank SMS",
            SmsParser.isBankSms(body, "Banque Misr"))
        assertFalse("Arabic BM deposit should not be treated as promotional",
            SmsParser.isPromotionalSms(body))
        assertEquals("6700", SmsParser.extractAmount(body))
        assertEquals("CreditCardReceived", SmsParser.inferType(body))
        assertEquals(106280.69, SmsParser.extractBalanceFromSms(body)!!, 0.001)
    }

    // ── Regression: bank transactions should yield an amount ─────────────────
    // If the export tracked it as a real Income/Expense with an amount, the parser
    // must also recover an amount (otherwise the transaction would be dropped).

    @Test
    fun `tracked income-expense messages with an amount are recognised as bank SMS`() {
        val misses = mutableListOf<String>()
        var checked = 0
        for (e in entries) {
            if (!e.tracked) continue
            if (e.expType != "Income" && e.expType != "Expense") continue
            if (e.expAmount.replace(",", "").toDoubleOrNull() == null) continue
            // PIN-to-pay authorization requests are intentionally rejected now (they are not yet
            // completed transactions — see commit "fix use pin to pay sms"), even though older
            // exports had tracked them. Exclude them from the recall check.
            val b = e.body.lowercase()
            if (b.contains("use pin") || b.contains("use your pin")) continue
            checked++
            if (!SmsParser.isBankSms(e.body, e.sender)) {
                misses += "SMS #${e.number} (${e.sender}): ${e.body.take(80)}"
            }
        }
        println("isBankSms recall: checked=$checked misses=${misses.size}")
        assertTrue(
            "isBankSms rejected ${misses.size}/$checked tracked transactions:\n" +
                misses.take(60).joinToString("\n"),
            misses.isEmpty()
        )
    }
}
