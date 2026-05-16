package com.example.wallettrackers.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.wallettrackers.model.Budget
import com.example.wallettrackers.model.Record
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object PdfReportGenerator {

    fun generateMonthlyReport(
        context: Context,
        records: List<Record>,
        budgets: List<Budget>,
        month: Int,
        year: Int
    ): Boolean {
        val cal = Calendar.getInstance().apply { set(Calendar.MONTH, month); set(Calendar.YEAR, year) }
        val monthName = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)

        val monthRecords = records.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == month && rc.get(Calendar.YEAR) == year
        }
        val expenses = monthRecords.filter { it.type == "Expense" }
        val incomes = monthRecords.filter { it.type == "Income" }
        val totalExpense = expenses.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        val totalIncome = incomes.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        val net = totalIncome - totalExpense

        val categoryBreakdown = expenses
            .groupBy { it.category }
            .mapValues { (_, rs) -> rs.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } }
            .toList()
            .sortedByDescending { it.second }

        val doc = PdfDocument()
        val pageW = 595
        val pageH = 842
        val margin = 40f

        val titlePaint = Paint().apply { isAntiAlias = true; textSize = 22f; isFakeBoldText = true; color = 0xFF1E293B.toInt() }
        val headerPaint = Paint().apply { isAntiAlias = true; textSize = 14f; isFakeBoldText = true; color = 0xFF334155.toInt() }
        val bodyPaint = Paint().apply { isAntiAlias = true; textSize = 11f; color = 0xFF475569.toInt() }
        val numPaint = Paint().apply { isAntiAlias = true; textSize = 11f; color = 0xFF1E293B.toInt(); textAlign = Paint.Align.RIGHT }
        val greenPaint = Paint().apply { isAntiAlias = true; textSize = 14f; isFakeBoldText = true; color = 0xFF16A34A.toInt() }
        val redPaint = Paint().apply { isAntiAlias = true; textSize = 14f; isFakeBoldText = true; color = 0xFFDC2626.toInt() }
        val chartPaint = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        val chartColors = intArrayOf(
            0xFF6366F1.toInt(), 0xFF8B5CF6.toInt(), 0xFFF59E0B.toInt(),
            0xFF10B981.toInt(), 0xFFEF4444.toInt(), 0xFF3B82F6.toInt(),
            0xFFEC4899.toInt(), 0xFF14B8A6.toInt()
        )

        // Page 1 — Summary
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 1).create())
        var canvas = page.canvas
        var y = margin + 30f

        canvas.drawText("Monthly Financial Report", margin, y, titlePaint)
        y += 30f
        canvas.drawText(monthName, margin, y, headerPaint)
        y += 40f

        // Summary box
        canvas.drawText("Total Income:", margin, y, bodyPaint)
        canvas.drawText(String.format("%,.2f", totalIncome), pageW - margin, y, numPaint.apply { color = 0xFF16A34A.toInt() })
        y += 22f
        canvas.drawText("Total Expenses:", margin, y, bodyPaint)
        canvas.drawText(String.format("%,.2f", totalExpense), pageW - margin, y, numPaint.apply { color = 0xFFDC2626.toInt() })
        y += 22f
        val linePaint = Paint().apply { color = 0xFFE2E8F0.toInt(); strokeWidth = 1f }
        canvas.drawLine(margin, y, pageW - margin, y, linePaint)
        y += 20f
        canvas.drawText("Net:", margin, y, headerPaint)
        val netText = String.format("%,.2f", net)
        canvas.drawText(netText, pageW - margin, y, if (net >= 0) greenPaint.apply { textAlign = Paint.Align.RIGHT } else redPaint.apply { textAlign = Paint.Align.RIGHT })
        y += 40f

        // Category breakdown
        canvas.drawText("Spending by Category", margin, y, headerPaint)
        y += 25f

        // Donut chart
        if (categoryBreakdown.isNotEmpty()) {
            val chartSize = 140f
            val chartLeft = pageW / 2f - chartSize / 2f
            val rect = RectF(chartLeft, y, chartLeft + chartSize, y + chartSize)
            var startAngle = -90f
            categoryBreakdown.forEachIndexed { index, (_, amount) ->
                val sweep = (amount / totalExpense * 360f).toFloat()
                chartPaint.color = chartColors[index % chartColors.size]
                canvas.drawArc(rect, startAngle, sweep, true, chartPaint)
                startAngle += sweep
            }
            // White center hole
            chartPaint.color = 0xFFFFFFFF.toInt()
            val holeRect = RectF(chartLeft + 35f, y + 35f, chartLeft + chartSize - 35f, y + chartSize - 35f)
            canvas.drawOval(holeRect, chartPaint)
            y += chartSize + 20f
        }

        // Legend + amounts table
        categoryBreakdown.take(10).forEachIndexed { index, (category, amount) ->
            val pct = if (totalExpense > 0) (amount / totalExpense * 100) else 0.0
            chartPaint.color = chartColors[index % chartColors.size]
            canvas.drawCircle(margin + 6f, y - 4f, 5f, chartPaint)
            canvas.drawText("$category (${String.format("%.1f", pct)}%)", margin + 20f, y, bodyPaint)
            canvas.drawText(String.format("%,.0f", amount), pageW - margin, y, numPaint.apply { color = 0xFF1E293B.toInt() })
            y += 20f
        }
        y += 20f

        // Budget performance
        if (budgets.isNotEmpty()) {
            canvas.drawText("Budget Performance", margin, y, headerPaint)
            y += 25f
            budgets.forEach { budget ->
                val spent = categoryBreakdown.find { it.first == budget.category }?.second ?: 0.0
                val pct = if (budget.monthlyLimit > 0) (spent / budget.monthlyLimit * 100) else 0.0
                val status = if (pct > 100) "OVER" else if (pct > 75) "WARNING" else "OK"
                canvas.drawText("${budget.category}: ${String.format("%.0f", spent)} / ${String.format("%.0f", budget.monthlyLimit)} (${String.format("%.0f", pct)}%) - $status", margin, y, bodyPaint)
                y += 18f
                if (y > pageH - 60) { doc.finishPage(page); page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 2).create()); canvas = page.canvas; y = margin }
            }
        }

        // Daily spending bar chart
        y += 30f
        if (y > pageH - 200) {
            doc.finishPage(page)
            page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, 2).create())
            canvas = page.canvas
            y = margin
        }
        canvas.drawText("Daily Spending", margin, y, headerPaint)
        y += 20f

        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        val dailySpending = (1..daysInMonth).map { day ->
            expenses.filter { r ->
                val rc = Calendar.getInstance().apply { time = r.timestamp }
                rc.get(Calendar.DAY_OF_MONTH) == day
            }.sumOf { it.amount.toDoubleOrNull() ?: 0.0 }
        }
        val maxDaily = dailySpending.max().coerceAtLeast(1.0)
        val chartWidth = pageW - margin * 2
        val chartHeight = 100f
        val barWidth = (chartWidth / daysInMonth) * 0.7f
        val barGap = chartWidth / daysInMonth

        // Grid line
        val gridPaint = Paint().apply { color = 0xFFE2E8F0.toInt(); strokeWidth = 0.5f }
        canvas.drawLine(margin, y + chartHeight, margin + chartWidth, y + chartHeight, gridPaint)

        dailySpending.forEachIndexed { index, amount ->
            val barH = (amount / maxDaily * chartHeight).toFloat()
            val left = margin + index * barGap
            val top = y + chartHeight - barH
            chartPaint.color = if (barH > chartHeight * 0.75f) 0xFFEF4444.toInt() else 0xFF6366F1.toInt()
            canvas.drawRect(left, top, left + barWidth, y + chartHeight, chartPaint)
        }

        // X axis labels
        val smallPaint = Paint().apply { isAntiAlias = true; textSize = 7f; color = 0xFF94A3B8.toInt(); textAlign = Paint.Align.CENTER }
        listOf(1, daysInMonth / 2, daysInMonth).forEach { day ->
            val x = margin + (day - 1) * barGap + barWidth / 2
            canvas.drawText("$day", x, y + chartHeight + 12f, smallPaint)
        }
        y += chartHeight + 30f

        doc.finishPage(page)

        // Page — Transaction list
        var pageNum = 3
        page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
        canvas = page.canvas
        y = margin + 20f
        canvas.drawText("Transactions — $monthName", margin, y, headerPaint)
        y += 25f

        val dateFormat = SimpleDateFormat("dd MMM", Locale.getDefault())
        monthRecords.sortedByDescending { it.timestamp }.forEach { record ->
            if (y > pageH - 40) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, pageNum).create())
                canvas = page.canvas
                y = margin
            }
            val date = dateFormat.format(record.timestamp)
            val type = if (record.type == "Income") "+" else "-"
            val line = "$date  ${record.category}  ${record.accountName}"
            canvas.drawText(line, margin, y, bodyPaint)
            val amtPaint = if (record.type == "Income") numPaint.apply { color = 0xFF16A34A.toInt() }
                          else numPaint.apply { color = 0xFFDC2626.toInt() }
            canvas.drawText("$type${String.format("%,.0f", record.amount.toDoubleOrNull() ?: 0.0)}", pageW - margin, y, amtPaint)
            y += 16f
        }

        doc.finishPage(page)

        // Save to Downloads
        return try {
            val fileName = "WalletReport_${monthName.replace(" ", "_")}.pdf"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> doc.writeTo(os) } }
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(dir, fileName)
                FileOutputStream(file).use { doc.writeTo(it) }
            }
            doc.close()
            true
        } catch (e: Exception) {
            doc.close()
            false
        }
    }
}
