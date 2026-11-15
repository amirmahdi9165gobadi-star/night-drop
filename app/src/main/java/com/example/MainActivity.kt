package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.automation.SettingsNavigator
import com.example.data.local.MacroPhase
import com.example.ui.ExecutionLogsScreen
import com.example.ui.GestureRecorderSheet
import com.example.ui.MacrosAndSettingsScreen
import com.example.ui.NightDropScreen
import com.example.ui.NightDropViewModel
import com.example.ui.theme.NightDropTheme

private const val ROUTE_SCHEDULER = "scheduler"
private const val ROUTE_MACROS = "macros"
private const val ROUTE_LOGS = "logs"

private data class NavDestinationItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

class MainActivity : ComponentActivity() {

    private val viewModel: NightDropViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            NightDropTheme(darkTheme = true) {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val lifecycleOwner = LocalLifecycleOwner.current
                val navController = rememberNavController()
                val snackbarHostState = remember { SnackbarHostState() }
                val backStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = backStackEntry?.destination?.route ?: ROUTE_SCHEDULER

                val destinations = remember {
                    listOf(
                        NavDestinationItem(ROUTE_SCHEDULER, "زمان‌بندی", Icons.Default.Schedule, "nav_scheduler"),
                        NavDestinationItem(ROUTE_MACROS, "ماکرو و تنظیمات", Icons.Default.Tune, "nav_macros"),
                        NavDestinationItem(ROUTE_LOGS, "گزارش‌ها", Icons.Default.History, "nav_logs")
                    )
                }

                val notificationLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { viewModel.refreshPermissions() }
                )

                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            viewModel.refreshPermissions()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                LaunchedEffect(uiState.snackbarMessage) {
                    val msg = uiState.snackbarMessage
                    if (msg != null) {
                        snackbarHostState.showSnackbar(msg)
                        viewModel.consumeSnackbar()
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val useRail = maxWidth >= 680.dp

                        Scaffold(
                            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                            containerColor = MaterialTheme.colorScheme.background,
                            bottomBar = {
                                if (!useRail) {
                                    NavigationBar(
                                        windowInsets = WindowInsets.navigationBars,
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ) {
                                        destinations.forEach { dest ->
                                            NavigationBarItem(
                                                selected = currentRoute == dest.route,
                                                onClick = {
                                                    navController.navigate(dest.route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                },
                                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                                label = { Text(dest.label) },
                                                modifier = Modifier.testTag(dest.testTag)
                                            )
                                        }
                                    }
                                }
                            }
                        ) { innerPadding ->
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                if (useRail) {
                                    NavigationRail(
                                        containerColor = MaterialTheme.colorScheme.surface
                                    ) {
                                        destinations.forEach { dest ->
                                            NavigationRailItem(
                                                selected = currentRoute == dest.route,
                                                onClick = {
                                                    navController.navigate(dest.route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                },
                                                icon = { Icon(dest.icon, contentDescription = dest.label) },
                                                label = { Text(dest.label) },
                                                modifier = Modifier.testTag(dest.testTag)
                                            )
                                        }
                                    }
                                }

                                NavHost(
                                    navController = navController,
                                    startDestination = ROUTE_SCHEDULER,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    composable(ROUTE_SCHEDULER) {
                                        NightDropScreen(
                                            state = uiState,
                                            onUrlChange = viewModel::onUrlChanged,
                                            onFileNameChange = viewModel::onFileNameChanged,
                                            onAllowMeteredChange = viewModel::onAllowMeteredChanged,
                                            onStartTimeSelected = viewModel::onStartTimeSelected,
                                            onCutoffTimeSelected = viewModel::onCutoffTimeSelected,
                                            onCutoffEnabledChange = viewModel::onCutoffEnabledChanged,
                                            onAddToQueue = viewModel::addUrlToQueue,
                                            onOpenAccessibilitySettings = { SettingsNavigator.openAccessibilitySettings(this@MainActivity) },
                                            onOpenOverlaySettings = { SettingsNavigator.openOverlaySettings(this@MainActivity) },
                                            onOpenExactAlarmSettings = { SettingsNavigator.openExactAlarmSettings(this@MainActivity) },
                                            onOpenBatterySettings = { SettingsNavigator.openBatteryOptimizationSettings(this@MainActivity) },
                                            onOpenAutostartSettings = { SettingsNavigator.openXiaomiAutostartSettings(this@MainActivity) },
                                            onRequestNotifications = {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                                } else {
                                                    SettingsNavigator.openAppNotificationSettings(this@MainActivity)
                                                }
                                            },
                                            onOpenDownloadsFolder = { SettingsNavigator.openDownloadsFolder(this@MainActivity) },
                                            onStartFloatingRecorder = { phase ->
                                                if (viewModel.startFloatingRecorder(phase)) {
                                                    moveTaskToBack(true)
                                                }
                                            },
                                            onStartVisualMacroTest = { phase ->
                                                if (viewModel.startVisualMacroTest(phase)) {
                                                    moveTaskToBack(true)
                                                }
                                            },
                                            onOpenStepEditor = viewModel::openRecorder,
                                            onUpdateStepDelay = viewModel::updateStepDelay,
                                            onMoveStep = viewModel::moveStep,
                                            onDeleteStep = viewModel::deleteStep,
                                            onOpenFullLogs = {
                                                navController.navigate(ROUTE_LOGS) {
                                                    popUpTo(navController.graph.findStartDestination().id) {
                                                        saveState = true
                                                    }
                                                    launchSingleTop = true
                                                    restoreState = true
                                                }
                                            },
                                            onClearLogs = viewModel::clearLogs,
                                            onScheduleQueue = viewModel::scheduleSequentialQueue,
                                            onImmediateQueueRun = viewModel::triggerImmediateQueueRun,
                                            onRetryTask = viewModel::retryTask,
                                            onCancelOrDeleteTask = viewModel::cancelOrDeleteTask,
                                            onClearFinishedTasks = viewModel::clearFinishedTasks
                                        )
                                    }
                                    composable(ROUTE_MACROS) {
                                        MacrosAndSettingsScreen(
                                            state = uiState,
                                            onOpenRecorder = viewModel::openRecorder,
                                            onApplyPrePreset = viewModel::applyPreDownloadPreset,
                                            onApplyPostPreset = viewModel::applyPostDownloadPreset,
                                            onMoveStep = viewModel::moveStep,
                                            onDeleteStep = viewModel::deleteStep,
                                            onNetworkWaitChange = viewModel::setNetworkStabilizationSeconds,
                                            onWakeOnAlarmChange = viewModel::setWakeScreenOnAlarm,
                                            onWakeOnCompleteChange = viewModel::setWakeOnDownloadComplete,
                                            onVibrateOnCompleteChange = viewModel::setVibrateOnComplete,
                                            onSaveGeminiApiKey = viewModel::saveGeminiApiKey,
                                            onCheckForUpdate = viewModel::checkForAppUpdate,
                                            onOpenUpdateRelease = viewModel::openUpdateReleasePage,
                                            onRunSelfOptimizationNow = viewModel::runSelfOptimizationNow,
                                            onResetStrategyScores = viewModel::resetStrategyScores
                                        )
                                    }
                                    composable(ROUTE_LOGS) {
                                        ExecutionLogsScreen(
                                            state = uiState,
                                            onClearLogs = viewModel::clearLogs
                                        )
                                    }
                                }
                            }
                        }

                        val activePhase = uiState.activeRecorderPhase
                        if (activePhase != null) {
                            val phaseSteps = if (activePhase == MacroPhase.PRE_DOWNLOAD) {
                                uiState.preSteps
                            } else {
                                uiState.postSteps
                            }
                            GestureRecorderSheet(
                                phase = activePhase,
                                steps = phaseSteps,
                                defaultDelayMs = uiState.defaultDelayMs,
                                isDryRunning = uiState.isDryRunning,
                                dryRunActiveIndex = uiState.dryRunActiveStepIndex,
                                onDelayChanged = viewModel::onDefaultDelayChanged,
                                onRecordTap = { nx, ny, w, h ->
                                    viewModel.recordTapStep(activePhase, nx, ny, w, h)
                                },
                                onRecordSwipe = { sx, sy, ex, ey, dur, w, h ->
                                    viewModel.recordSwipeStep(activePhase, sx, sy, ex, ey, dur, w, h)
                                },
                                onAddQuickStep = { type, label, delay ->
                                    viewModel.addQuickSystemStep(activePhase, type, label, delay)
                                },
                                onUpdateStepDelay = viewModel::updateStepDelay,
                                onDeleteStep = viewModel::deleteStep,
                                onClearPhase = { viewModel.clearPhase(activePhase) },
                                onRunDryPlayback = { viewModel.runDryPlayback(activePhase) },
                                onStopDryPlayback = viewModel::stopDryRun,
                                onDismiss = viewModel::closeRecorder
                            )
                        }
                    }
                }
            }
        }
    }
}
