package com.example.wallettrackers.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.example.wallettrackers.MainActivity
import com.example.wallettrackers.R

object NotificationHelper {

    private const val CHANNEL_BUDGET = "budget_alerts"
    private const val CHANNEL_BILLS = "bill_reminders"

    fun createChannels(context: Context) {
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
    }

    fun sendBudgetAlert(context: Context, category: String, spent: Double, limit: Double, currency: String) {
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
    }

    fun sendBillReminder(context: Context, billName: String, amount: Double, currency: String, dayOfMonth: Int) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pi = PendingIntent.getActivity(context, billName.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, CHANNEL_BILLS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Bill Due: $billName")
            .setContentText("${"%.2f".format(amount)} $currency due on day $dayOfMonth")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(billName.hashCode(), notification)
    }
}
