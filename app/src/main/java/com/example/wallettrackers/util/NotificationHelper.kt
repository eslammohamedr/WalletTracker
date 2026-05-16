package com.example.wallettrackers.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.wallettrackers.MainActivity
import com.example.wallettrackers.R

object NotificationHelper {

    private const val TAG = "NotifHelper"
    private const val CHANNEL_BUDGET = "budget_alerts"
    private const val CHANNEL_BILLS = "bill_reminders"

    fun createChannels(context: Context) {
        Log.d(TAG, "createChannels START: creating budget and bill notification channels")
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BUDGET, "Budget Alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when you approach or exceed a budget limit"
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BILLS, "Bill Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Reminders for recurring monthly bills"
            }
        )
        Log.d(TAG, "createChannels END: channels created")
    }

    fun sendBudgetAlert(context: Context, category: String, spent: Double, limit: Double, currency: String) {
        Log.d(TAG, "sendBudgetAlert START: category='$category' spent=$spent limit=$limit currency=$currency")
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra("navigate_to", "all_records")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val isOver = spent > limit
        val title = if (isOver) "Budget Exceeded: $category" else "Budget Warning: $category"
        val pct = (spent / limit * 100).toInt()
        val msg = if (isOver) "Spent ${"%.2f".format(spent)} $currency — ${pct - 100}% over limit"
                  else "Spent ${"%.2f".format(spent)} / ${"%.2f".format(limit)} $currency ($pct%)"
        Log.d(TAG, "sendBudgetAlert: isOver=$isOver pct=$pct% title='$title'")

        val notification = NotificationCompat.Builder(context, CHANNEL_BUDGET)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(category.hashCode(), notification)
        Log.d(TAG, "sendBudgetAlert END: notification posted with id=${category.hashCode()}")
    }

    fun sendBillReminder(context: Context, billName: String, amount: Double, currency: String, dayOfMonth: Int, isDayBefore: Boolean = false) {
        Log.d(TAG, "sendBillReminder START: bill='$billName' amount=$amount currency=$currency day=$dayOfMonth isDayBefore=$isDayBefore")
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notifId = billName.hashCode() + if (isDayBefore) 1 else 0
        val pi = PendingIntent.getActivity(context, notifId, intent, PendingIntent.FLAG_IMMUTABLE)
        val title = if (isDayBefore) "Bill Due Tomorrow: $billName" else "Bill Due Today: $billName"
        val text = if (isDayBefore)
            "${"%.2f".format(amount)} $currency is due tomorrow — don't forget to pay!"
        else
            "${"%.2f".format(amount)} $currency is due today"

        val notification = NotificationCompat.Builder(context, CHANNEL_BILLS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notifId, notification)
        Log.d(TAG, "sendBillReminder END: notification posted with id=$notifId")
    }
}
