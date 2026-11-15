package com.example.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.example.data.local.MacroPhase
import com.example.data.local.MacroStepEntity
import com.example.data.local.StrategyEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.IndigoSecondary
import kotlin.math.roundToInt

@Composable
fun MacrosAndSettingsScreen(
    state: NightDropUiState,
    onOpenRecorder: (MacroPhase) -> Unit,
    onApplyPrePreset: () -> Unit,
    onApplyPostPreset: () -> Unit,
    onMoveStep: (MacroPhase, Long, Int) -> Unit,
    onDeleteStep: (Long) -> Unit,
    onNetworkWaitChange: (Int) -> Unit,
    onWakeOnAlarmChange: (Boolean) -> Unit,
    onWakeOnCompleteChange: (Boolean) -> Unit,
    onVibrateOnCompleteChange: (Boolean) -> Unit,
    onSaveGeminiApiKey: (String) -> Unit,
    onCheckForUpdate: (String) -> Unit,
    onOpenUpdateRelease: () -> Unit,
    onRunSelfOptimizationNow: () -> Unit,
    onResetStrategyScores: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = CyanPrimary)
                    Text(
                        text = "مدیریت توالی‌ها و تنظیمات اجرا",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            // DataStore Scheduler Settings
            item {
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
                            text = "تنظیمات سرویس بیدارسازی و شبکه",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "زمان انتظار تثبیت شبکه قبل از دانلود",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = "${state.settings.networkStabilizationSeconds} ثانیه",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = CyanPrimary
                                )
                            }
                            Slider(
                                value = state.settings.networkStabilizationSeconds.toFloat(),
                                onValueChange = { onNetworkWaitChange(it.roundToInt()) },
                                valueRange = 3f..20f,
                                modifier = Modifier.testTag("settings_network_wait_slider")
                            )
                            val sampleCount = state.settings.adaptiveTimingSamplesMs.size
                            val learnedSecText = String.format(
                                java.util.Locale.US,
                                "%.1f",
                                state.settings.learnedNetworkDelayMs / 1000.0
                            )
                            Text(
                                text = if (sampleCount > 0) {
                                    "حافظه تطبیقی سیم‌کارت (میانگین $sampleCount شب اخیر): $learnedSecText ثانیه"
                                } else {
                                    "حافظه تطبیقی سیم‌کارت: هنوز نمونه‌ای از شب‌های قبل ثبت نشده (استفاده از مقدار پیش‌فرض)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("adaptive_timing_summary_text")
                            )
                        }

                        SettingToggleRow(
                            title = "روشن کردن صفحه هنگام آلارم شبانه",
                            checked = state.settings.wakeScreenOnAlarm,
                            testTag = "settings_wake_alarm_switch",
                            onCheckedChange = onWakeOnAlarmChange
                        )
                        SettingToggleRow(
                            title = "بیدارسازی صفحه هنگام اتمام دانلود",
                            checked = state.settings.wakeOnDownloadComplete,
                            testTag = "settings_wake_complete_switch",
                            onCheckedChange = onWakeOnCompleteChange
                        )
                        SettingToggleRow(
                            title = "لرزش کوتاه پس از پایان دانلود",
                            checked = state.settings.vibrateOnComplete,
                            testTag = "settings_vibrate_switch",
                            onCheckedChange = onVibrateOnCompleteChange
                        )
                    }
                }
            }

            // Self-Evolving AI Cloud Troubleshooter (Gemini API Key)
            item {
                GeminiCloudSettingsCard(
                    savedApiKey = state.settings.geminiApiKey,
                    onSaveApiKey = onSaveGeminiApiKey
                )
            }

            // Post-Operation Self-Optimizer & Multi-Strategy Fallback Ranker
            item {
                SelfEvolvingEngineCard(
                    state = state,
                    onRunOptimizer = onRunSelfOptimizationNow,
                    onResetStrategies = onResetStrategyScores
                )
            }

            // OTA Version Checker (GitHub Releases / JSON Manifest)
            item {
                OtaVersionCheckerCard(
                    state = state,
                    onCheckUpdate = onCheckForUpdate,
                    onOpenReleasePage = onOpenUpdateRelease
                )
            }

            // Pre-download sequence manager
            item {
                SequenceSectionHeader(
                    title = "توالی قبل از دانلود (${state.preSteps.size} گام)",
                    accent = CyanPrimary,
                    onRecordClick = { onOpenRecorder(MacroPhase.PRE_DOWNLOAD) },
                    onPresetClick = onApplyPrePreset
                )
            }

            if (state.preSteps.isEmpty()) {
                item {
                    EmptyStepsHint("گامی برای قبل از دانلود ثبت نشده؛ می‌توانید از دکمه قالب آماده یا ضبط استفاده کنید.")
                }
            } else {
                itemsIndexed(state.preSteps, key = { _, item -> "pre_${item.id}" }) { idx, step ->
                    ReorderableStepRow(
                        index = idx,
                        total = state.preSteps.size,
                        step = step,
                        onMoveUp = { onMoveStep(MacroPhase.PRE_DOWNLOAD, step.id, -1) },
                        onMoveDown = { onMoveStep(MacroPhase.PRE_DOWNLOAD, step.id, 1) },
                        onDelete = { onDeleteStep(step.id) }
                    )
                }
            }

            // Post-download sequence manager
            item {
                Spacer(Modifier.height(4.dp))
                SequenceSectionHeader(
                    title = "توالی بعد از دانلود (${state.postSteps.size} گام)",
                    accent = IndigoSecondary,
                    onRecordClick = { onOpenRecorder(MacroPhase.POST_DOWNLOAD) },
                    onPresetClick = onApplyPostPreset
                )
            }

            if (state.postSteps.isEmpty()) {
                item {
                    EmptyStepsHint("گامی برای بعد از دانلود ثبت نشده؛ از دکمه قالب آماده یا ضبط استفاده کنید.")
                }
            } else {
                itemsIndexed(state.postSteps, key = { _, item -> "post_${item.id}" }) { idx, step ->
                    ReorderableStepRow(
                        index = idx,
                        total = state.postSteps.size,
                        step = step,
                        onMoveUp = { onMoveStep(MacroPhase.POST_DOWNLOAD, step.id, -1) },
                        onMoveDown = { onMoveStep(MacroPhase.POST_DOWNLOAD, step.id, 1) },
                        onDelete = { onDeleteStep(step.id) }
                    )
                }
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.testTag(testTag)
        )
    }
}

@Composable
private fun SequenceSectionHeader(
    title: String,
    accent: androidx.compose.ui.graphics.Color,
    onRecordClick: () -> Unit,
    onPresetClick: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = accent,
            fontWeight = FontWeight.Bold
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onRecordClick,
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("باز کردن ضبط‌کننده")
            }
            OutlinedButton(
                onClick = onPresetClick,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Icon(Icons.Default.AutoFixHigh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("قالب پیشنهادی")
            }
        }
    }
}

@Composable
private fun EmptyStepsHint(text: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(14.dp)
        )
    }
}

@Composable
private fun ReorderableStepRow(
    index: Int,
    total: Int,
    step: MacroStepEntity,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${index + 1}. ${step.label}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                val nodeHint = step.primaryNodeLabel()?.let { "نود: «$it» • " } ?: ""
                Text(
                    text = "$nodeHint${step.actionType} • تاخیر بعد: ${step.delayAfterMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row {
                IconButton(onClick = onMoveUp, enabled = index > 0) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = "انتقال به بالا", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onMoveDown, enabled = index < total - 1) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = "انتقال به پایین", modifier = Modifier.size(18.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "حذف",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GeminiCloudSettingsCard(
    savedApiKey: String,
    onSaveApiKey: (String) -> Unit
) {
    var draftKey by remember(savedApiKey) { mutableStateOf(savedApiKey) }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.CloudQueue, contentDescription = null, tint = CyanPrimary)
                Text(
                    text = "مغز پشتیبان چندمرحله‌ای AI (Gemini ← DeepSeek)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "در صورت بن‌بست استراتژی‌های آفلاین، ابتدا نقشه صفحه به Gemini API (با تایم‌اوت ۸ ثانیه) ارسال می‌شود و در صورت خطا یا قطع ارتباط، به‌طور خودکار از اپلیکیشن DeepSeek نصب‌شده روی گوشی راه‌حل استخراج و اجرا می‌گردد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = draftKey,
                    onValueChange = { draftKey = it },
                    label = { Text("Gemini API Key (AIza...)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("gemini_api_key_input")
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (savedApiKey.isNotBlank()) "وضعیت: کلید ذخیره شده" else "وضعیت: تنظیم نشده",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (savedApiKey.isNotBlank()) EmeraldTertiary else AmberWarning
                )
                Button(
                    onClick = { onSaveApiKey(draftKey) },
                    modifier = Modifier.testTag("save_gemini_key_btn")
                ) {
                    Text("ذخیره کلید API")
                }
            }
        }
    }
}

@Composable
private fun SelfEvolvingEngineCard(
    state: NightDropUiState,
    onRunOptimizer: () -> Unit,
    onResetStrategies: () -> Unit
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.Psychology, contentDescription = null, tint = IndigoSecondary)
                Text(
                    text = "خوداصلاحگری و رتبه‌بندی استراتژی‌ها",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "آستانه‌های خودتنظیم فعلی: پایش هر ${state.settings.adaptiveProbeIntervalSeconds} ثانیه | تایماوت ${state.settings.adaptivePingTimeoutSeconds} ثانیه",
                style = MaterialTheme.typography.labelMedium,
                color = CyanPrimary
            )
            if (state.settings.lastOptimizationSummary.isNotBlank()) {
                Text(
                    text = state.settings.lastOptimizationSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.strategies.isNotEmpty()) {
                state.strategies.forEach { strategy ->
                    StrategyRankingRow(strategy = strategy)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onRunOptimizer,
                    enabled = !state.isOptimizing,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("run_self_optimizer_btn")
                ) {
                    Text(if (state.isOptimizing) "در حال تحلیل..." else "تحلیل و بهینه‌سازی اکنون")
                }
                OutlinedButton(
                    onClick = onResetStrategies,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("reset_strategies_btn")
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("ریست امتیازها")
                }
            }
        }
    }
}

@Composable
private fun StrategyRankingRow(strategy: StrategyEntity) {
    val scoreColor = when {
        strategy.successScore >= 90 -> EmeraldTertiary
        strategy.successScore >= 60 -> AmberWarning
        else -> MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = strategy.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "هدف: ${strategy.goal} • موفق: ${strategy.successCount} | شکست: ${strategy.failureCount}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "${strategy.successScore} امتیاز",
            style = MaterialTheme.typography.labelLarge,
            color = scoreColor,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun OtaVersionCheckerCard(
    state: NightDropUiState,
    onCheckUpdate: (String) -> Unit,
    onOpenReleasePage: () -> Unit
) {
    var draftUrl by remember(state.settings.otaManifestUrl) {
        mutableStateOf(state.settings.otaManifestUrl)
    }

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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = EmeraldTertiary)
                Text(
                    text = "بررسی آپدیت خودکار نسخه (OTA)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = draftUrl,
                    onValueChange = { draftUrl = it },
                    label = { Text("GitHub Releases / JSON URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ota_url_input")
                )
            }
            Button(
                onClick = { onCheckUpdate(draftUrl) },
                enabled = !state.isCheckingUpdate,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("check_update_btn")
            ) {
                if (state.isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("در حال بررسی نسخه جدید...")
                } else {
                    Text("بررسی انتشار نسخه جدید")
                }
            }

            val update = state.updateCheckResult
            if (update != null) {
                if (update.succeeded && update.hasUpdate) {
                    Text(
                        text = "نسخه جدید ${update.latestVersionName} آماده دریافت است (نسخه فعلی: ${update.currentVersionName})",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldTertiary,
                        fontWeight = FontWeight.Bold
                    )
                    if (update.releaseNotes.isNotBlank()) {
                        Text(
                            text = update.releaseNotes,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenReleasePage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("open_release_url_btn")
                    ) {
                        Text("باز کردن صفحه دانلود نسخه جدید در مرورگر")
                    }
                } else if (update.succeeded) {
                    Text(
                        text = "نسخه نصب‌شده (${update.currentVersionName}) آخرین نسخه منتشر شده است.",
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanPrimary
                    )
                } else if (!update.errorMessage.isNullOrBlank()) {
                    Text(
                        text = update.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
