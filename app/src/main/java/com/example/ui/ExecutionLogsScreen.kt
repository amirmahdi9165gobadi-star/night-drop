package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.local.ExecutionLogEntity
import com.example.data.local.LogSeverity
import com.example.data.local.TaskStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LogFilter { ALL, SUCCESS, ISSUES }

@Composable
fun ExecutionLogsScreen(
    state: NightDropUiState,
    onClearLogs: () -> Unit
) {
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val filteredLogs = remember(state.logs, filter) {
        when (filter) {
            LogFilter.ALL -> state.logs
            LogFilter.SUCCESS -> state.logs.filter { it.severity == LogSeverity.SUCCESS.name }
            LogFilter.ISSUES -> state.logs.filter {
                it.severity == LogSeverity.WARNING.name || it.severity == LogSeverity.ERROR.name
            }
        }
    }

    val completedCount = remember(state.tasks) {
        state.tasks.count { it.status == TaskStatus.COMPLETED.name }
    }
    val totalMbDownloaded = remember(state.tasks) {
        state.tasks.sumOf { it.bytesDownloaded } / (1024.0 * 1024.0)
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = CyanPrimary)
                        Text(
                            text = "گزارش اجرا و آمار شبانه",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    IconButton(
                        onClick = onClearLogs,
                        enabled = state.logs.isNotEmpty(),
                        modifier = Modifier.testTag("logs_clear_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = "پاک کردن گزارش‌ها")
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MetricCard(
                        label = "تعداد رویدادها",
                        value = "${state.logs.size}",
                        accent = CyanPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "دانلودهای موفق",
                        value = "$completedCount",
                        accent = EmeraldTertiary,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        label = "حجم دریافتی",
                        value = String.format(Locale.US, "%.1f MB", totalMbDownloaded),
                        accent = AmberWarning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = filter == LogFilter.ALL,
                        onClick = { filter = LogFilter.ALL },
                        label = { Text("همه") }
                    )
                    FilterChip(
                        selected = filter == LogFilter.SUCCESS,
                        onClick = { filter = LogFilter.SUCCESS },
                        label = { Text("موفق") }
                    )
                    FilterChip(
                        selected = filter == LogFilter.ISSUES,
                        onClick = { filter = LogFilter.ISSUES },
                        label = { Text("هشدار و خطا") }
                    )
                }
            }

            if (filteredLogs.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = CyanPrimary.copy(alpha = 0.7f),
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "گزارشی برای نمایش وجود ندارد",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "پس از زمان‌بندی یا تست اجرای شبانه، تاریخچه بیدارسازی و دانلود در اینجا ثبت می‌شود.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(filteredLogs, key = { it.id }) { log ->
                    LogTimelineRow(log = log)
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun MetricCard(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LogTimelineRow(log: ExecutionLogEntity) {
    val severity = runCatching { LogSeverity.valueOf(log.severity) }.getOrDefault(LogSeverity.INFO)
    val (color, icon) = when (severity) {
        LogSeverity.SUCCESS -> EmeraldTertiary to Icons.Default.CheckCircle
        LogSeverity.WARNING -> AmberWarning to Icons.Default.WarningAmber
        LogSeverity.ERROR -> CoralError to Icons.Default.ErrorOutline
        LogSeverity.INFO -> CyanPrimary to Icons.Default.Info
    }
    val timeText = remember(log.timestampMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(log.timestampMillis))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = log.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = log.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}
