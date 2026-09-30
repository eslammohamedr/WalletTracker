package com.example.wallettrackers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import com.example.wallettrackers.model.CreditStatement
import com.example.wallettrackers.util.ReminderManager
import com.example.wallettrackers.worker.ReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import androidx.core.app.NotificationManagerCompat

@RunWith(AndroidJUnit4::class)
class StatementReminderWorkIntegrationTest {
    @Test
    fun reminderWorkerPostsDueCardNotification() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManager = WorkManager.getInstance(context)
        val uniqueName = "reminder-delivery-${UUID.randomUUID()}"
        val title = "Wallet reminder ${UUID.randomUUID()}"
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
            .close()
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInputData(
                Data.Builder()
                    .putString("title", title)
                    .putString("message", "Payment for your card ending ****4455 is due.")
                    .putString("cardDigits", "4455")
                    .putDouble("amount", 123.45)
                    .build()
            )
            .build()

        try {
            workManager.enqueueUniqueWork(uniqueName, androidx.work.ExistingWorkPolicy.REPLACE, request)
            val info = awaitWorkInfo(workManager, uniqueName) { it.state == WorkInfo.State.SUCCEEDED }
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertTrue("Notifications are disabled for the app", NotificationManagerCompat.from(context).areNotificationsEnabled())
            val posted = (context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager)
                .activeNotifications
                .mapNotNull { it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() }
            assertTrue("Due-card notification title was not posted", posted.contains(title))
        } finally {
            workManager.cancelUniqueWork(uniqueName)
        }
    }

    @Test
    fun futureStatementEnqueuesThreeTaggedRemindersAndCancelsAll() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val workManager = WorkManager.getInstance(context)
        val smsId = "reminder-it-${UUID.randomUUID()}"
        val dueDate = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 8) }.time
        val statement = CreditStatement(
            cardLast4Digits = "4455",
            totalAmount = 123.45,
            dueDate = dueDate,
            userId = "instrumentation-user",
            smsId = smsId
        )
        val names = listOf(5, 1, 0).map { "reminder_${smsId}_$it" }

        try {
            ReminderManager.scheduleStatementReminders(context, statement)
            names.forEach { name ->
                val info = awaitWorkInfo(workManager, name) { it.state == WorkInfo.State.ENQUEUED }
                assertEquals(WorkInfo.State.ENQUEUED, info.state)
                assertTrue(info.tags.contains(name))
            }

            ReminderManager.cancelReminders(context, smsId)
            val cancelled = names.map { name -> awaitWorkInfo(workManager, name) { it.state == WorkInfo.State.CANCELLED } }
            assertTrue(cancelled.all { it.state == WorkInfo.State.CANCELLED })
        } finally {
            ReminderManager.cancelReminders(context, smsId)
        }
    }

    private fun awaitWorkInfo(workManager: WorkManager, name: String, predicate: (WorkInfo) -> Boolean): WorkInfo {
        val liveData = workManager.getWorkInfosForUniqueWorkLiveData(name)
        val latch = CountDownLatch(1)
        val matchingInfo = AtomicReference<WorkInfo?>()
        val observer = Observer<List<WorkInfo>> { infos ->
            infos.singleOrNull()?.takeIf(predicate)?.let { info ->
                matchingInfo.set(info)
                latch.countDown()
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { liveData.observeForever(observer) }
        try {
            assertTrue("Timed out waiting for WorkManager state for $name", latch.await(10, TimeUnit.SECONDS))
            return matchingInfo.get() ?: error("WorkManager returned no matching info for $name")
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { liveData.removeObserver(observer) }
        }
    }
}
