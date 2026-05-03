package com.example.wallettrackers

import com.example.wallettrackers.util.SmsParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SmsParser — covers SMS-F-002 through SMS-F-008, SMS-F-013, SMS-F-014
 * Run with: ./gradlew test
 */
class SmsParserTest {

    // ─────────────────────────────────────────────
    // isBankSms  (SMS-F-002, SMS-F-003, SMS-F-004)
    // ─────────────────────────────────────────────

    @Test
    fun `isBankSms returns true for debit SMS with amount and account`() {
        val sms = "Your account ending 1234 has been debited EGP 500.00. Avail Bal EGP 4,500.00"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `isBankSms returns true for salary SMS without account digits`() {
        val sms = "Salary of EGP 15,000.00 has been credited to your account"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `isBankSms returns false for declined transaction`() {
        val sms = "Your transaction has been declined due to insufficient funds. Amount: EGP 200"
        assertFalse(SmsParser.isBankSms(sms))
    }

    @Test
    fun `isBankSms returns false for promotional SMS`() {
        val sms = "Special offer! Enjoy up to 30% cashback. Apply now. T&Cs apply."
        assertFalse(SmsParser.isBankSms(sms))
    }

    @Test
    fun `isBankSms returns false for random non-bank SMS`() {
        val sms = "Your OTP is 123456. Do not share with anyone."
        assertFalse(SmsParser.isBankSms(sms))
    }

    @Test
    fun `isBankSms returns true for instapay SMS without amount currency symbol`() {
        val sms = "IPN Inward transfer received from Ahmed Mohamed"
        assertTrue(SmsParser.isBankSms(sms))
    }

    // ─────────────────────────────────────────────
    // isDeclinedTransaction  (SMS-F-003)
    // ─────────────────────────────────────────────

    @Test
    fun `isDeclinedTransaction returns true for declined SMS`() {
        assertTrue(SmsParser.isDeclinedTransaction("Your transaction has been declined"))
        assertTrue(SmsParser.isDeclinedTransaction("Card declined. Amount EGP 300"))
        assertTrue(SmsParser.isDeclinedTransaction("Insufficient funds for this transaction"))
    }

    @Test
    fun `isDeclinedTransaction returns false for successful transaction`() {
        assertFalse(SmsParser.isDeclinedTransaction("EGP 500 debited from account 1234"))
    }

    // ─────────────────────────────────────────────
    // inferType  (SMS-F-008)
    // ─────────────────────────────────────────────

    @Test
    fun `inferType returns Expense for purchase SMS`() {
        val sms = "EGP 250.00 charged to your card ending 5678 at KFC. Avail Bal EGP 9,750"
        assertEquals("Expense", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns Income for salary SMS`() {
        val sms = "Your salary EGP 15,000.00 has been credited to account 1234"
        assertEquals("Income", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns Income for deposit SMS`() {
        val sms = "EGP 2,000.00 has been credited to your account via deposit"
        assertEquals("Income", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns AtmWithdrawal for ATM SMS`() {
        val sms = "EGP 3,000.00 cash withdrawal from ATM. Card **1234. Avail Bal EGP 7,000"
        assertEquals("AtmWithdrawal", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns Statement for credit card statement SMS`() {
        val sms = "Your credit card statement is ready. Total Amt Due EGP 5,200. Due Date 15/06/2026"
        assertEquals("Statement", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns CardPayment for credit card payment SMS`() {
        val sms = "EGP 5,000.00 debited from account 1234 for credit card payment"
        assertEquals("CardPayment", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns CreditCardReceived when credit card confirms payment received`() {
        val sms = "Payment of EGP 5,000 has been received for your credit card ending 9999"
        assertEquals("CreditCardReceived", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns Income for cashback SMS`() {
        val sms = "Cashback of EGP 50.00 has been credited to your account"
        assertEquals("Income", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns Income for instapay inward`() {
        val sms = "IPN Inward: EGP 1,000 received from Mohamed Ali"
        assertEquals("Income", SmsParser.inferType(sms))
    }

    // ─────────────────────────────────────────────
    // extractAmount  (SMS-F-006)
    // ─────────────────────────────────────────────

    @Test
    fun `extractAmount extracts EGP amount correctly`() {
        val sms = "EGP 1,250.00 debited from card ending 1234"
        assertEquals("1250.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `extractAmount extracts total amount due from statement`() {
        val sms = "Total Amt Due EGP 5,200.50. Min Amt Due EGP 500. Due Date 15-06-2026"
        assertEquals("5200.50", SmsParser.extractAmount(sms))
    }

    @Test
    fun `extractAmount returns null for SMS with no amount`() {
        val sms = "Your OTP is 123456"
        assertNull(SmsParser.extractAmount(sms))
    }

    @Test
    fun `extractAmount handles comma-separated thousands`() {
        val sms = "EGP 10,000 credited to your account"
        assertEquals("10000", SmsParser.extractAmount(sms))
    }

    @Test
    fun `extractAmount extracts USD amount`() {
        val sms = "USD 50.00 charged to card ending 4321"
        assertEquals("50.00", SmsParser.extractAmount(sms))
    }

    // ─────────────────────────────────────────────
    // extractLast4Digits  (SMS-F-007)
    // ─────────────────────────────────────────────

    @Test
    fun `extractLast4Digits extracts digits from starred card number`() {
        val sms = "EGP 300 debited from card **5678. Avail Bal EGP 2,700"
        assertEquals("5678", SmsParser.extractLast4Digits(sms))
    }

    @Test
    fun `extractLast4Digits extracts digits from credit card ending pattern`() {
        val sms = "Payment received for Credit Card ending with 9999"
        assertEquals("9999", SmsParser.extractLast4Digits(sms))
    }

    @Test
    fun `extractLast4Digits extracts from A-slash-c pattern`() {
        val sms = "Your A/c 1234 has been debited EGP 500"
        assertEquals("1234", SmsParser.extractLast4Digits(sms))
    }

    // ─────────────────────────────────────────────
    // inferCategory  (AI-F-003 fallback)
    // ─────────────────────────────────────────────

    @Test
    fun `inferCategory returns Groceries for Carrefour`() {
        val sms = "EGP 450 charged at Carrefour. Card **1234"
        assertEquals("Groceries", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Uber for Uber purchase`() {
        val sms = "EGP 85.00 charged to card ending 1234 at UBER"
        assertEquals("Uber", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Subscriptions for Netflix`() {
        val sms = "USD 15.99 charged at NETFLIX. Card **5678"
        assertEquals("Subscriptions", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Restaurants for KFC`() {
        val sms = "EGP 180 charged at KFC Egypt. Card **1234"
        assertEquals("Restaurants", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Salary for salary SMS`() {
        val sms = "Salary EGP 12,000 credited to your account"
        assertEquals("Salary", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Others for unknown merchant`() {
        val sms = "EGP 200 charged at XYZSTORE. Card **1234"
        assertEquals("Others", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Instapay outcome for outward instapay`() {
        val sms = "IPN Outward: EGP 500 sent to Ahmed"
        assertEquals("Instapay outcome", SmsParser.inferCategory(sms))
    }

    // ─────────────────────────────────────────────
    // extractBalanceFromSms  (SMS-F-014)
    // ─────────────────────────────────────────────

    @Test
    fun `extractBalanceFromSms reads Avail Bal format`() {
        val sms = "EGP 500 debited. Avail Bal EGP 4,500.00"
        assertEquals(4500.0, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    @Test
    fun `extractBalanceFromSms reads Available Balance format`() {
        val sms = "EGP 200 charged. Available Balance: 8,300.50"
        assertEquals(8300.50, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    @Test
    fun `extractBalanceFromSms returns null when no balance in SMS`() {
        val sms = "EGP 300 charged to card ending 1234 at KFC"
        assertNull(SmsParser.extractBalanceFromSms(sms))
    }

    // ─────────────────────────────────────────────
    // inferCurrency  (SMS-F-006 currency detection)
    // ─────────────────────────────────────────────

    @Test
    fun `inferCurrency returns EGP by default`() {
        assertEquals("EGP", SmsParser.inferCurrency("EGP 500 debited from account"))
    }

    @Test
    fun `inferCurrency returns USD for dollar sign`() {
        assertEquals("USD", SmsParser.inferCurrency("USD 50.00 charged to your card"))
    }

    @Test
    fun `inferCurrency returns EUR for euro symbol`() {
        assertEquals("EUR", SmsParser.inferCurrency("€ 30.00 charged at merchant"))
    }

    // ─────────────────────────────────────────────
    // inferComment  (SMS-F-005)
    // ─────────────────────────────────────────────

    @Test
    fun `inferComment returns Cashback for cashback SMS`() {
        val sms = "Cashback of EGP 50.00 credited to your account"
        assertEquals("Cashback", SmsParser.inferComment(sms))
    }

    @Test
    fun `inferComment extracts recipient name from to-with-reference pattern`() {
        val sms = "EGP 500 transferred to Ahmed Mohamed with reference 123456"
        assertEquals("Ahmed Mohamed", SmsParser.inferComment(sms))
    }

    @Test
    fun `inferComment extracts sender name from from-with-reference pattern`() {
        val sms = "EGP 1,000 received from Mohamed Ali with reference 789012"
        assertEquals("Mohamed Ali", SmsParser.inferComment(sms))
    }

    @Test
    fun `inferComment extracts merchant name from at pattern`() {
        val sms = "EGP 250 charged to card **1234 at CARREFOUR EGYPT on 01-05-2026"
        assertEquals("CARREFOUR EGYPT", SmsParser.inferComment(sms))
    }

    @Test
    fun `inferComment extracts merchant name stopping at period`() {
        val sms = "EGP 85 charged to card **5678 at UBER EGYPT. Avail Bal EGP 9,915"
        assertEquals("UBER EGYPT", SmsParser.inferComment(sms))
    }

    @Test
    fun `inferComment returns null when no pattern matches`() {
        val sms = "Your account balance is EGP 5,000"
        assertNull(SmsParser.inferComment(sms))
    }

    // ── extractDueDate ────────────────────────────────────────────────────

    @Test
    fun `extractDueDate handles due before with hyphen date`() {
        val sms = "minimum due is 401.99 EGP, due before 26-05-2026 For more info"
        assertEquals("26-05-2026", SmsParser.extractDueDate(sms))
    }

    @Test
    fun `extractDueDate handles Due Date keyword`() {
        val sms = "Total Amt Due EGP 3,500. Due Date 10-06-2026. Min Amt Due EGP 350."
        assertEquals("10-06-2026", SmsParser.extractDueDate(sms))
    }

    @Test
    fun `extractDueDate handles slash separator`() {
        val sms = "Your statement is ready. Due Date: 15/06/2026"
        assertEquals("15/06/2026", SmsParser.extractDueDate(sms))
    }

    @Test
    fun `extractDueDate handles payment due keyword`() {
        val sms = "Payment Due 20-07-2026. Total EGP 1200."
        assertEquals("20-07-2026", SmsParser.extractDueDate(sms))
    }

    @Test
    fun `extractDueDate returns null when no due date present`() {
        val sms = "EGP 200 debited from your account. Avail Bal EGP 9,800."
        assertNull(SmsParser.extractDueDate(sms))
    }
}
