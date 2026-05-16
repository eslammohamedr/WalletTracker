package com.example.wallettrackers.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.wallettrackers.MainActivity
import com.example.wallettrackers.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BalanceWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { update(context, manager, it) }
    }

    companion object {
        fun update(context: Context, manager: AppWidgetManager, id: Int) {
            val prefs = context.getSharedPreferences("wallet_widget", Context.MODE_PRIVATE)
            val balance = prefs.getString("total_balance", "--") ?: "--"
            val spentToday = prefs.getString("spent_today", "--") ?: "--"
            val budgetLeft = prefs.getString("budget_left", "--") ?: "--"
            val ts = prefs.getLong("last_updated", 0L)
            val updatedStr = if (ts > 0)
                "Updated ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))}"
            else ""

            val views = RemoteViews(context.packageName, R.layout.widget_balance)
            views.setTextViewText(R.id.widget_balance, balance)
            views.setTextViewText(R.id.widget_spent_today, spentToday)
            views.setTextViewText(R.id.widget_budget_left, budgetLeft)
            views.setTextViewText(R.id.widget_updated, updatedStr)

            val intent = Intent(context, MainActivity::class.java)
            val pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, pi)

            manager.updateAppWidget(id, views)
        }
    }
}
