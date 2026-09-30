package com.example.wallettrackers

import androidx.test.core.app.ApplicationProvider
import com.example.wallettrackers.receiver.SmsReceiver
import org.junit.Assert.assertEquals
import org.junit.Test

class SmsReceiverNotificationTest {
    @Test
    fun transactionNotificationUsesAppIconAndVisibleTransactionDetails() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val notification = SmsReceiver().buildNotification(
            context,
            "Transaction Added Automatically",
            "Groceries: -0.11 EGP",
            true
        )

        assertEquals(R.drawable.ic_stat_wallet, notification.smallIcon?.resId)
        assertEquals("Transaction Added Automatically", notification.extras.getCharSequence("android.title"))
        assertEquals("Groceries: -0.11 EGP", notification.extras.getCharSequence("android.text"))
    }
}
