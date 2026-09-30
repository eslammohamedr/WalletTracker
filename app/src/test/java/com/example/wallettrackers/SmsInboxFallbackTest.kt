package com.example.wallettrackers

import com.example.wallettrackers.util.InboxSmsCandidate
import com.example.wallettrackers.util.SmsInboxFallback
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsInboxFallbackTest {
    @Test
    fun retriesAnInitiallyEmptyInboxUntilTheNewRowIsVisible() = runTest {
        val newSms = InboxSmsCandidate("54", "1861", "statement alert", 1_800_000_000_000L)
        var reads = 0

        val result = SmsInboxFallback.readRecentCandidatesWithRetry(
            readCandidates = {
                reads++
                if (reads < 3) emptyList() else listOf(newSms)
            },
            maxAttempts = 6,
            retryDelayMillis = 1
        )

        assertEquals(listOf(newSms), result)
        assertEquals(3, reads)
    }

    @Test
    fun fallbackIsUsedOnlyWhenBroadcastHasNoPdus() {
        assertTrue(SmsInboxFallback.shouldReadInboxFallback(0))
        assertFalse(SmsInboxFallback.shouldReadInboxFallback(1))
        assertFalse(SmsInboxFallback.shouldReadInboxFallback(2))
    }

    @Test
    fun selectsNewestRecentInboxRowAndRejectsHistoricalOrFutureRows() {
        val now = 1_800_000_000_000L
        val stale = InboxSmsCandidate("10", "Bank", "old transaction", now - 30_001)
        val recent = InboxSmsCandidate("11", "Bank", "new transaction", now - 1_000)
        val future = InboxSmsCandidate("12", "Bank", "future transaction", now + 5_001)

        assertEquals(recent, SmsInboxFallback.selectNewestRecent(listOf(stale, recent, future), now))
        assertNull(SmsInboxFallback.selectNewestRecent(listOf(stale, future), now))
    }

    @Test
    fun rejectsIncompleteCandidatesAndAlreadyProcessedRowIds() {
        val now = 1_800_000_000_000L
        val incomplete = InboxSmsCandidate("13", "Bank", "", now)

        assertNull(SmsInboxFallback.selectNewestRecent(listOf(incomplete), now))
        assertTrue(SmsInboxFallback.isUnseenInboxRow("14", 13))
        assertFalse(SmsInboxFallback.isUnseenInboxRow("14", 14))
        assertFalse(SmsInboxFallback.isUnseenInboxRow("not-an-id", 0))
    }

    @Test
    fun selectsEveryRecentUnseenSmsInArrivalOrder() {
        val now = 1_800_000_000_000L
        val alreadyProcessed = InboxSmsCandidate("20", "Bank", "old transaction", now)
        val second = InboxSmsCandidate("22", "Bank", "second transaction", now - 500)
        val first = InboxSmsCandidate("21", "Bank", "first transaction", now - 1_000)
        val historical = InboxSmsCandidate("23", "Bank", "historical transaction", now - 30_001)

        assertEquals(
            listOf(first, second),
            SmsInboxFallback.selectRecentUnseen(
                listOf(second, alreadyProcessed, historical, first),
                now,
                lastProcessedId = 20
            )
        )
    }
}
