package com.example.wallettrackers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.wallettrackers.util.PendingCreditPaymentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PendingCreditPaymentStoreTest {
    @Test
    fun peekPreservesPendingEntryUntilExplicitlyConsumed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smsId = "pending-store-${UUID.randomUUID()}"
        val key = "cc_pending_$smsId"
        val preferences = context.getSharedPreferences("pending_cc", Context.MODE_PRIVATE)
        val store = PendingCreditPaymentStore(context)

        try {
            store.store("47.29", "3333", smsId)

            assertEquals(smsId, store.peek("47.29", "3333")?.smsId)
            assertEquals(smsId, store.peek("47.29", "3333")?.smsId)
            assertTrue(preferences.contains(key))

            assertNotNull(store.consume("47.29", "3333"))
            assertFalse(preferences.contains(key))
        } finally {
            preferences.edit().remove(key).apply()
        }
    }

    @Test
    fun ambiguousSameAmountCardsRemainPendingUntilDestinationIsKnown() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferences = context.getSharedPreferences("pending_cc", Context.MODE_PRIVATE)
        val firstSmsId = "pending-first-${UUID.randomUUID()}"
        val secondSmsId = "pending-second-${UUID.randomUUID()}"
        val firstKey = "cc_pending_$firstSmsId"
        val secondKey = "cc_pending_$secondSmsId"
        val store = PendingCreditPaymentStore(context)

        try {
            store.store("47.29", "3333", firstSmsId)
            store.store("47.29", "4444", secondSmsId)

            assertEquals(null, store.peek("47.29", ""))
            assertTrue(preferences.contains(firstKey))
            assertTrue(preferences.contains(secondKey))
            assertEquals(firstSmsId, store.peek("47.29", "3333")?.smsId)
        } finally {
            preferences.edit().remove(firstKey).remove(secondKey).apply()
        }
    }

    @Test
    fun expiredPendingEntryIsDiscardedInsteadOfMatched() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val smsId = "pending-expired-${UUID.randomUUID()}"
        val key = "cc_pending_$smsId"
        val preferences = context.getSharedPreferences("pending_cc", Context.MODE_PRIVATE)
        val startMillis = 1_000L
        val initialStore = PendingCreditPaymentStore(context) { startMillis }
        val expiredStore = PendingCreditPaymentStore(context) {
            startMillis + TimeUnit.HOURS.toMillis(48) + 1
        }

        try {
            initialStore.store("47.29", "3333", smsId)

            assertEquals(null, expiredStore.peek("47.29", "3333"))
            assertFalse(preferences.contains(key))
        } finally {
            preferences.edit().remove(key).apply()
        }
    }

    @Test
    fun legacyAmountKeyRemainsReadableAndConsumable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val key = "cc_pending_47.29"
        val preferences = context.getSharedPreferences("pending_cc", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val store = PendingCreditPaymentStore(context) { now }

        try {
            preferences.edit().putString(key, "legacy-sms-id|3333|$now").apply()

            assertEquals("legacy-sms-id", store.peek("47.29", "3333")?.smsId)
            assertEquals("legacy-sms-id", store.consume("47.29", "3333")?.smsId)
            assertFalse(preferences.contains(key))
        } finally {
            preferences.edit().remove(key).apply()
        }
    }
}
