package com.example.wallettrackers

import com.example.wallettrackers.util.SmsParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression corpus using real Egyptian bank SMS formats.
 * Each test is anchored to an actual message pattern from a specific bank.
 * Covers SMS-F-002 through SMS-F-008, SMS-F-013, SMS-F-014.
 */
class RealSmsBankCorpusTest {

    // ═══════════════════════════════════════════════════════
    // CIB — Commercial International Bank
    // ═══════════════════════════════════════════════════════

    @Test
    fun `CIB debit isBankSms`() {
        val sms = "Dear Client, your A/c 1234 has been debited EGP 500.00. Avail Bal EGP 4,500.00 on 01-05-2026."
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `CIB debit inferType is Expense`() {
        val sms = "Dear Client, your A/c 1234 has been debited EGP 500.00. Avail Bal EGP 4,500.00 on 01-05-2026."
        assertEquals("Expense", SmsParser.inferType(sms))
    }

    @Test
    fun `CIB debit extractAmount`() {
        val sms = "Dear Client, your A/c 1234 has been debited EGP 500.00. Avail Bal EGP 4,500.00 on 01-05-2026."
        assertEquals("500.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `CIB debit extractLast4Digits`() {
        val sms = "Dear Client, your A/c 1234 has been debited EGP 500.00. Avail Bal EGP 4,500.00 on 01-05-2026."
        assertEquals("1234", SmsParser.extractLast4Digits(sms))
    }

    @Test
    fun `CIB debit extractBalanceFromSms`() {
        val sms = "Dear Client, your A/c 1234 has been debited EGP 500.00. Avail Bal EGP 4,500.00 on 01-05-2026."
        assertEquals(4500.0, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    @Test
    fun `CIB salary credit isBankSms`() {
        val sms = "Dear Client, your A/c 1234 has been credited EGP 15,000.00 (Salary). Avail Bal EGP 15,000.00."
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `CIB salary credit inferType is Income`() {
        val sms = "Dear Client, your A/c 1234 has been credited EGP 15,000.00 (Salary). Avail Bal EGP 15,000.00."
        assertEquals("Income", SmsParser.inferType(sms))
    }

    @Test
    fun `CIB salary credit extractAmount`() {
        val sms = "Dear Client, your A/c 1234 has been credited EGP 15,000.00 (Salary). Avail Bal EGP 15,000.00."
        assertEquals("15000.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `CIB card purchase isBankSms`() {
        val sms = "EGP 250.00 has been charged to your CIB card **1234 at CARREFOUR EGYPT. Avail Bal EGP 7,750.00"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `CIB card purchase inferType is Expense`() {
        val sms = "EGP 250.00 has been charged to your CIB card **1234 at CARREFOUR EGYPT. Avail Bal EGP 7,750.00"
        assertEquals("Expense", SmsParser.inferType(sms))
    }

    @Test
    fun `CIB card purchase inferCategory is Groceries`() {
        val sms = "EGP 250.00 has been charged to your CIB card **1234 at CARREFOUR EGYPT. Avail Bal EGP 7,750.00"
        assertEquals("Groceries", SmsParser.inferCategory(sms))
    }

    @Test
    fun `CIB card purchase extractLast4Digits from starred card`() {
        val sms = "EGP 250.00 has been charged to your CIB card **1234 at CARREFOUR EGYPT. Avail Bal EGP 7,750.00"
        assertEquals("1234", SmsParser.extractLast4Digits(sms))
    }

    @Test
    fun `CIB ATM withdrawal inferType is AtmWithdrawal`() {
        val sms = "Cash withdrawal EGP 2,000.00 from ATM. Card **5678. Avail Bal EGP 8,000.00"
        assertEquals("AtmWithdrawal", SmsParser.inferType(sms))
    }

    @Test
    fun `CIB ATM withdrawal extractAmount`() {
        val sms = "Cash withdrawal EGP 2,000.00 from ATM. Card **5678. Avail Bal EGP 8,000.00"
        assertEquals("2000.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `CIB credit card statement isBankSms`() {
        val sms = "Your CIB credit card statement is ready. Total Amt Due EGP 5,200.00. Min. Amt Due EGP 500.00. Due Date 15-06-2026"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `CIB credit card statement inferType is Statement`() {
        val sms = "Your CIB credit card statement is ready. Total Amt Due EGP 5,200.00. Min. Amt Due EGP 500.00. Due Date 15-06-2026"
        assertEquals("Statement", SmsParser.inferType(sms))
    }

    @Test
    fun `CIB credit card statement extractAmount picks Total Amt Due`() {
        val sms = "Your CIB credit card statement is ready. Total Amt Due EGP 5,200.00. Min. Amt Due EGP 500.00. Due Date 15-06-2026"
        assertEquals("5200.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `CIB credit card payment inferType is CardPayment`() {
        val sms = "EGP 5,200.00 debited from A/c 1234 for credit card payment. Avail Bal EGP 3,300.00"
        assertEquals("CardPayment", SmsParser.inferType(sms))
    }

    @Test
    fun `CIB payment received on credit card inferType is CreditCardReceived`() {
        val sms = "Payment of EGP 5,200.00 has been received for your credit card ending 9999. Thank you."
        assertEquals("CreditCardReceived", SmsParser.inferType(sms))
    }

    @Test
    fun `CIB payment received extractLast4Digits`() {
        val sms = "Payment of EGP 5,200.00 has been received for your credit card ending 9999. Thank you."
        assertEquals("9999", SmsParser.extractLast4Digits(sms))
    }

    // ═══════════════════════════════════════════════════════
    // Banque Misr (BM)
    // ═══════════════════════════════════════════════════════

    @Test
    fun `BM account debit isBankSms`() {
        val sms = "BM: Your account ending 1234 has been debited EGP 300.00. Available Balance EGP 2,700.00"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `BM account debit inferType is Expense`() {
        val sms = "BM: Your account ending 1234 has been debited EGP 300.00. Available Balance EGP 2,700.00"
        assertEquals("Expense", SmsParser.inferType(sms))
    }

    @Test
    fun `BM account debit extractBalanceFromSms`() {
        val sms = "BM: Your account ending 1234 has been debited EGP 300.00. Available Balance EGP 2,700.00"
        assertEquals(2700.0, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    @Test
    fun `BM instapay inward isBankSms`() {
        val sms = "BM: IPN Inward transfer of EGP 1,000.00 received from Ahmed Mohamed. Avail Bal EGP 6,000.00"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `BM instapay inward inferType is Income`() {
        val sms = "BM: IPN Inward transfer of EGP 1,000.00 received from Ahmed Mohamed. Avail Bal EGP 6,000.00"
        assertEquals("Income", SmsParser.inferType(sms))
    }

    @Test
    fun `BM instapay inward inferCategory is Instapay income`() {
        val sms = "BM: IPN Inward transfer of EGP 1,000.00 received from Ahmed Mohamed. Avail Bal EGP 6,000.00"
        assertEquals("Instapay income", SmsParser.inferCategory(sms))
    }

    @Test
    fun `BM instapay inward extractAmount`() {
        val sms = "BM: IPN Inward transfer of EGP 1,000.00 received from Ahmed Mohamed. Avail Bal EGP 6,000.00"
        assertEquals("1000.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `BM instapay outward isBankSms`() {
        val sms = "BM: IPN Outward transfer of EGP 500.00 sent to Mohamed Ali. Avail Bal EGP 5,500.00"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `BM instapay outward inferType is Expense`() {
        val sms = "BM: IPN Outward transfer of EGP 500.00 sent to Mohamed Ali. Avail Bal EGP 5,500.00"
        assertEquals("Expense", SmsParser.inferType(sms))
    }

    @Test
    fun `BM instapay outward inferCategory is Instapay outcome`() {
        val sms = "BM: IPN Outward transfer of EGP 500.00 sent to Mohamed Ali. Avail Bal EGP 5,500.00"
        assertEquals("Instapay outcome", SmsParser.inferCategory(sms))
    }

    @Test
    fun `BM salary credit inferType is Income`() {
        val sms = "BM: Salary of EGP 12,000.00 credited to account ending 5678. Avail Bal EGP 12,000.00"
        assertEquals("Income", SmsParser.inferType(sms))
    }

    @Test
    fun `BM salary credit inferCategory is Salary`() {
        val sms = "BM: Salary of EGP 12,000.00 credited to account ending 5678. Avail Bal EGP 12,000.00"
        assertEquals("Salary", SmsParser.inferCategory(sms))
    }

    @Test
    fun `BM salary credit extractAmount`() {
        val sms = "BM: Salary of EGP 12,000.00 credited to account ending 5678. Avail Bal EGP 12,000.00"
        assertEquals("12000.00", SmsParser.extractAmount(sms))
    }

    // ═══════════════════════════════════════════════════════
    // NBE — National Bank of Egypt
    // ═══════════════════════════════════════════════════════

    @Test
    fun `NBE card purchase isBankSms`() {
        val sms = "NBE: EGP 450.00 charged at KFC EGYPT using card ending 4321. Available Balance EGP 9,550.00"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `NBE card purchase inferType is Expense`() {
        val sms = "NBE: EGP 450.00 charged at KFC EGYPT using card ending 4321. Available Balance EGP 9,550.00"
        assertEquals("Expense", SmsParser.inferType(sms))
    }

    @Test
    fun `NBE card purchase inferCategory is Restaurants`() {
        val sms = "NBE: EGP 450.00 charged at KFC EGYPT using card ending 4321. Available Balance EGP 9,550.00"
        assertEquals("Restaurants", SmsParser.inferCategory(sms))
    }

    @Test
    fun `NBE card purchase extractAmount`() {
        val sms = "NBE: EGP 450.00 charged at KFC EGYPT using card ending 4321. Available Balance EGP 9,550.00"
        assertEquals("450.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `NBE card purchase extractLast4Digits`() {
        val sms = "NBE: EGP 450.00 charged at KFC EGYPT using card ending 4321. Available Balance EGP 9,550.00"
        assertEquals("4321", SmsParser.extractLast4Digits(sms))
    }

    @Test
    fun `NBE card purchase extractBalanceFromSms`() {
        val sms = "NBE: EGP 450.00 charged at KFC EGYPT using card ending 4321. Available Balance EGP 9,550.00"
        assertEquals(9550.0, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    // ═══════════════════════════════════════════════════════
    // Non-bank SMS — must all return false for isBankSms
    // ═══════════════════════════════════════════════════════

    @Test
    fun `OTP SMS isBankSms returns false`() {
        val sms = "Your OTP is 847291. Valid for 5 minutes. Do not share with anyone."
        assertFalse(SmsParser.isBankSms(sms))
    }

    @Test
    fun `Vodafone promotional isBankSms returns false`() {
        val sms = "Enjoy double data this weekend! Recharge now and get 2x your bundle. T&Cs apply."
        assertFalse(SmsParser.isBankSms(sms))
    }

    @Test
    fun `CIB promotional installment isBankSms returns false`() {
        val sms = "CIB: Convert your purchases to easy installments with 0% interest. T&Cs apply. Call us at 19666."
        assertFalse(SmsParser.isBankSms(sms))
    }

    @Test
    fun `Declined transaction isBankSms returns false`() {
        val sms = "Your transaction of EGP 500 has been declined. Reason: Insufficient funds."
        assertFalse(SmsParser.isBankSms(sms))
    }

    @Test
    fun `Delivery tracking SMS isBankSms returns false`() {
        val sms = "Your order #12345 is out for delivery. Expected arrival: today between 2-6 PM."
        assertFalse(SmsParser.isBankSms(sms))
    }

    // ═══════════════════════════════════════════════════════
    // Currency detection on real patterns
    // ═══════════════════════════════════════════════════════

    @Test
    fun `USD charge inferCurrency returns USD`() {
        val sms = "USD 15.99 charged to card **5678 at NETFLIX. Avail Bal USD 984.01"
        assertEquals("USD", SmsParser.inferCurrency(sms))
    }

    @Test
    fun `USD Netflix inferCategory is Subscriptions`() {
        val sms = "USD 15.99 charged to card **5678 at NETFLIX. Avail Bal USD 984.01"
        assertEquals("Subscriptions", SmsParser.inferCategory(sms))
    }

    @Test
    fun `USD Netflix extractAmount`() {
        val sms = "USD 15.99 charged to card **5678 at NETFLIX. Avail Bal USD 984.01"
        assertEquals("15.99", SmsParser.extractAmount(sms))
    }

    @Test
    fun `Uber charge inferCategory is Uber`() {
        val sms = "EGP 85.00 charged to card **1234 at UBER EGYPT. Avail Bal EGP 9,915.00"
        assertEquals("Uber", SmsParser.inferCategory(sms))
    }

    @Test
    fun `Starbucks charge inferCategory is Cafe`() {
        val sms = "EGP 120.00 charged to card **1234 at STARBUCKS CAIRO. Avail Bal EGP 9,880.00"
        assertEquals("Cafe", SmsParser.inferCategory(sms))
    }

    @Test
    fun `Vodafone bill inferCategory is Mobile`() {
        val sms = "EGP 299.00 charged to card **1234 at VODAFONE EGYPT. Avail Bal EGP 9,701.00"
        assertEquals("Mobile", SmsParser.inferCategory(sms))
    }

    @Test
    fun `Spotify charge inferCategory is Subscriptions`() {
        val sms = "USD 9.99 charged to card **5678 at SPOTIFY. Avail Bal USD 490.01"
        assertEquals("Subscriptions", SmsParser.inferCategory(sms))
    }
}
