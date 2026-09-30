package com.example.wallettrackers

import com.example.wallettrackers.util.SmsBroadcastId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SmsBroadcastIdTest {
    @Test
    fun duplicateDeliveryProducesSameIdButDistinctMessagesWithSameTimestampDoNotCollide() {
        val timestamp = 1_790_318_593_000L
        val first = SmsBroadcastId.create(timestamp, "MainBank", "Carrefour ****1111 EGP 0.03")
        val duplicate = SmsBroadcastId.create(timestamp, "MainBank", "Carrefour ****1111 EGP 0.03")
        val second = SmsBroadcastId.create(timestamp, "SecondBank", "KFC ****2222 EGP 0.04")

        assertEquals(first, duplicate)
        assertNotEquals(first, second)
    }

    @Test
    fun retainsTimestampForExistingSmsCenterMatching() {
        val timestamp = 1_790_318_593_000L
        val smsId = SmsBroadcastId.create(timestamp, "MainBank", "transaction")

        assertEquals(timestamp, SmsBroadcastId.timestampMillis(smsId))
        assertEquals(timestamp, SmsBroadcastId.timestampMillis(timestamp.toString()))
        assertEquals(null, SmsBroadcastId.timestampMillis("inbox:50"))
    }
}
