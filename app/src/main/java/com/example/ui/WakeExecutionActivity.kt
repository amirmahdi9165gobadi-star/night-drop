package com.example.ui

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.automation.ExecutionLiveState
import com.example.automation.NightExecutionBus
import com.example.automation.SettingsNavigator
import com.example.data.local.GestureActionType
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldTertiary
import com.example.ui.theme.IndigoSecondary
import com.example.ui.theme.MidnightBg
import com.example.ui.theme.NightDropTheme

class WakeExecutionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockScreenWake()

        setContent {
            NightDropTheme(darkTheme = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    val liveState by NightExecutionBus.state.collectAsStateWithLifecycle()
                    androidx.compose.runtime.LaunchedEffect(liveState.yieldToSystem) {
                        if (liveState.yieldToSystem) {
                            finish()
                        }
                    }
                    WakeExecutionScreen(
                        liveState = liveState,
                        onOpenConnectivity = { SettingsNavigator.openConnectivityPanel(this) },
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun configureLockScreenWake() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            if (keyguardManager.isKeyguardLocked) {
                keyguardManager.requestDismissKeyguard(this, null)
            }
        }
    }
}

@Composable
private fun WakeExecutionScreen(
    liveState: ExecutionLiveState,
    onOpenConnectivity: () -> Unit,
    onDismiss: () -> Unit
) {
    val activeStep = liveState.steps.getOrNull(liveState.activeStepIndex)
    val pulseProgress by animateFloatAsState(
        targetValue = if (liveState.activeStepIndex >= 0) 1f else 0f,
        animationSpec = tween(durationMillis = 450),
        label = "pulse_anim"
    )

    Scaffold(
        containerColor = MidnightBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF0B132B), MidnightBg, Color(0xFF040811))
                    )
                )
                .padding(innerPadding)
                .padding(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(CyanPrimary.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (liveState.isFinished) Icons.Default.CheckCircle else Icons.Default.Bolt,
                                contentDescription = null,
                                tint = if (liveState.isFinished) EmeraldTertiary else CyanPrimary
                            )
                        }
                        Column {
                            Text(
                                text = liveState.phaseLabel.ifBlank { "اجرای خودکار شبانه" },
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = liveState.fileName.ifBlank { "NightDrop Wake HUD" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = liveState.statusDetail.ifBlank { "آماده اجرای توالی درون‌برنامه‌ای..." },
                            style = MaterialTheme.typography.titleMedium,
                            color = CyanPrimary
                        )

                        if (liveState.countdownSeconds > 0) {
                            Text(
                                text = "00:0${liveState.countdownSeconds}",
                                style = MaterialTheme.typography.displaySmall,
                                color = EmeraldTertiary
                            )
                        }

                        // Live visual replay canvas inside app bounds
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color(0xFF060B16))
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        ) {
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                liveState.steps.forEachIndexed { idx, step ->
                                    val isCurrent = idx == liveState.activeStepIndex
                                    val start = Offset(step.normStartX * size.width, step.normStartY * size.height)
                                    val end = Offset(step.normEndX * size.width, step.normEndY * size.height)
                                    val stepColor = when {
                                        isCurrent -> CyanPrimary
                                        idx < liveState.activeStepIndex -> EmeraldTertiary.copy(alpha = 0.7f)
                                        else -> IndigoSecondary.copy(alpha = 0.45f)
                                    }

                                    when (step.actionType) {
                                        GestureActionType.TAP.name -> {
                                            drawCircle(
                                                color = stepColor.copy(alpha = if (isCurrent) 0.25f else 0.12f),
                                                radius = if (isCurrent) 34f * (0.7f + 0.3f * pulseProgress) else 20f,
                                                center = start
                                            )
                                            drawCircle(
                                                color = stepColor,
                                                radius = if (isCurrent) 12f else 8f,
                                                center = start
                                            )
                                        }
                                        GestureActionType.SWIPE.name -> {
                                            drawLine(
                                                color = stepColor,
                                                start = start,
                                                end = end,
                                                strokeWidth = if (isCurrent) 8f else 4f,
                                                cap = StrokeCap.Round
                                            )
                                            drawCircle(color = stepColor, radius = 9f, center = start)
                                            drawCircle(color = EmeraldTertiary, radius = 9f, center = end)
                                        }
                                    }
                                }
                            }

                            if (activeStep != null) {
                                Text(
                                    text = "${activeStep.actionType} (${activeStep.pixelStartX}, ${activeStep.pixelStartY})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(10.dp)
                                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onOpenConnectivity,
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("wake_connectivity_button")
                    ) {
                        Icon(Icons.Default.Wifi, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("پنل اینترنت")
                    }

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = CyanPrimary),
                        modifier = Modifier
                            .weight(1f)
                            .height(50.dp)
                            .testTag("wake_dismiss_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("بستن صفحه", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
