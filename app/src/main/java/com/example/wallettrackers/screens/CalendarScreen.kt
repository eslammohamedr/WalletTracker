package com.example.wallettrackers.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.wallettrackers.model.Record
import com.example.wallettrackers.viewmodel.HomeViewModel
import com.example.wallettrackers.components.RecordCard
import java.text.SimpleDateFormat
import java.util.*

import com.example.wallettrackers.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(viewModel: HomeViewModel, onBack: () -> Unit) {
    val records by viewModel.records
    val today = remember { Calendar.getInstance() }
    var displayedMonth by remember { mutableIntStateOf(today.get(Calendar.MONTH)) }
    var displayedYear by remember { mutableIntStateOf(today.get(Calendar.YEAR)) }
    var selectedDay by remember { mutableStateOf<Int?>(today.get(Calendar.DAY_OF_MONTH)) }

    val monthLabel = remember(displayedMonth, displayedYear) {
        val cal = Calendar.getInstance().apply { set(displayedYear, displayedMonth, 1) }
        SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(cal.time)
    }

    val recordsByDay = remember(records, displayedMonth, displayedYear) {
        records.filter { r ->
            val rc = Calendar.getInstance().apply { time = r.timestamp }
            rc.get(Calendar.MONTH) == displayedMonth && rc.get(Calendar.YEAR) == displayedYear
        }.groupBy { r ->
            Calendar.getInstance().apply { time = r.timestamp }.get(Calendar.DAY_OF_MONTH)
        }
    }

    val selectedRecords = remember(selectedDay, recordsByDay) {
        selectedDay?.let { recordsByDay[it] }?.sortedByDescending { it.timestamp } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Calendar", fontWeight = FontWeight.Bold, color = DGTextPrimary) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, null, tint = DGTextPrimary) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DGBackground)
            )
        },
        containerColor = DGBackground
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                // Month navigation
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        if (displayedMonth == 0) { displayedMonth = 11; displayedYear-- } else displayedMonth--
                        selectedDay = null
                    }) { Icon(Icons.Default.ChevronLeft, null, tint = DGVioletLight) }
                    Text(monthLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = DGTextPrimary)
                    IconButton(onClick = {
                        if (displayedMonth == 11) { displayedMonth = 0; displayedYear++ } else displayedMonth++
                        selectedDay = null
                    }) { Icon(Icons.Default.ChevronRight, null, tint = DGVioletLight) }
                }

                // Day-of-week headers
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    listOf("Sun","Mon","Tue","Wed","Thu","Fri","Sat").forEach { d ->
                        Text(d, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = DGTextSecondary)
                    }
                }
                Spacer(Modifier.height(4.dp))

                // Calendar grid
                val cal = Calendar.getInstance().apply { set(displayedYear, displayedMonth, 1) }
                val firstDow = cal.get(Calendar.DAY_OF_WEEK) - 1
                val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                val totalCells = firstDow + daysInMonth
                val rows = (totalCells + 6) / 7

                Column(Modifier.padding(horizontal = 8.dp)) {
                    for (row in 0 until rows) {
                        Row(Modifier.fillMaxWidth()) {
                            for (col in 0..6) {
                                val cellIdx = row * 7 + col
                                val day = cellIdx - firstDow + 1
                                if (day in 1..daysInMonth) {
                                    val isToday = day == today.get(Calendar.DAY_OF_MONTH) &&
                                            displayedMonth == today.get(Calendar.MONTH) &&
                                            displayedYear == today.get(Calendar.YEAR)
                                    val isSelected = day == selectedDay
                                    val hasRecords = recordsByDay.containsKey(day)
                                    val dayExpense = recordsByDay[day]?.filter { it.type == "Expense" }?.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } ?: 0.0
                                    val dayIncome = recordsByDay[day]?.filter { it.type == "Income" }?.sumOf { it.amount.toDoubleOrNull() ?: 0.0 } ?: 0.0

                                    Box(
                                        modifier = Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(when { 
                                                isSelected -> DGViolet
                                                isToday -> DGIndigo.copy(alpha = 0.3f) 
                                                else -> DGSurface 
                                            })
                                            .clickable { selectedDay = day },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(day.toString(), style = MaterialTheme.typography.bodySmall, fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Normal,
                                                color = when { isSelected -> Color.White; isToday -> DGVioletLight; else -> DGTextPrimary })
                                            if (hasRecords) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                                    if (dayExpense > 0) Box(Modifier.size(4.dp).clip(CircleShape).background(DGRed))
                                                    if (dayIncome > 0) Box(Modifier.size(4.dp).clip(CircleShape).background(DGGreen))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // Selected day records
            if (selectedDay != null) {
                item {
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = DGIndigo.copy(alpha = 0.2f))
                    Spacer(Modifier.height(8.dp))
                    val dayTotal = selectedRecords.sumOf {
                        if (it.type == "Income") it.amount.toDoubleOrNull() ?: 0.0
                        else -(it.amount.toDoubleOrNull() ?: 0.0)
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(modifier = Modifier.width(3.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(AccentGradient))
                            Text("Day $selectedDay", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = DGTextPrimary)
                        }
                        if (selectedRecords.isNotEmpty()) {
                            Text("${if (dayTotal >= 0) "+" else ""}${"%.2f".format(dayTotal)} EGP",
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                                color = if (dayTotal >= 0) DGGreen else DGRed)
                        }
                    }
                }
                if (selectedRecords.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Text("No records on this day", color = DGTextSecondary, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                } else {
                    items(selectedRecords, key = { it.id }) { record ->
                        RecordCard(record = record, onLongClick = {})
                    }
                }
            }
        }
    }
}
