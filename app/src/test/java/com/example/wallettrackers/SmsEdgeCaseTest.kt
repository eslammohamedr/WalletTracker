package com.example.wallettrackers

import com.example.wallettrackers.util.SmsParser
import org.junit.Assert.*
import org.junit.Test

/**
 * Edge-case and boundary tests for SmsParser.
 * Covers unusual formats, mixed-language SMS, and boundary values.
 */
class SmsEdgeCaseTest {

    // ── Amount formats ────────────────────────────────���───────────────────

    @Test
    fun `extractAmount handles amount with no decimal places`() {
        assertEquals("500", SmsParser.extractAmount("EGP 500 debited from account"))
    }

    @Test
    fun `extractAmount handles large amount with multiple comma groups`() {
        assertEquals("1000000.00", SmsParser.extractAmount("EGP 1,000,000.00 credited to account"))
    }

    @Test
    fun `extractAmount handles SMS where transaction amount comes before balance`() {
        // Parser should return the transaction amount, not the balance
        val sms = "EGP 300.00 debited from card **1234. Avail Bal EGP 9,700.00"
        assertEquals("300.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `extractAmount returns first significant amount not balance`() {
        // Total Amt Due takes priority over inline amounts
        val sms = "Total Amt Due EGP 3,500.00. Min Amt Due EGP 350.00. Due Date 10-06-2026"
        assertEquals("3500.00", SmsParser.extractAmount(sms))
    }

    @Test
    fun `extractAmount handles USD amount with dollar sign`() {
        assertEquals("9.99", SmsParser.extractAmount("$ 9.99 charged to card **1234"))
    }

    // ── Balance extraction edge cases ─────────────────────────────────────

    @Test
    fun `extractBalanceFromSms handles balance with no decimal`() {
        val sms = "EGP 200 debited. Avail Bal EGP 3000"
        assertEquals(3000.0, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    @Test
    fun `extractBalanceFromSms handles New Bal format`() {
        val sms = "EGP 150 charged. New Bal EGP 8,850.00"
        assertEquals(8850.0, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    @Test
    fun `extractBalanceFromSms handles balance after colon`() {
        val sms = "EGP 400 debited. Balance after: EGP 9,600.00"
        assertEquals(9600.0, SmsParser.extractBalanceFromSms(sms)!!, 0.01)
    }

    @Test
    fun `extractBalanceFromSms returns null for statement SMS with no balance`() {
        val sms = "Your statement is ready. Total Amt Due EGP 5,200. Due Date 15-06-2026"
        assertNull(SmsParser.extractBalanceFromSms(sms))
    }

    // ── isBankSms edge cases ────────────────────────────────���─────────────

    @Test
    fun `isBankSms returns true for SMS with only balance info`() {
        val sms = "Your current balance is EGP 5,000.00"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `isBankSms returns false for empty string`() {
        assertFalse(SmsParser.isBankSms(""))
    }

    @Test
    fun `isBankSms returns false for whitespace only`() {
        assertFalse(SmsParser.isBankSms("   "))
    }

    @Test
    fun `isBankSms returns true for SMS with ref no`() {
        val sms = "EGP 750 transferred. Ref No 987654321. Avail Bal EGP 4,250"
        assertTrue(SmsParser.isBankSms(sms))
    }

    @Test
    fun `isBankSms returns false for SMS that has promo signals but also transaction signals`() {
        // A real transaction SMS that coincidentally mentions "special offer"
        // transaction signals should win — isPromotionalSms returns false
        val sms = "Special offer cashback EGP 50 credited to your account ending 1234. Avail Bal EGP 5,050"
        assertTrue(SmsParser.isBankSms(sms))
    }

    // ── inferType edge cases ──────────────────���──────────────────────────���─

    @Test
    fun `inferType defaults to Expense for ambiguous debit SMS`() {
        val sms = "EGP 200 spent on purchase. Card **1234"
        assertEquals("Expense", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns Income for returned amount`() {
        val sms = "EGP 150 returned to your account ending 1234"
        assertEquals("Income", SmsParser.inferType(sms))
    }

    @Test
    fun `inferType returns AtmWithdrawal when withdrawal keyword present`() {
        val sms = "EGP 1,000 withdrawal processed. Card **5678. Avail Bal EGP 4,000"
        assertEquals("AtmWithdrawal", SmsParser.inferType(sms))
    }

    // ── extractLast4Digits edge cases ─────────────────────────────────────

    @Test
    fun `extractLast4Digits handles account pattern with spaces`() {
        val sms = "Your account ending 5432 has been debited EGP 300"
        assertEquals("5432", SmsParser.extractLast4Digits(sms))
    }

    @Test
    fun `extractLast4Digits prefers starred card over plain account number`() {
        val sms = "EGP 200 debited from A/c 1234 using card **5678"
        assertEquals("5678", SmsParser.extractLast4Digits(sms))
    }

    @Test
    fun `extractLast4Digits returns null for SMS with no card or account digits`() {
        val sms = "Your salary has been credited to your account"
        // No 4-digit sequence that's not a year
        assertNull(SmsParser.extractLast4Digits(sms))
    }

    // ── inferCategory edge cases ──────────────────────────────────────────

    @Test
    fun `inferCategory returns Salary for TT payment`() {
        val sms = "EGP 10,000 TT Payment credited to your account"
        assertEquals("Salary", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Mobile for Fawry payment`() {
        val sms = "EGP 120 charged at FAWRY. Card **1234"
        assertEquals("Mobile", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Health and beauty for pharmacy`() {
        val sms = "EGP 85 charged at EL EZABY PHARMACY. Card **1234"
        assertEquals("Health and beauty", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Fuel for petrol station`() {
        val sms = "EGP 500 charged at TOTAL PETROL. Card **1234"
        assertEquals("Fuel", SmsParser.inferCategory(sms))
    }

    @Test
    fun `inferCategory returns Instapay income for IPN inward`() {
        val sms = "IPN Inward transfer of EGP 2,000 received. Avail Bal EGP 7,000"
        assertEquals("Instapay income", SmsParser.inferCategory(sms))
    }

    // ── inferCurrency edge cases ──────────────────────────────────────────

    @Test
    fun `inferCurrency returns SAR for Saudi riyal symbol`() {
        assertEquals("SAR", SmsParser.inferCurrency("SAR 500 transferred"))
    }

    @Test
    fun `inferCurrency returns GBP for pound sign`() {
        assertEquals("GBP", SmsParser.inferCurrency("£ 100 charged to card ending 1234"))
    }

    @Test
    fun `inferCurrency returns EGP when no currency marker present`() {
        assertEquals("EGP", SmsParser.inferCurrency("500 debited from account"))
    }
}
