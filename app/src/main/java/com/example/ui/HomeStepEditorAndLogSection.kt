package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.automation.BrainSnapshot
import com.example.automation.BrainState
import com.example.data.local.ExecutionLogEntity
import com.example.data.local.GestureActionType
import com.example.data.local.LogSeverity
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStepEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.IndigoSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BrainEngineStatusCard(snapshot: BrainSnapshot) {
    val accent = when (snapshot.state) {
        BrainState.IDLE -> CyanPrimary
        BrainState.RECOVERING_ERROR -> AmberWarning
        BrainState.SAFE_SHUTDOWN -> EmeraldTertiary
        BrainState.DOWNLOADING -> CyanPrimary
        else -> IndigoSecondary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
            .testTag("brain_engine_status_card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Psychology, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    Text(
                        text = "موتور هوشمند: ${snapshot.state.persianTitle}",
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = if (snapshot.isCharging) "🔋 ${snapshot.batteryPercent}% ⚡" else "🔋 ${snapshot.batteryPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (snapshot.batteryPercent < 15 && !snapshot.isCharging) CoralError else EmeraldTertiary,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = snapshot.decisionMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (snapshot.speedBytesPerSec > 0L) {
                val etaText = if (snapshot.estimatedEtaSeconds >= 0L) "${snapshot.estimatedEtaSeconds}s" else "—"
                val cutoffText = if (snapshot.cutoffRemainingSeconds >= 0L) "${snapshot.cutoffRemainingSeconds}s" else "—"
                Text(
                    text = "سرعت: ${snapshot.speedBytesPerSec / 1024} KB/s • ETA: $etaText • تا توقف اجباری: $cutoffText",
                    style = MaterialTheme.typography.labelSmall,
                    color = CyanPrimary
                )
            }
        }
    }
}

@Composable
fun HomeMacroStepEditorSection(
    preSteps: List<MacroStepEntity>,
    postSteps: List<MacroStepEntity>,
    onUpdateStepDelay: (MacroStepEntity, Long) -> Unit,
    onMoveStep: (MacroPhase, Long, Int) -> Unit,
    onDeleteStep: (Long) -> Unit
) {
    var selectedPhase by remember { mutableStateOf(MacroPhase.PRE_DOWNLOAD) }
    var editingDelayStep by remember { mutableStateOf<MacroStepEntity?>(null) }

    val activeSteps = if (selectedPhase == MacroPhase.PRE_DOWNLOAD) preSteps else postSteps
    val accent = if (selectedPhase == MacroPhase.PRE_DOWNLOAD) CyanPrimary else IndigoSecondary

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                    Text(
                        text = "ویرایشگر لیست حرکات ماکرو",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedPhase == MacroPhase.PRE_DOWNLOAD,
                    onClick = { selectedPhase = MacroPhase.PRE_DOWNLOAD },
                    label = { Text("قبل دانلود (${preSteps.size})") },
                    modifier = Modifier.testTag("home_editor_tab_pre")
                )
                FilterChip(
                    selected = selectedPhase == MacroPhase.POST_DOWNLOAD,
                    onClick = { selectedPhase = MacroPhase.POST_DOWNLOAD },
                    label = { Text("بعد دانلود (${postSteps.size})") },
                    modifier = Modifier.testTag("home_editor_tab_post")
                )
            }

            if (activeSteps.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "هنوز حرکتی برای این بخش ضبط نشده است. از دکمه «ضبط شناور» برای ثبت لمس و متن دکمه‌ها استفاده کنید.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                activeSteps.forEachIndexed { index, step ->
                    HomeMacroStepRow(
                        index = index,
                        total = activeSteps.size,
                        step = step,
                        accent = accent,
                        onDecreaseDelay = {
                            onUpdateStepDelay(step, (step.delayAfterMs - 250L).coerceAtLeast(500L))
                        },
                        onIncreaseDelay = {
                            onUpdateStepDelay(step, (step.delayAfterMs + 250L).coerceAtMost(60_000L))
                        },
                        onEditExactDelay = { editingDelayStep = step },
                        onMoveUp = { onMoveStep(selectedPhase, step.id, -1) },
                        onMoveDown = { onMoveStep(selectedPhase, step.id, 1) },
                        onDelete = { onDeleteStep(step.id) }
                    )
                }
            }
        }
    }

    val targetStep = editingDelayStep
    if (targetStep != null) {
        var delayInput by remember(targetStep.id) {
            mutableStateOf(targetStep.delayAfterMs.toString())
        }
        AlertDialog(
            onDismissRequest = { editingDelayStep = null },
            title = { Text("تنظیم تاخیر قبل از مرحله بعد") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "مرحله: ${targetStep.label}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = delayInput,
                        onValueChange = { raw -> delayInput = raw.filter { it.isDigit() } },
                        label = { Text("تاخیر (میلی‌ثانیه، حداقل 500)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("step_delay_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val parsed = delayInput.toLongOrNull() ?: targetStep.delayAfterMs
                        onUpdateStepDelay(targetStep, parsed.coerceIn(500L, 60_000L))
                        editingDelayStep = null
                    },
                    modifier = Modifier.testTag("step_delay_confirm")
                ) {
                    Text("ذخیره")
                }
            },
            dismissButton = {
                TextButton(onClick = { editingDelayStep = null }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
private fun HomeMacroStepRow(
    index: Int,
    total: Int,
    step: MacroStepEntity,
    accent: androidx.compose.ui.graphics.Color,
    onDecreaseDelay: () -> Unit,
    onIncreaseDelay: () -> Unit,
    onEditExactDelay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val smartLabel = step.primaryNodeLabel()
    val stepIcon = when (step.actionType) {
        GestureActionType.SWIPE.name -> Icons.Default.Swipe
        GestureActionType.TAP.name -> if (smartLabel != null) Icons.Default.SmartButton else Icons.Default.TouchApp
        else -> Icons.Default.TouchApp
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(stepIcon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = step.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val detailLine = buildString {
                            if (smartLabel != null) {
                                append("نود: «$smartLabel» • ")
                            }
                            if (step.actionType == GestureActionType.TAP.name) {
                                append("مختصات: (${step.pixelStartX},${step.pixelStartY})")
                            } else if (step.actionType == GestureActionType.SWIPE.name) {
                                append("سوایپ ${step.durationMs}ms")
                            } else {
                                append(step.actionType)
                            }
                        }
                        Text(
                            text = detailLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (smartLabel != null) EmeraldTertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onMoveUp, enabled = index > 0, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "بالا", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onMoveDown, enabled = index < total - 1, modifier = Modifier.size(34.dp)) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "پایین", modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                        Icon(
                            Icons.Default.DeleteOutline,
                            contentDescription = "حذف حرکت",
                            tint = CoralError,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Delay editor row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "تاخیر تا مرحله بعد:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    IconButton(onClick = onDecreaseDelay, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Remove, contentDescription = "کاهش تاخیر", modifier = Modifier.size(16.dp))
                    }
                    Surface(
                        color = accent.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable(onClick = onEditExactDelay)
                    ) {
                        Text(
                            text = "${step.delayAfterMs} ms ✎",
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = onIncreaseDelay, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Add, contentDescription = "افزایش تاخیر", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun LastNightExecutionLogSection(
    logs: List<ExecutionLogEntity>,
    onOpenFullLogs: () -> Unit,
    onClearLogs: () -> Unit
) {
    val recentLogs = remember(logs) { logs.take(8) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = CyanPrimary, modifier = Modifier.size(20.dp))
                    Text(
                        text = "گزارش شب گذشته",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (logs.isNotEmpty()) {
                        IconButton(onClick = onClearLogs, modifier = Modifier.size(36.dp)) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "پاک کردن گزارش‌ها", modifier = Modifier.size(18.dp))
                        }
                    }
                    TextButton(
                        onClick = onOpenFullLogs,
                        modifier = Modifier.testTag("home_view_all_logs_btn")
                    ) {
                        Text("همه (${logs.size})")
                    }
                }
            }

            if (recentLogs.isEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "هنوز گزارشی ثبت نشده است. زمان بیدار شدن گوشی، اجرای تک‌تک گام‌های ماکرو، نتیجه دانلودها و ساعت قطع اینترنت اینجا نمایش داده می‌شود.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                recentLogs.forEach { log ->
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

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = log.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = log.detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = color,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}
