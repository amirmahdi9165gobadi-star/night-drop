package com.example.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.data.local.GestureActionType
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStepEntity
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.IndigoSecondary
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GestureRecorderSheet(
    phase: MacroPhase,
    steps: List<MacroStepEntity>,
    defaultDelayMs: Long,
    isDryRunning: Boolean,
    dryRunActiveIndex: Int,
    onDelayChanged: (Long) -> Unit,
    onRecordTap: (Float, Float, Int, Int) -> Unit,
    onRecordSwipe: (Float, Float, Float, Float, Long, Int, Int) -> Unit,
    onAddQuickStep: (GestureActionType, String, Long) -> Unit,
    onUpdateStepDelay: (MacroStepEntity, Long) -> Unit,
    onDeleteStep: (Long) -> Unit,
    onClearPhase: () -> Unit,
    onRunDryPlayback: () -> Unit,
    onStopDryPlayback: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val rootView = LocalView.current
    val screenWidthPx = remember(rootView) { rootView.width.coerceAtLeast(1080) }
    val screenHeightPx = remember(rootView) { rootView.height.coerceAtLeast(2400) }

    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }
    var dragStartedAt by remember { mutableStateOf(0L) }

    val pulseProgress by animateFloatAsState(
        targetValue = if (dryRunActiveIndex >= 0) 1f else 0.2f,
        animationSpec = tween(350),
        label = "dry_run_pulse"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (phase == MacroPhase.PRE_DOWNLOAD) {
                            "ضبط توالی قبل از دانلود"
                        } else {
                            "ضبط توالی بعد از دانلود"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "روی صفحه شبیه‌ساز لمس (Tap) یا کشیدن (Swipe) انجام دهید تا مختصات و تأخیر ذخیره شوند.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("recorder_close_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "بستن پنل ضبط")
                }
            }

            // Interactive Touch & Swipe Capture Pad
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF060B16))
                    .border(
                        width = 1.5.dp,
                        color = if (isDryRunning) EmeraldTertiary else CyanPrimary.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(phase, canvasSize) {
                        detectTapGestures { offset ->
                            val w = canvasSize.width.coerceAtLeast(1)
                            val h = canvasSize.height.coerceAtLeast(1)
                            val nx = (offset.x / w).coerceIn(0f, 1f)
                            val ny = (offset.y / h).coerceIn(0f, 1f)
                            onRecordTap(nx, ny, screenWidthPx, screenHeightPx)
                        }
                    }
                    .pointerInput(phase, canvasSize) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragCurrent = offset
                                dragStartedAt = System.currentTimeMillis()
                            },
                            onDrag = { change, _ ->
                                change.consume()
                                dragCurrent = change.position
                            },
                            onDragEnd = {
                                val start = dragStart
                                val end = dragCurrent
                                if (start != null && end != null) {
                                    val w = canvasSize.width.coerceAtLeast(1)
                                    val h = canvasSize.height.coerceAtLeast(1)
                                    val duration = (System.currentTimeMillis() - dragStartedAt).coerceIn(150L, 2_000L)
                                    onRecordSwipe(
                                        (start.x / w).coerceIn(0f, 1f),
                                        (start.y / h).coerceIn(0f, 1f),
                                        (end.x / w).coerceIn(0f, 1f),
                                        (end.y / h).coerceIn(0f, 1f),
                                        duration,
                                        screenWidthPx,
                                        screenHeightPx
                                    )
                                }
                                dragStart = null
                                dragCurrent = null
                            },
                            onDragCancel = {
                                dragStart = null
                                dragCurrent = null
                            }
                        )
                    }
                    .testTag("gesture_capture_pad")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    // Subtle grid lines
                    val cols = 4
                    val rows = 4
                    val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    for (c in 1 until cols) {
                        val x = size.width * (c / cols.toFloat())
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            pathEffect = dash
                        )
                    }
                    for (r in 1 until rows) {
                        val y = size.height * (r / rows.toFloat())
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            pathEffect = dash
                        )
                    }

                    // Existing recorded steps
                    steps.forEachIndexed { idx, step ->
                        val isActive = idx == dryRunActiveIndex
                        val start = Offset(step.normStartX * size.width, step.normStartY * size.height)
                        val end = Offset(step.normEndX * size.width, step.normEndY * size.height)
                        val color = when {
                            isActive -> EmeraldTertiary
                            step.actionType == GestureActionType.TAP.name -> CyanPrimary
                            step.actionType == GestureActionType.SWIPE.name -> IndigoSecondary
                            else -> Color.Transparent
                        }

                        if (step.actionType == GestureActionType.TAP.name) {
                            drawCircle(
                                color = color.copy(alpha = if (isActive) 0.35f else 0.16f),
                                radius = if (isActive) 34f * pulseProgress else 22f,
                                center = start
                            )
                            drawCircle(color = color, radius = if (isActive) 12f else 8f, center = start)
                        } else if (step.actionType == GestureActionType.SWIPE.name) {
                            drawLine(
                                color = color,
                                start = start,
                                end = end,
                                strokeWidth = if (isActive) 8f else 5f,
                                cap = StrokeCap.Round
                            )
                            drawCircle(color = color, radius = 9f, center = start)
                            drawCircle(color = EmeraldTertiary, radius = 9f, center = end)
                        }
                    }

                    // Active drag preview
                    val ds = dragStart
                    val dc = dragCurrent
                    if (ds != null && dc != null) {
                        drawLine(
                            color = CyanPrimary,
                            start = ds,
                            end = dc,
                            strokeWidth = 6f,
                            cap = StrokeCap.Round
                        )
                        drawCircle(color = CyanPrimary, radius = 10f, center = ds)
                        drawCircle(color = EmeraldTertiary, radius = 10f, center = dc)
                    }
                }

                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = if (isDryRunning) "در حال شبیه‌سازی..." else "پد ضبط لمس و کشیدن درون‌برنامه‌ای",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White
                    )
                }
            }

            // Default delay slider
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "تاخیر پیش‌فرض پس از هر حرکت",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${defaultDelayMs} ms",
                        style = MaterialTheme.typography.labelLarge,
                        color = CyanPrimary
                    )
                }
                Slider(
                    value = defaultDelayMs.toFloat(),
                    onValueChange = { onDelayChanged(((it / 100f).roundToInt() * 100).toLong()) },
                    valueRange = 200f..5000f
                )
            }

            // Quick compliant system action chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AssistChip(
                    onClick = {
                        onAddQuickStep(GestureActionType.WAKE_SCREEN, "بیدارسازی صفحه نمایش", 600L)
                    },
                    label = { Text("بیدارسازی صفحه") },
                    leadingIcon = { Icon(Icons.Default.ScreenLockPortrait, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = CyanPrimary)
                )
                AssistChip(
                    onClick = {
                        onAddQuickStep(GestureActionType.OPEN_CONNECTIVITY_PANEL, "باز کردن پنل اینترنت", 1500L)
                    },
                    label = { Text("پنل اینترنت") },
                    leadingIcon = { Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = IndigoSecondary)
                )
                AssistChip(
                    onClick = {
                        onAddQuickStep(GestureActionType.VIBRATE_ALERT, "هشدار لرزشی کوتاه", 400L)
                    },
                    label = { Text("لرزش هشدار") },
                    leadingIcon = { Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    colors = AssistChipDefaults.assistChipColors(leadingIconContentColor = EmeraldTertiary)
                )
                AssistChip(
                    onClick = {
                        onAddQuickStep(GestureActionType.WAIT_DELAY, "مکث ۵ ثانیه‌ای", 5000L)
                    },
                    label = { Text("مکث ۵ ثانیه") },
                    leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            // Playback & Clear controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { if (isDryRunning) onStopDryPlayback() else onRunDryPlayback() },
                    enabled = steps.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDryRunning) MaterialTheme.colorScheme.error else CyanPrimary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("recorder_dry_run_button")
                ) {
                    Icon(
                        imageVector = if (isDryRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isDryRunning) "توقف شبیه‌سازی" else "شبیه‌سازی توالی (${steps.size})")
                }

                OutlinedButton(
                    onClick = onClearPhase,
                    enabled = steps.isNotEmpty() && !isDryRunning,
                    modifier = Modifier
                        .height(48.dp)
                        .testTag("recorder_clear_button")
                ) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = "پاک کردن همه گام‌ها")
                }
            }

            // Recorded Steps List
            if (steps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "هنوز گامی ثبت نشده است؛ روی پد بالا لمس یا سوایپ کنید.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(190.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(steps, key = { _, item -> item.id }) { idx, step ->
                        val isHighlighted = idx == dryRunActiveIndex
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isHighlighted) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(26.dp)
                                            .clip(CircleShape)
                                            .background(CyanPrimary.copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${idx + 1}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = CyanPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Column {
                                        Text(
                                            text = step.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        val smartHint = step.primaryNodeLabel()?.let { "نود: «$it» • " } ?: ""
                                        Text(
                                            text = "${smartHint}مدت: ${step.durationMs}ms • تاخیر بعد: ${step.delayAfterMs}ms",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            onUpdateStepDelay(step, (step.delayAfterMs - 250L).coerceAtLeast(500L))
                                        }
                                    ) {
                                        Icon(Icons.Default.Remove, contentDescription = "کاهش تاخیر", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            onUpdateStepDelay(step, step.delayAfterMs + 250L)
                                        }
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = "افزایش تاخیر", modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDeleteStep(step.id) }) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "حذف گام",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
