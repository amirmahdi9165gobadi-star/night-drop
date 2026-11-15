package com.example.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleOutline
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SettingsPower
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.local.DownloadTaskEntity
import com.example.data.local.MacroPhase
import com.example.data.local.TaskStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CoralError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.IndigoSecondary
import java.util.Locale

private enum class ActiveTimePickerMode { START_TIME, CUTOFF_TIME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NightDropScreen(
    state: NightDropUiState,
    onUrlChange: (String) -> Unit,
    onFileNameChange: (String) -> Unit,
    onAllowMeteredChange: (Boolean) -> Unit,
    onStartTimeSelected: (Int, Int) -> Unit,
    onCutoffTimeSelected: (Int, Int) -> Unit,
    onCutoffEnabledChange: (Boolean) -> Unit,
    onAddToQueue: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onOpenDownloadsFolder: () -> Unit,
    onStartFloatingRecorder: (MacroPhase) -> Unit,
    onStartVisualMacroTest: (MacroPhase) -> Unit,
    onOpenStepEditor: (MacroPhase) -> Unit,
    onUpdateStepDelay: (com.example.data.local.MacroStepEntity, Long) -> Unit,
    onMoveStep: (MacroPhase, Long, Int) -> Unit,
    onDeleteStep: (Long) -> Unit,
    onOpenFullLogs: () -> Unit,
    onClearLogs: () -> Unit,
    onScheduleQueue: () -> Unit,
    onImmediateQueueRun: () -> Unit,
    onRetryTask: (DownloadTaskEntity) -> Unit,
    onCancelOrDeleteTask: (DownloadTaskEntity) -> Unit,
    onClearFinishedTasks: () -> Unit
) {
    var activePicker by remember { mutableStateOf<ActiveTimePickerMode?>(null) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HeroHeaderCard()
            }

            item {
                BrainEngineStatusCard(snapshot = state.brainSnapshot)
            }

            item {
                PermissionsQuickPanel(
                    state = state,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onOpenOverlaySettings = onOpenOverlaySettings,
                    onOpenExactAlarmSettings = onOpenExactAlarmSettings,
                    onOpenBatterySettings = onOpenBatterySettings,
                    onOpenAutostartSettings = onOpenAutostartSettings,
                    onRequestNotifications = onRequestNotifications
                )
            }

            item {
                SequentialQueueBuilderCard(
                    state = state,
                    onUrlChange = onUrlChange,
                    onFileNameChange = onFileNameChange,
                    onAllowMeteredChange = onAllowMeteredChange,
                    onAddToQueue = onAddToQueue,
                    onOpenStartTimePicker = { activePicker = ActiveTimePickerMode.START_TIME },
                    onOpenCutoffTimePicker = { activePicker = ActiveTimePickerMode.CUTOFF_TIME },
                    onCutoffEnabledChange = onCutoffEnabledChange
                )
            }

            item {
                MacroSequenceCardsRow(
                    preCount = state.preSteps.size,
                    postCount = state.postSteps.size,
                    onStartPreFloating = { onStartFloatingRecorder(MacroPhase.PRE_DOWNLOAD) },
                    onStartPostFloating = { onStartFloatingRecorder(MacroPhase.POST_DOWNLOAD) },
                    onTestPreMacro = { onStartVisualMacroTest(MacroPhase.PRE_DOWNLOAD) },
                    onTestPostMacro = { onStartVisualMacroTest(MacroPhase.POST_DOWNLOAD) },
                    onEditPreSteps = { onOpenStepEditor(MacroPhase.PRE_DOWNLOAD) },
                    onEditPostSteps = { onOpenStepEditor(MacroPhase.POST_DOWNLOAD) }
                )
            }

            item {
                HomeMacroStepEditorSection(
                    preSteps = state.preSteps,
                    postSteps = state.postSteps,
                    onUpdateStepDelay = onUpdateStepDelay,
                    onMoveStep = onMoveStep,
                    onDeleteStep = onDeleteStep
                )
            }

            item {
                ActionButtonsRow(
                    queueCount = state.tasks.count {
                        it.status == TaskStatus.QUEUED.name || it.status == TaskStatus.SCHEDULED.name
                    },
                    onScheduleQueue = onScheduleQueue,
                    onImmediateQueueRun = onImmediateQueueRun
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "صف دانلود متوالی (${state.tasks.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Row {
                        if (state.tasks.any {
                                it.status == TaskStatus.COMPLETED.name ||
                                    it.status == TaskStatus.FAILED.name ||
                                    it.status == TaskStatus.CANCELLED.name ||
                                    it.status == TaskStatus.SKIPPED.name
                            }
                        ) {
                            IconButton(
                                onClick = onClearFinishedTasks,
                                modifier = Modifier.testTag("clear_finished_tasks_btn")
                            ) {
                                Icon(Icons.Default.DeleteSweep, contentDescription = "پاکسازی تکمیل‌شده‌ها")
                            }
                        }
                        TextButton(
                            onClick = onOpenDownloadsFolder,
                            modifier = Modifier.testTag("open_downloads_folder_btn")
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("پوشه دانلودها")
                        }
                    }
                }
            }

            if (state.tasks.isEmpty()) {
                item {
                    EmptyQueueCard()
                }
            } else {
                itemsIndexed(state.tasks, key = { _, task -> task.id }) { index, task ->
                    DownloadTaskCard(
                        orderNumber = index + 1,
                        task = task,
                        onRetry = { onRetryTask(task) },
                        onOpenDownloads = onOpenDownloadsFolder,
                        onCancelOrDelete = { onCancelOrDeleteTask(task) }
                    )
                }
            }

            item {
                LastNightExecutionLogSection(
                    logs = state.logs,
                    onOpenFullLogs = onOpenFullLogs,
                    onClearLogs = onClearLogs
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    val pickerMode = activePicker
    if (pickerMode != null) {
        val initialHour = if (pickerMode == ActiveTimePickerMode.START_TIME) {
            state.settings.startHour
        } else {
            state.settings.cutoffHour
        }
        val initialMinute = if (pickerMode == ActiveTimePickerMode.START_TIME) {
            state.settings.startMinute
        } else {
            state.settings.cutoffMinute
        }
        val pickerState = rememberTimePickerState(
            initialHour = initialHour,
            initialMinute = initialMinute,
            is24Hour = true
        )
        AlertDialog(
            onDismissRequest = { activePicker = null },
            title = {
                Text(
                    if (pickerMode == ActiveTimePickerMode.START_TIME) {
                        "انتخاب ساعت شروع دانلود شبانه"
                    } else {
                        "انتخاب ساعت توقف اجباری (Cutoff)"
                    }
                )
            },
            text = {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        TimePicker(state = pickerState)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (pickerMode == ActiveTimePickerMode.START_TIME) {
                            onStartTimeSelected(pickerState.hour, pickerState.minute)
                        } else {
                            onCutoffTimeSelected(pickerState.hour, pickerState.minute)
                        }
                        activePicker = null
                    },
                    modifier = Modifier.testTag("time_picker_confirm")
                ) {
                    Text("تایید")
                }
            },
            dismissButton = {
                TextButton(onClick = { activePicker = null }) {
                    Text("انصراف")
                }
            }
        )
    }
}

@Composable
private fun HeroHeaderCard() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
    ) {
        Image(
            painter = painterResource(id = R.drawable.img_night_sky_banner),
            contentDescription = stringResource(id = R.string.hero_banner_desc),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xE6070D1A),
                            Color(0x99070D1A),
                            Color(0x4D070D1A)
                        )
                    )
                )
                .padding(18.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.NightsStay,
                        contentDescription = null,
                        tint = CyanPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = "مدیریت دانلود شبانه NightDrop",
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White
                    )
                }
                Text(
                    text = "صف متوالی، تست اینترنت (Ping 204)، توقف اجباری و تست بصری لمس‌ها",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFCBD5E1)
                )
            }
        }
    }
}

@Composable
private fun PermissionsQuickPanel(
    state: NightDropUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenExactAlarmSettings: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onOpenAutostartSettings: () -> Unit,
    onRequestNotifications: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "مجوزهای سریع اتوماسیون و پس‌زمینه",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = if (state.permissions.isNetworkConnected) "شبکه متصل" else "آفلاین",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.permissions.isNetworkConnected) EmeraldTertiary else AmberWarning
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PermissionShortcutTile(
                    title = "سرویس دسترس‌پذیری",
                    subtitle = if (state.permissions.isAccessibilityEnabled) "متصل" else "غیرفعال",
                    isGranted = state.permissions.isAccessibilityEnabled,
                    icon = Icons.Default.AccessibilityNew,
                    testTag = "perm_accessibility_btn",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenAccessibilitySettings
                )
                PermissionShortcutTile(
                    title = "نمایش روی برنامه‌ها",
                    subtitle = if (state.permissions.canDrawOverlays) "مجاز" else "نیازمند تایید",
                    isGranted = state.permissions.canDrawOverlays,
                    icon = Icons.Default.Layers,
                    testTag = "perm_overlay_btn",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenOverlaySettings
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PermissionShortcutTile(
                    title = "آلارم دقیق",
                    subtitle = if (state.permissions.canScheduleExactAlarms) "فعال" else "نیازمند تایید",
                    isGranted = state.permissions.canScheduleExactAlarms,
                    icon = Icons.Default.AccessAlarm,
                    testTag = "perm_exact_alarm_btn",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenExactAlarmSettings
                )
                PermissionShortcutTile(
                    title = "باتری پس‌زمینه",
                    subtitle = if (state.permissions.isIgnoringBatteryOptimizations) "بدون محدودیت" else "بهینه‌سازی باز شود",
                    isGranted = state.permissions.isIgnoringBatteryOptimizations,
                    icon = Icons.Default.BatteryChargingFull,
                    testTag = "perm_battery_btn",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenBatterySettings
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PermissionShortcutTile(
                    title = "Autostart شیائومی/پوکو",
                    subtitle = "اجرای خودکار",
                    isGranted = null,
                    icon = Icons.Default.SettingsPower,
                    testTag = "perm_autostart_btn",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenAutostartSettings
                )
                PermissionShortcutTile(
                    title = "اعلان وضعیت",
                    subtitle = if (state.permissions.hasNotificationPermission) "فعال" else "درخواست مجوز",
                    isGranted = state.permissions.hasNotificationPermission,
                    icon = Icons.Default.Notifications,
                    testTag = "perm_notif_btn",
                    modifier = Modifier.weight(1f),
                    onClick = onRequestNotifications
                )
            }
        }
    }
}

@Composable
private fun PermissionShortcutTile(
    title: String,
    subtitle: String,
    isGranted: Boolean?,
    icon: ImageVector,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val accent = when (isGranted) {
        true -> EmeraldTertiary
        false -> AmberWarning
        null -> IndigoSecondary
    }

    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(17.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun SequentialQueueBuilderCard(
    state: NightDropUiState,
    onUrlChange: (String) -> Unit,
    onFileNameChange: (String) -> Unit,
    onAllowMeteredChange: (Boolean) -> Unit,
    onAddToQueue: () -> Unit,
    onOpenStartTimePicker: () -> Unit,
    onOpenCutoffTimePicker: () -> Unit,
    onCutoffEnabledChange: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "افزودن لینک به صف متوالی و ساعت اجرا",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = state.urlInput,
                    onValueChange = onUrlChange,
                    label = { Text("Direct Download URL (https://...)") },
                    leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("download_url_input")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = state.customFileName,
                    onValueChange = onFileNameChange,
                    label = { Text("نام فایل (اختیاری)") },
                    leadingIcon = { Icon(Icons.Default.Download, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("download_filename_input")
                )
                Button(
                    onClick = onAddToQueue,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldTertiary),
                    modifier = Modifier
                        .height(54.dp)
                        .testTag("add_to_queue_btn")
                ) {
                    Icon(Icons.Default.AddCircleOutline, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("افزودن به صف", fontWeight = FontWeight.Bold)
                }
            }

            // Dual Time Pickers: Start Time & Force Stop Cutoff Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TimeSlotTile(
                    title = "ساعت شروع دانلود",
                    timeText = String.format(Locale.US, "%02d:%02d", state.settings.startHour, state.settings.startMinute),
                    accent = CyanPrimary,
                    icon = Icons.Default.Schedule,
                    testTag = "time_picker_trigger",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenStartTimePicker
                )
                TimeSlotTile(
                    title = "ساعت توقف اجباری",
                    timeText = String.format(Locale.US, "%02d:%02d", state.settings.cutoffHour, state.settings.cutoffMinute),
                    accent = if (state.settings.cutoffEnabled) CoralError else Color.Gray,
                    icon = Icons.Default.PowerSettingsNew,
                    testTag = "cutoff_time_picker_trigger",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCutoffTimePicker
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "فعال بودن تایمر توقف اجباری (لغو دانلود و قطع دیتا)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.settings.cutoffEnabled,
                    onCheckedChange = onCutoffEnabledChange,
                    modifier = Modifier.testTag("cutoff_enabled_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "اجازه دانلود با دیتای سیم‌کارت (Metered)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = state.allowMetered,
                    onCheckedChange = onAllowMeteredChange,
                    modifier = Modifier.testTag("allow_metered_switch")
                )
            }
        }
    }
}

@Composable
private fun TimeSlotTile(
    title: String,
    timeText: String,
    accent: Color,
    icon: ImageVector,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = timeText,
                style = MaterialTheme.typography.headlineSmall,
                color = accent,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun MacroSequenceCardsRow(
    preCount: Int,
    postCount: Int,
    onStartPreFloating: () -> Unit,
    onStartPostFloating: () -> Unit,
    onTestPreMacro: () -> Unit,
    onTestPostMacro: () -> Unit,
    onEditPreSteps: () -> Unit,
    onEditPostSteps: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SequenceLauncherCard(
            title = "ماکروی قبل از دانلود",
            subtitle = "روشن کردن دیتا و انتخاب سیم‌کارت",
            count = preCount,
            accent = CyanPrimary,
            recordTag = "record_pre_macro_btn",
            testMacroTag = "test_pre_macro_btn",
            editTag = "edit_pre_macro_btn",
            modifier = Modifier.weight(1f),
            onLaunchFloating = onStartPreFloating,
            onTestMacro = onTestPreMacro,
            onEditSteps = onEditPreSteps
        )
        SequenceLauncherCard(
            title = "ماکروی بعد از دانلود",
            subtitle = "قطع دیتا و منوی خاموش کردن",
            count = postCount,
            accent = IndigoSecondary,
            recordTag = "record_post_macro_btn",
            testMacroTag = "test_post_macro_btn",
            editTag = "edit_post_macro_btn",
            modifier = Modifier.weight(1f),
            onLaunchFloating = onStartPostFloating,
            onTestMacro = onTestPostMacro,
            onEditSteps = onEditPostSteps
        )
    }
}

@Composable
private fun SequenceLauncherCard(
    title: String,
    subtitle: String,
    count: Int,
    accent: Color,
    recordTag: String,
    testMacroTag: String,
    editTag: String,
    modifier: Modifier = Modifier,
    onLaunchFloating: () -> Unit,
    onTestMacro: () -> Unit,
    onEditSteps: () -> Unit
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.PictureInPictureAlt, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            Text(
                text = if (count == 0) "بدون حرکت ضبط‌شده" else "$count حرکت آماده اجرا",
                style = MaterialTheme.typography.labelSmall,
                color = accent
            )
            Button(
                onClick = onLaunchFloating,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag(recordTag)
            ) {
                Text("ضبط شناور", style = MaterialTheme.typography.labelLarge)
            }
            OutlinedButton(
                onClick = onTestMacro,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag(testMacroTag)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp), tint = CoralError)
                Spacer(Modifier.width(4.dp))
                Text("تست ماکرو", style = MaterialTheme.typography.labelLarge)
            }
            TextButton(
                onClick = onEditSteps,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(editTag)
            ) {
                Text("ویرایش گام‌ها")
            }
        }
    }
}

@Composable
private fun ActionButtonsRow(
    queueCount: Int,
    onScheduleQueue: () -> Unit,
    onImmediateQueueRun: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onScheduleQueue,
            colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
            modifier = Modifier
                .weight(1.4f)
                .height(54.dp)
                .testTag("schedule_download_btn")
        ) {
            Icon(Icons.Default.RocketLaunch, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("شروع زمان‌بندی ($queueCount)", fontWeight = FontWeight.Bold)
        }

        OutlinedButton(
            onClick = onImmediateQueueRun,
            modifier = Modifier
                .weight(1f)
                .height(54.dp)
                .testTag("test_now_btn")
        ) {
            Icon(Icons.Default.PlayCircleOutline, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("اجرای فوری صف")
        }
    }
}

@Composable
private fun EmptyQueueCard() {
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
                imageVector = Icons.Default.CloudDownload,
                contentDescription = null,
                tint = CyanPrimary.copy(alpha = 0.7f),
                modifier = Modifier.size(40.dp)
            )
            Text(
                text = "صف دانلود متوالی خالی است",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "چندین لینک مستقیم را با «افزودن به صف» اضافه کنید و سپس زمان شروع و توقف اجباری را زمان‌بندی کنید.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DownloadTaskCard(
    orderNumber: Int,
    task: DownloadTaskEntity,
    onRetry: () -> Unit,
    onOpenDownloads: () -> Unit,
    onCancelOrDelete: () -> Unit
) {
    val status = runCatching { TaskStatus.valueOf(task.status) }.getOrDefault(TaskStatus.QUEUED)
    val (statusText, statusColor, statusIcon) = when (status) {
        TaskStatus.QUEUED -> Triple("در انتظار در صف", AmberWarning, Icons.Default.HourglassTop)
        TaskStatus.SCHEDULED -> Triple(
            String.format(Locale.US, "زمان‌بندی %02d:%02d", task.scheduledHour, task.scheduledMinute),
            CyanPrimary,
            Icons.Default.Schedule
        )
        TaskStatus.PRE_ACTIONS_RUNNING -> Triple("اجرای ماکروی اولیه", IndigoSecondary, Icons.Default.TouchApp)
        TaskStatus.WAITING_NETWORK -> Triple("تست اینترنت (Ping 204)", AmberWarning, Icons.Default.Wifi)
        TaskStatus.DOWNLOADING -> Triple("در حال دانلود", CyanPrimary, Icons.Default.CloudDownload)
        TaskStatus.PAUSED -> Triple("توقف موقت (اولویت‌بندی هوشمند)", AmberWarning, Icons.Default.HourglassTop)
        TaskStatus.POST_ACTIONS_RUNNING -> Triple("اجرای ماکروی پایانی", IndigoSecondary, Icons.Default.TouchApp)
        TaskStatus.COMPLETED -> Triple("تکمیل شده", EmeraldTertiary, Icons.Default.CheckCircle)
        TaskStatus.FAILED -> Triple("ناموفق", CoralError, Icons.Default.WarningAmber)
        TaskStatus.CANCELLED -> Triple("لغو شده", Color.Gray, Icons.Default.DeleteOutline)
        TaskStatus.SKIPPED -> Triple("رد شده (کمبود فضا)", AmberWarning, Icons.Default.WarningAmber)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$orderNumber",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Icon(statusIcon, contentDescription = null, tint = statusColor, modifier = Modifier.size(18.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = task.fileName,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor
                        )
                    }
                }
                Row {
                    if (status == TaskStatus.FAILED || status == TaskStatus.CANCELLED || status == TaskStatus.PAUSED || status == TaskStatus.SKIPPED) {
                        IconButton(onClick = onRetry) {
                            Icon(Icons.Default.Refresh, contentDescription = "ادامه / تلاش مجدد", tint = CyanPrimary)
                        }
                    } else if (status == TaskStatus.COMPLETED) {
                        IconButton(onClick = onOpenDownloads) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "باز کردن دانلودها", tint = EmeraldTertiary)
                        }
                    }
                    IconButton(onClick = onCancelOrDelete) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "حذف از صف",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Text(
                text = task.url,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (status == TaskStatus.DOWNLOADING && task.totalBytes > 0L) {
                val fraction = (task.bytesDownloaded.toFloat() / task.totalBytes.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanPrimary
                )
                Text(
                    text = "${(fraction * 100).toInt()}% (${task.bytesDownloaded / 1024} KB / ${task.totalBytes / 1024} KB)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!task.errorMessage.isNullOrBlank()) {
                Text(
                    text = task.errorMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = CoralError
                )
            }
        }
    }
}
