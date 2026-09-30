package com.example.wallettrackers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.util.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsParserFinancialLogicComponentTest {
    @Test
    fun realBanqueMisrArabicCreditPaymentExtractsFinancialFields() {
        val body = "شكرا لاستخدامكم بطاقة بنك مصر الائتمانية ****7000   تم ايداع 6630 EGP   " +
            "فى BM-Online يوم  25/02/2025 متاح الان EGP  46568.69   " +
            "للمزيد من المعلومات join https://bnkmsr.com/online"

        assertTrue(SmsParser.isBankSms(body, "Banque Misr"))
        assertFalse(SmsParser.isPromotionalSms(body))
        assertEquals("CreditCardReceived", SmsParser.inferType(body))
        assertEquals("6630", SmsParser.extractAmount(body))
        assertEquals("7000", SmsParser.extractLast4Digits(body))
        assertEquals("EGP", SmsParser.inferCurrency(body))
        assertEquals(46568.69, SmsParser.extractBalanceFromSms(body)!!, 0.001)
    }
}
