package com.example.wallettrackers.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Locale

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.d("ReminderWorker", "doWork START: processing payment reminder")
        val title = inputData.getString("title") ?: "Payment Reminder"
        val message = inputData.getString("message") ?: "You have a payment due soon."
        val cardDigits = inputData.getString("cardDigits") ?: ""
        val amount = inputData.getDouble("amount", 0.0)
        Log.d("ReminderWorker", "doWork: title='$title' card=****$cardDigits amount=$amount")

        sendNotification(title, "$message (Card ****$cardDigits: $amount EGP)")
        Log.d("ReminderWorker", "doWork END: notification sent")

        return Result.success()
    }

    private fun sendNotification(title: String, text: String) {
        Log.d("ReminderWorker", "sendNotification: title='$title' text='${text.take(60)}'")
        val context = applicationContext
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "payment_reminders"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Payment Reminders", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

        val notifId = System.currentTimeMillis().toInt()
        notificationManager.notify(notifId, builder.build())
        Log.d("ReminderWorker", "sendNotification: posted with id=$notifId")
    }
}
