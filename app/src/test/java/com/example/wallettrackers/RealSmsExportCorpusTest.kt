package com.example.wallettrackers

import com.example.wallettrackers.util.SmsParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Corpus built from the real sms_export.txt (1 088 messages, exported 03/05/2026).
 * Every SMS body below is copied verbatim from the export file.
 * Expected values match the "App extracted" block in the export.
 */
class RealSmsExportCorpusTest {

    // ═══════════════════════════════════════════════════════════
    // HSBC — IPN Inward (credited, from instapay alias)  SMS #2
    // ═══════════════════════════════════════════════════════════

    private val hsbcIpnInwardAlias =
        "Your HSBC Account ********3001 was credited with IPN inward transfer for EGP 1,080.00 " +
        "on 03-05-2026 17:06 from mohamedragab29@instapay with reference 208ad9e4. " +
        "For further details, please contact HSBC call centre"

    @Test fun `HSBC IPN inward alias isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcIpnInwardAlias))
    @Test fun `HSBC IPN inward alias inferType Income`() = assertEquals("Income", SmsParser.inferType(hsbcIpnInwardAlias))
    @Test fun `HSBC IPN inward alias inferCategory Instapay income`() = assertEquals("Instapay income", SmsParser.inferCategory(hsbcIpnInwardAlias))
    @Test fun `HSBC IPN inward alias extractAmount`() = assertEquals("1080.00", SmsParser.extractAmount(hsbcIpnInwardAlias))
    @Test fun `HSBC IPN inward alias extractLast4Digits`() = assertEquals("3001", SmsParser.extractLast4Digits(hsbcIpnInwardAlias))
    @Test fun `HSBC IPN inward alias inferComment captures sender`() = assertEquals("mohamedragab29@instapay", SmsParser.inferComment(hsbcIpnInwardAlias))

    // ═══════════════════════════════════════════════════════════
    // HSBC — IPN Inward (from full person name)  SMS #32
    // ═══════════════════════════════════════════════════════════

    private val hsbcIpnInwardPerson =
        "Your HSBC Account ********3001 was credited with IPN inward transfer for EGP 4,450.00 " +
        "on 22-04-2026 22:18 from AHMED MOHAMED RAGAB MOHAMED with reference adb6a4b5. " +
        "For further details, please contact HSBC call centre"

    @Test fun `HSBC IPN inward person inferType Income`() = assertEquals("Income", SmsParser.inferType(hsbcIpnInwardPerson))
    @Test fun `HSBC IPN inward person extractAmount`() = assertEquals("4450.00", SmsParser.extractAmount(hsbcIpnInwardPerson))
    @Test fun `HSBC IPN inward person inferComment captures full name`() =
        assertEquals("AHMED MOHAMED RAGAB MOHAMED", SmsParser.inferComment(hsbcIpnInwardPerson))

    // ═══════════════════════════════════════════════════════════
    // HSBC — IPN Outward (debited, to recipient name)  SMS #3
    // ═══════════════════════════════════════════════════════════

    private val hsbcIpnOutward =
        "Your HSBC Account ********3001 was debited with IPN outward transfer for EGP 2,202.20 " +
        "on 02-05-2026 16:40 to AHMED MOHAMED AHMED ABDELGHANY MABROUK with reference 7793cc31. " +
        "For further details, please contact HSBC call centre"

    @Test fun `HSBC IPN outward isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcIpnOutward))
    @Test fun `HSBC IPN outward inferType Expense`() = assertEquals("Expense", SmsParser.inferType(hsbcIpnOutward))
    @Test fun `HSBC IPN outward inferCategory Instapay outcome`() = assertEquals("Instapay outcome", SmsParser.inferCategory(hsbcIpnOutward))
    @Test fun `HSBC IPN outward extractAmount`() = assertEquals("2202.20", SmsParser.extractAmount(hsbcIpnOutward))
    @Test fun `HSBC IPN outward extractLast4Digits`() = assertEquals("3001", SmsParser.extractLast4Digits(hsbcIpnOutward))
    @Test fun `HSBC IPN outward inferComment captures recipient`() =
        assertEquals("AHMED MOHAMED AHMED ABDELGHANY MABROUK", SmsParser.inferComment(hsbcIpnOutward))

    // ═══════════════════════════════════════════════════════════
    // HSBC — IPN Outward to numeric alias (e.g. "0160")  SMS #63
    // ═══════════════════════════════════════════════════════════

    private val hsbcIpnOutwardNumeric =
        "Your HSBC Account ********3001 was debited with IPN outward transfer for EGP 10,010.00 " +
        "on 04-04-2026 08:11 to 0160 with reference a9145481. " +
        "For further details, please contact HSBC call centre"

    @Test fun `HSBC IPN outward numeric alias inferType Expense`() = assertEquals("Expense", SmsParser.inferType(hsbcIpnOutwardNumeric))
    @Test fun `HSBC IPN outward numeric alias extractAmount`() = assertEquals("10010.00", SmsParser.extractAmount(hsbcIpnOutwardNumeric))
    @Test fun `HSBC IPN outward numeric alias inferComment captures alias`() =
        assertEquals("0160", SmsParser.inferComment(hsbcIpnOutwardNumeric))

    // ═══════════════════════════════════════════════════════════
    // HSBC — ATM Cash Withdrawal  SMS #5
    // ═══════════════════════════════════════════════════════════

    private val hsbcAtm =
        "From HSBC: 02MAY26 ATM Cash Withdrawal from 074-151***-001 EGP 3,000.00- " +
        "Your available balance is EGP 216,628.28"

    @Test fun `HSBC ATM isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcAtm))
    @Test fun `HSBC ATM inferType AtmWithdrawal`() = assertEquals("AtmWithdrawal", SmsParser.inferType(hsbcAtm))
    @Test fun `HSBC ATM extractAmount`() = assertEquals("3000.00", SmsParser.extractAmount(hsbcAtm))
    @Test fun `HSBC ATM extractBalanceFromSms`() =
        assertEquals(216628.28, SmsParser.extractBalanceFromSms(hsbcAtm)!!, 0.01)

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase EGP (Uber)  SMS #11
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcUber =
        "Your Credit Card ending with *** 2601 has been used for EGP 109.98 " +
        "on 30/04/2026 at Uber. Your available limit is EGP 115648.75"

    @Test fun `HSBC CC EGP Uber isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcCcUber))
    @Test fun `HSBC CC EGP Uber inferType Expense`() = assertEquals("Expense", SmsParser.inferType(hsbcCcUber))
    @Test fun `HSBC CC EGP Uber inferCategory Uber`() = assertEquals("Uber", SmsParser.inferCategory(hsbcCcUber))
    @Test fun `HSBC CC EGP Uber extractAmount`() = assertEquals("109.98", SmsParser.extractAmount(hsbcCcUber))
    @Test fun `HSBC CC EGP Uber extractLast4Digits`() = assertEquals("2601", SmsParser.extractLast4Digits(hsbcCcUber))
    @Test fun `HSBC CC EGP Uber inferComment`() = assertEquals("Uber", SmsParser.inferComment(hsbcCcUber))
    @Test fun `HSBC CC EGP Uber extractBalanceFromSms available limit`() =
        assertEquals(115648.75, SmsParser.extractBalanceFromSms(hsbcCcUber)!!, 0.01)

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase USD (Claude.ai)  SMS #10
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcUsd =
        "Your Credit Card ending with *** 2601 has been used for USD 20.00 " +
        "on 01/05/2026 at CLAUDE.AI SUBSCRIPTION. Your available limit is EGP 114574.63"

    @Test fun `HSBC CC USD isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcCcUsd))
    @Test fun `HSBC CC USD inferType Expense`() = assertEquals("Expense", SmsParser.inferType(hsbcCcUsd))
    @Test fun `HSBC CC USD inferCurrency USD`() = assertEquals("USD", SmsParser.inferCurrency(hsbcCcUsd))
    @Test fun `HSBC CC USD extractAmount`() = assertEquals("20.00", SmsParser.extractAmount(hsbcCcUsd))
    @Test fun `HSBC CC USD extractLast4Digits`() = assertEquals("2601", SmsParser.extractLast4Digits(hsbcCcUsd))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase, talabat (merchant cut at dot)  SMS #26
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcTalabat =
        "Your Credit Card ending with *** 2601 has been used for EGP 233.99 " +
        "on 25/04/2026 at talabat.com. Your available limit is EGP 114669.98"

    @Test fun `HSBC CC talabat extractAmount`() = assertEquals("233.99", SmsParser.extractAmount(hsbcCcTalabat))
    @Test fun `HSBC CC talabat inferComment strips dot suffix`() =
        assertEquals("talabat", SmsParser.inferComment(hsbcCcTalabat))
    @Test fun `HSBC CC talabat inferCategory Restaurants`() =
        assertEquals("Restaurants", SmsParser.inferCategory(hsbcCcTalabat))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase Netflix  SMS #49
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcNetflix =
        "Your Credit Card ending with *** 2601 has been used for EGP 170.00 " +
        "on 10/04/2026 at Netflix.com. Your available limit is EGP 115054.05"

    @Test fun `HSBC CC Netflix inferCategory Subscriptions`() = assertEquals("Subscriptions", SmsParser.inferCategory(hsbcCcNetflix))
    @Test fun `HSBC CC Netflix inferComment strips .com`() = assertEquals("Netflix", SmsParser.inferComment(hsbcCcNetflix))
    @Test fun `HSBC CC Netflix extractAmount`() = assertEquals("170.00", SmsParser.extractAmount(hsbcCcNetflix))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase Google YouTube  SMS #23
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcYoutube =
        "Your Credit Card ending with *** 2601 has been used for EGP 87.99 " +
        "on 26/04/2026 at Google YouTube. Your available limit is EGP 114581.99"

    @Test fun `HSBC CC YouTube inferCategory Subscriptions`() = assertEquals("Subscriptions", SmsParser.inferCategory(hsbcCcYoutube))
    @Test fun `HSBC CC YouTube inferComment`() = assertEquals("Google YouTube", SmsParser.inferComment(hsbcCcYoutube))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase Yango Play  SMS #19
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcYango =
        "Your Credit Card ending with *** 2601 has been used for EGP 199.00 " +
        "on 28/04/2026 at Google Yango Play Mov. Your available limit is EGP 116010.70"

    @Test fun `HSBC CC Yango inferCategory Subscriptions`() = assertEquals("Subscriptions", SmsParser.inferCategory(hsbcCcYango))
    @Test fun `HSBC CC Yango inferComment`() = assertEquals("Google Yango Play Mov", SmsParser.inferComment(hsbcCcYango))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase Amazon Prime  SMS #40
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcAmazon =
        "Your Credit Card ending with *** 2505 has been used for EGP 29.00 " +
        "on 21/04/2026 at AMAZON Prime EG. Your available limit is EGP 95256.42"

    @Test fun `HSBC CC Amazon inferCategory Subscriptions`() = assertEquals("Subscriptions", SmsParser.inferCategory(hsbcCcAmazon))
    @Test fun `HSBC CC Amazon extractLast4Digits`() = assertEquals("2505", SmsParser.extractLast4Digits(hsbcCcAmazon))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card purchase Groceries (BEET ELGOMLA)  SMS #73
    // ═══════════════════════════════════════════════════════════

    private val hsbcCcGroceries =
        "Your Credit Card ending with *** 2505 has been used for EGP 718.80 " +
        "on 30/03/2026 at BEET ELGOMLA. Your available limit is EGP 87342.04"

    @Test fun `HSBC CC Groceries inferCategory`() = assertEquals("Groceries", SmsParser.inferCategory(hsbcCcGroceries))
    @Test fun `HSBC CC Groceries inferComment`() = assertEquals("BEET ELGOMLA", SmsParser.inferComment(hsbcCcGroceries))
    @Test fun `HSBC CC Groceries extractAmount`() = assertEquals("718.80", SmsParser.extractAmount(hsbcCcGroceries))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card Statement  SMS #34
    // ═══════════════════════════════════════════════════════════

    private val hsbcStatement =
        "HSBC Credit Card ending *** 2601 Statement Date 01/04/2026. " +
        "Total Amt Due EGP 1,627.71, Due Date 26/04/2026. Min. Amt Due EGP 115.32. Please ignore if paid."

    @Test fun `HSBC statement isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcStatement))
    @Test fun `HSBC statement inferType Statement`() = assertEquals("Statement", SmsParser.inferType(hsbcStatement))
    @Test fun `HSBC statement extractAmount Total Amt Due`() = assertEquals("1627.71", SmsParser.extractAmount(hsbcStatement))
    @Test fun `HSBC statement extractLast4Digits`() = assertEquals("2601", SmsParser.extractLast4Digits(hsbcStatement))
    @Test fun `HSBC statement extractDueDate`() = assertEquals("26/04/2026", SmsParser.extractDueDate(hsbcStatement))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card Statement (card 2505)  SMS #68
    // ═══════════════════════════════════════════════════════════

    private val hsbcStatement2505 =
        "HSBC Credit Card ending *** 2505 Statement Date 09/03/2026. " +
        "Total Amt Due EGP 8,850.16, Due Date 05/04/2026. Min. Amt Due EGP 378.08. Please ignore if paid."

    @Test fun `HSBC statement 2505 extractAmount`() = assertEquals("8850.16", SmsParser.extractAmount(hsbcStatement2505))
    @Test fun `HSBC statement 2505 extractLast4Digits`() = assertEquals("2505", SmsParser.extractLast4Digits(hsbcStatement2505))
    @Test fun `HSBC statement 2505 extractDueDate`() = assertEquals("05/04/2026", SmsParser.extractDueDate(hsbcStatement2505))
    @Test fun `HSBC statement 2505 is not promotional`() = assertFalse(SmsParser.isPromotionalSms(hsbcStatement2505))

    // ═══════════════════════════════════════════════════════════
    // HSBC — Credit Card Payment (transfer to CC)  SMS #24
    // ═══════════════════════════════════════════════════════════

    private val hsbcCardPayment =
        "From HSBC: 26APR26 Transfer from 074-151***-001 EGP 1,627.71- " +
        "to your Credit Card ending with 2601 as per your instruction. Your available balance is EGP 226,325.28"

    @Test fun `HSBC card payment isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcCardPayment))
    @Test fun `HSBC card payment inferType CardPayment`() = assertEquals("CardPayment", SmsParser.inferType(hsbcCardPayment))
    @Test fun `HSBC card payment extractAmount`() = assertEquals("1627.71", SmsParser.extractAmount(hsbcCardPayment))
    @Test fun `HSBC card payment extractLast4Digits`() = assertEquals("2601", SmsParser.extractLast4Digits(hsbcCardPayment))
    @Test fun `HSBC card payment extractBalanceFromSms`() =
        assertEquals(226325.28, SmsParser.extractBalanceFromSms(hsbcCardPayment)!!, 0.01)

    // ═══════════════════════════════════════════════════════════
    // HSBC — TT Payment (salary credit)  SMS #37
    // ═══════════════════════════════════════════════════════════

    private val hsbcSalary =
        "From HSBC: 21APR26 TT Payment to 074-151***-001 EGP 63,006.08+ " +
        "Your available balance is EGP 223,858.99"

    @Test fun `HSBC salary isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcSalary))
    @Test fun `HSBC salary inferType Income`() = assertEquals("Income", SmsParser.inferType(hsbcSalary))
    @Test fun `HSBC salary inferCategory Salary`() = assertEquals("Salary", SmsParser.inferCategory(hsbcSalary))
    @Test fun `HSBC salary extractAmount`() = assertEquals("63006.08", SmsParser.extractAmount(hsbcSalary))
    @Test fun `HSBC salary extractBalanceFromSms`() =
        assertEquals(223858.99, SmsParser.extractBalanceFromSms(hsbcSalary)!!, 0.01)

    // ═══════════════════════════════════════════════════════════
    // HSBC — Cashback credited  SMS #44
    // ═══════════════════════════════════════════════════════════

    private val hsbcCashback =
        "You have earned Cashback of EGP 18.54 credited to your card ending with *** 2505." +
        "Check your statement or log on to Online Banking for details. T&Cs apply."

    @Test fun `HSBC cashback isBankSms`() = assertTrue(SmsParser.isBankSms(hsbcCashback))
    @Test fun `HSBC cashback inferType Income`() = assertEquals("Income", SmsParser.inferType(hsbcCashback))
    @Test fun `HSBC cashback extractAmount`() = assertEquals("18.54", SmsParser.extractAmount(hsbcCashback))
    @Test fun `HSBC cashback extractLast4Digits`() = assertEquals("2505", SmsParser.extractLast4Digits(hsbcCashback))
    @Test fun `HSBC cashback inferComment`() = assertEquals("Cashback", SmsParser.inferComment(hsbcCashback))

    // ═══════════════════════════════════════════════════════════
    // Banque Misr — Credit Card Purchase EGP  SMS #8
    // ═══════════════════════════════════════════════════════════

    private val bmPurchase =
        "Thank you for using BM credit card *****7000 now debited by EGP 415.25 " +
        "at BEET ELGOMLA               on 01/05/2026 , available now EGP 2786.31 " +
        "For more info join https://bnkmsr.com/online"

    @Test fun `BM purchase isBankSms`() = assertTrue(SmsParser.isBankSms(bmPurchase))
    @Test fun `BM purchase inferType Expense`() = assertEquals("Expense", SmsParser.inferType(bmPurchase))
    @Test fun `BM purchase inferCategory Groceries`() = assertEquals("Groceries", SmsParser.inferCategory(bmPurchase))
    @Test fun `BM purchase extractAmount`() = assertEquals("415.25", SmsParser.extractAmount(bmPurchase))
    @Test fun `BM purchase extractLast4Digits`() = assertEquals("7000", SmsParser.extractLast4Digits(bmPurchase))
    @Test fun `BM purchase inferComment strips trailing spaces`() =
        assertEquals("BEET ELGOMLA", SmsParser.inferComment(bmPurchase))
    @Test fun `BM purchase extractBalanceFromSms available now`() =
        assertEquals(2786.31, SmsParser.extractBalanceFromSms(bmPurchase)!!, 0.01)

    // ═══════════════════════════════════════════════════════════
    // Banque Misr — CC Purchase with integer amount  SMS #9
    // ═══════════════════════════════════════════════════════════

    private val bmPurchaseInt =
        "Thank you for using BM credit card *****7000 now debited by EGP 700 " +
        "at FAWRY*MALM ALAJYAL 1       on 01/05/2026 , available now EGP 3201.56 " +
        "For more info join https://bnkmsr.com/online"

    @Test fun `BM purchase integer amount isBankSms`() = assertTrue(SmsParser.isBankSms(bmPurchaseInt))
    @Test fun `BM purchase integer amount extractAmount`() = assertEquals("700", SmsParser.extractAmount(bmPurchaseInt))
    @Test fun `BM purchase integer amount inferCategory Mobile (Fawry)`() =
        assertEquals("Mobile", SmsParser.inferCategory(bmPurchaseInt))
    @Test fun `BM purchase integer extractBalanceFromSms`() =
        assertEquals(3201.56, SmsParser.extractBalanceFromSms(bmPurchaseInt)!!, 0.01)

    // ═══════════════════════════════════════════════════════════
    // Banque Misr — Statement (amount BEFORE EGP, hyphen date)  SMS #1
    // already in RealSmsBankCorpusTest — only add extractDueDate here
    // ═══════════════════════════════════════════════════════════

    private val bmStatementHyphen =
        "Dear customer, your card ****7000 statement is issued with total 8039.88 EGP, " +
        "minimum due is 401.99 EGP, due before 26-05-2026 " +
        "For more info, https://bnkmsr.com/online or call 19888"

    @Test fun `BM statement hyphen date extractDueDate`() =
        assertEquals("26-05-2026", SmsParser.extractDueDate(bmStatementHyphen))
    @Test fun `BM statement hyphen extractAmount picks total not minimum`() =
        assertEquals("8039.88", SmsParser.extractAmount(bmStatementHyphen))

    // ═══════════════════════════════════════════════════════════
    // Banque Misr — Statement (EGP BEFORE amount, slash date)  SMS #67
    // ═══════════════════════════════════════════════════════════

    private val bmStatementSlash =
        "Dear customer, your card ****7000 statement is issued with total EGP 6643.33, " +
        "minimum due is EGP 332.17, due before 26/04/2026 " +
        "For more info, https://bnkmsr.com/online or call 19888"

    @Test fun `BM statement slash isBankSms`() = assertTrue(SmsParser.isBankSms(bmStatementSlash))
    @Test fun `BM statement slash inferType Statement`() = assertEquals("Statement", SmsParser.inferType(bmStatementSlash))
    @Test fun `BM statement slash extractAmount`() = assertEquals("6643.33", SmsParser.extractAmount(bmStatementSlash))
    @Test fun `BM statement slash extractLast4Digits`() = assertEquals("7000", SmsParser.extractLast4Digits(bmStatementSlash))
    @Test fun `BM statement slash extractDueDate`() = assertEquals("26/04/2026", SmsParser.extractDueDate(bmStatementSlash))
    @Test fun `BM statement slash is not promotional`() = assertFalse(SmsParser.isPromotionalSms(bmStatementSlash))

    // ═══════════════════════════════════════════════════════════
    // Banque Misr — Credit Card deposit received  SMS #58
    // ═══════════════════════════════════════════════════════════

    private val bmCcReceived =
        "Deposit of EGP 10000 was made to BM credit card ending ****7000 at BM-Online on 06/04/2026." +
        "Your current balance is EGP 9594.18." +
        "To view transactions, visit bnkmsr.com/online."

    @Test fun `BM CC received isBankSms`() = assertTrue(SmsParser.isBankSms(bmCcReceived))
    @Test fun `BM CC received inferType CreditCardReceived`() = assertEquals("CreditCardReceived", SmsParser.inferType(bmCcReceived))
    @Test fun `BM CC received extractAmount`() = assertEquals("10000", SmsParser.extractAmount(bmCcReceived))
    @Test fun `BM CC received extractLast4Digits`() = assertEquals("7000", SmsParser.extractLast4Digits(bmCcReceived))
    @Test fun `BM CC received extractBalanceFromSms current balance`() =
        assertEquals(9594.18, SmsParser.extractBalanceFromSms(bmCcReceived)!!, 0.01)

    // ═══════════════════════════════════════════════════════════
    // Banque Misr — negative available balance  SMS #60
    // ═══════════════════════════════════════════════════════════

    private val bmNegativeBalance =
        "Thank you for using BM credit card *****7000 now debited by EGP 184 " +
        "at BEET ELGOMLA               on 06/04/2026 , available now EGP -405.82 " +
        "For more info join https://bnkmsr.com/online"

    @Test fun `BM negative balance isBankSms`() = assertTrue(SmsParser.isBankSms(bmNegativeBalance))
    @Test fun `BM negative balance inferType Expense`() = assertEquals("Expense", SmsParser.inferType(bmNegativeBalance))
    @Test fun `BM negative balance extractAmount`() = assertEquals("184", SmsParser.extractAmount(bmNegativeBalance))

    // ═══════════════════════════════════════════════════════════
    // Non-bank / promotional — must all return false for isBankSms
    // ═══════════════════════════════════════════════════════════

    @Test fun `HSBC installment promo isBankSms false`() {
        assertFalse(SmsParser.isBankSms(
            "HSBC: Enjoy 0% installment plan on your purchases. No processing fee. T&Cs apply."
        ))
    }

    @Test fun `OTP SMS isBankSms false`() {
        assertFalse(SmsParser.isBankSms(
            "Your OTP is 847291. Valid for 5 minutes. Do not share with anyone."
        ))
    }

    @Test fun `BM declined transaction isBankSms false`() {
        assertFalse(SmsParser.isBankSms(
            "Your transaction of EGP 500 on BM card *****7000 has been declined. Insufficient funds."
        ))
    }
}
