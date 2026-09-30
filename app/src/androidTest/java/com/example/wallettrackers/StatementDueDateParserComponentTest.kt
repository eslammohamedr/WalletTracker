package com.example.wallettrackers

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.util.StatementDueDateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.text.SimpleDateFormat
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class StatementDueDateParserComponentTest {
    @Test
    fun validBankDateFormatsPreserveTheExactCalendarDate() {
        val formatter = SimpleDateFormat("dd/MM/yyyy", Locale.ROOT)
        assertEquals("26/04/2026", formatter.format(StatementDueDateParser.parse("26/04/2026")!!))
        assertEquals("31/12/2026", formatter.format(StatementDueDateParser.parse("31-12-2026")!!))
    }

    @Test
    fun leapDayIsValidatedStrictly() {
        assertNotNull(StatementDueDateParser.parse("29/02/2024"))
        assertNull(StatementDueDateParser.parse("29/02/2025"))
    }

    @Test
    fun missingMalformedAndTrailingTextDoNotProduceDueDates() {
        assertNull(StatementDueDateParser.parse(null))
        assertNull(StatementDueDateParser.parse("  "))
        assertNull(StatementDueDateParser.parse("31/02/2026"))
        assertNull(StatementDueDateParser.parse("26/04/2026 due soon"))
    }
}
