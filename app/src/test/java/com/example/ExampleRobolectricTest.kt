package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("NightDrop", appName)
  }

  @Test
  fun `download url validation works`() {
    org.junit.Assert.assertTrue(
      com.example.automation.DownloadEnqueuer.isValidDownloadUrl("https://example.com/archive.zip")
    )
    org.junit.Assert.assertFalse(
      com.example.automation.DownloadEnqueuer.isValidDownloadUrl("ftp://example.com/archive.zip")
    )
  }

  @Test
  fun `cutoff time rolls over to next morning when start is before midnight`() {
    val startCal = java.util.Calendar.getInstance().apply {
      set(2026, 10, 14, 23, 30, 0)
      set(java.util.Calendar.MILLISECOND, 0)
    }
    val cutoffMillis = com.example.automation.NightAlarmScheduler.computeCutoffTriggerAfter(
      startTriggerMillis = startCal.timeInMillis,
      cutoffHour = 6,
      cutoffMinute = 55
    )
    val cutoffCal = java.util.Calendar.getInstance().apply { timeInMillis = cutoffMillis }
    assertEquals(15, cutoffCal.get(java.util.Calendar.DAY_OF_MONTH))
    assertEquals(6, cutoffCal.get(java.util.Calendar.HOUR_OF_DAY))
    assertEquals(55, cutoffCal.get(java.util.Calendar.MINUTE))
  }

  @Test
  fun `connection monitor retries pre-macro when round 1 times out`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var macroRetried = false
    val monitor = com.example.automation.ConnectionMonitor(
      context = context,
      windowMillis = 350L,
      probeIntervalMillis = 80L,
      internetProbe = {
        com.example.automation.ConnectivityCheckResult(
          hasValidatedCapability = macroRetried,
          httpPingSucceeded = macroRetried,
          httpStatusCode = if (macroRetried) 204 else -1
        )
      }
    )

    val outcome = monitor.awaitInternetWithMacroRetry(
      onRetryPreMacro = { macroRetried = true }
    )

    org.junit.Assert.assertTrue(outcome.isConnected)
    org.junit.Assert.assertTrue(outcome.retriedPreMacro)
    assertEquals(2, outcome.roundReached)
    assertEquals(204, outcome.lastResult?.httpStatusCode)
  }

  @Test
  fun `connection monitor succeeds in round 1 without retrying pre-macro`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var macroRetried = false
    val monitor = com.example.automation.ConnectionMonitor(
      context = context,
      windowMillis = 2_000L,
      probeIntervalMillis = 100L,
      internetProbe = {
        com.example.automation.ConnectivityCheckResult(
          hasValidatedCapability = true,
          httpPingSucceeded = true,
          httpStatusCode = 204
        )
      }
    )

    val outcome = monitor.awaitInternetWithMacroRetry(
      onRetryPreMacro = { macroRetried = true }
    )

    org.junit.Assert.assertTrue(outcome.isConnected)
    org.junit.Assert.assertFalse(outcome.retriedPreMacro)
    org.junit.Assert.assertFalse(macroRetried)
    assertEquals(1, outcome.roundReached)
    assertEquals(204, outcome.lastResult?.httpStatusCode)
  }

  @Test
  fun `hybrid macro repository stores ui node metadata enforces min delay and supports undo`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val repo = com.example.data.local.MacroRepository(
        db.macroDao(),
        db.downloadTaskDao(),
        db.executionLogDao()
      )
      val step1Id = repo.addStep(
        phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD,
        actionType = com.example.data.local.GestureActionType.TAP,
        pixelStartX = 320,
        pixelStartY = 640,
        pixelEndX = 320,
        pixelEndY = 640,
        delayAfterMs = 200L, // should clamp to 500L min
        label = "کلیک «Mobile data»",
        nodeText = "Mobile data",
        contentDescription = "Mobile data tile"
      )
      repo.addStep(
        phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD,
        actionType = com.example.data.local.GestureActionType.TAP,
        pixelStartX = 500,
        pixelStartY = 900,
        delayAfterMs = 900L,
        label = "اشتباه لمس"
      )
      repo.updateStepDelayById(step1Id, 1_450L)

      val afterUndo = repo.undoLastStep(com.example.data.local.MacroPhase.PRE_DOWNLOAD)
      val remaining = repo.getSteps(com.example.data.local.MacroPhase.PRE_DOWNLOAD)

      assertEquals(1, remaining.size)
      assertEquals(step1Id, afterUndo?.id)
      val domain = remaining.first().toMacroStep()
      assertEquals("Mobile data", domain.nodeText)
      assertEquals("Mobile data tile", domain.contentDescription)
      assertEquals(1_450L, domain.delayBeforeNextMs)
      org.junit.Assert.assertTrue(domain.hasNodeSelector)
    } finally {
      db.close()
    }
  }

  @Test
  fun `brain engine precheck aborts low battery and skips pre-macro when online`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    val lowBatteryEngine = com.example.automation.BrainEngine(
      context = context,
      batteryProvider = { com.example.automation.BatteryStatus(levelPercent = 12, isCharging = false) },
      networkOnlineProvider = { false }
    )
    val lowBatteryDecision = lowBatteryEngine.evaluatePreChecks()
    org.junit.Assert.assertFalse(lowBatteryDecision.shouldProceed)
    org.junit.Assert.assertTrue(
      lowBatteryDecision.abortReason?.contains("به دلیل کمبود باتری عملیات انجام نشد") == true
    )

    val onlineEngine = com.example.automation.BrainEngine(
      context = context,
      batteryProvider = { com.example.automation.BatteryStatus(levelPercent = 12, isCharging = true) },
      networkOnlineProvider = { true }
    )
    val onlineDecision = onlineEngine.evaluatePreChecks()
    org.junit.Assert.assertTrue(onlineDecision.shouldProceed)
    org.junit.Assert.assertTrue(onlineDecision.skipPreMacroBecauseOnline)
    assertEquals(com.example.automation.BrainState.VERIFYING_NETWORK, onlineEngine.snapshot.value.state)
  }

  @Test
  fun `brain engine self healing recovers from blocking popup and retries`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = com.example.automation.BrainEngine(
      context = context,
      selfHealWaitMs = 20L
    )
    val steps = listOf(
      com.example.data.local.MacroStepEntity(
        id = 1L,
        phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD.name,
        orderIndex = 0,
        actionType = com.example.data.local.GestureActionType.TAP.name,
        normStartX = 0.5f,
        normStartY = 0.5f,
        normEndX = 0.5f,
        normEndY = 0.5f,
        pixelStartX = 500,
        pixelStartY = 500,
        pixelEndX = 500,
        pixelEndY = 500,
        durationMs = 90L,
        delayAfterMs = 20L,
        label = "کلیک «Mobile data»",
        nodeText = "Mobile data"
      )
    )

    var healCount = 0
    var attempts = 0
    val ok = engine.executeMacroWithSelfHealing(
      phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD,
      steps = steps,
      maxAttempts = 3,
      stepExecutor = { _, _ ->
        attempts++
        if (attempts == 1) {
          com.example.automation.MacroStepExecutionResult(
            succeeded = true,
            usedSmartNodeClick = false,
            nodeExpectedButMissing = true,
            blockingPopupDetected = true
          )
        } else {
          com.example.automation.MacroStepExecutionResult(
            succeeded = true,
            usedSmartNodeClick = true,
            matchedNodeLabel = "Mobile data"
          )
        }
      },
      selfHealNavigator = { healCount++ },
      onLog = { _, _, _ -> }
    )

    org.junit.Assert.assertTrue(ok)
    assertEquals(1, healCount)
    assertEquals(2, attempts)
  }

  @Test
  fun `brain engine smart queue prioritizer pauses large slow download for smaller task`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val now = 1_000_000L
    val engine = com.example.automation.BrainEngine(
      context = context,
      minSpeedWarmupMillis = 5_000L,
      clockMillis = { now }
    )

    val heavyTask = com.example.data.local.DownloadTaskEntity(
      id = 1L,
      url = "https://example.com/huge.iso",
      fileName = "huge.iso",
      scheduledHour = 2,
      scheduledMinute = 0,
      scheduledTriggerAtMillis = now,
      status = com.example.data.local.TaskStatus.DOWNLOADING.name,
      bytesDownloaded = 100_000L,
      totalBytes = 100_000_000L,
      queueOrder = 0
    )
    val smallTask = com.example.data.local.DownloadTaskEntity(
      id = 2L,
      url = "https://example.com/patch.zip",
      fileName = "patch.zip",
      scheduledHour = 2,
      scheduledMinute = 0,
      scheduledTriggerAtMillis = now,
      status = com.example.data.local.TaskStatus.QUEUED.name,
      totalBytes = 500_000L,
      queueOrder = 1
    )

    // 100KB downloaded in 10s = 10KB/s -> ~10,000s ETA vs 60s left to cutoff
    val decision = engine.evaluateDownloadFeasibility(
      activeTask = heavyTask,
      bytesDownloaded = 100_000L,
      totalBytes = 100_000_000L,
      elapsedDownloadMillis = 10_000L,
      cutoffTriggerAtMillis = now + 60_000L,
      queuedCandidates = listOf(smallTask)
    )

    org.junit.Assert.assertTrue(decision is com.example.automation.DownloadPriorityDecision.PauseAndSwitchToSmaller)
    val switchDecision = decision as com.example.automation.DownloadPriorityDecision.PauseAndSwitchToSmaller
    assertEquals(2L, switchDecision.nextTask.id)
  }

  @Test
  fun `brain engine double check offline protocol retries disconnect before running power macro`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var networkChecks = 0
    val engine = com.example.automation.BrainEngine(
      context = context,
      offlineVerifyDelayMs = 20L,
      networkOnlineProvider = {
        networkChecks++
        // Still online on first 3s check, offline after retry
        networkChecks == 1
      }
    )

    var disconnectRuns = 0
    var powerRuns = 0
    val disconnectStep = com.example.data.local.MacroStepEntity(
      id = 10L,
      phase = com.example.data.local.MacroPhase.POST_DOWNLOAD.name,
      orderIndex = 0,
      actionType = com.example.data.local.GestureActionType.TAP.name,
      normStartX = 0.5f,
      normStartY = 0.5f,
      normEndX = 0.5f,
      normEndY = 0.5f,
      pixelStartX = 400,
      pixelStartY = 400,
      pixelEndX = 400,
      pixelEndY = 400,
      durationMs = 80L,
      delayAfterMs = 20L,
      label = "قطع دیتا"
    )
    val powerStep = disconnectStep.copy(
      id = 11L,
      orderIndex = 1,
      actionType = com.example.data.local.GestureActionType.GLOBAL_POWER_DIALOG.name,
      label = "منوی پاور"
    )

    val (dc, pw) = com.example.automation.BrainEngine.splitPostMacroSteps(listOf(disconnectStep, powerStep))
    val outcome = engine.executeDoubleCheckOfflineProtocol(
      disconnectSteps = dc,
      powerSteps = pw,
      runMacro = { _, macroSteps ->
        if (macroSteps.any { it.actionType == com.example.data.local.GestureActionType.GLOBAL_POWER_DIALOG.name }) {
          powerRuns++
        } else {
          disconnectRuns++
        }
        true
      },
      onLog = { _, _, _ -> }
    )

    org.junit.Assert.assertTrue(outcome.confirmedOffline)
    assertEquals(2, disconnectRuns)
    assertEquals(1, powerRuns)
  }

  @Test
  fun `brain engine storage preflight enforces 500MB safety margin`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = com.example.automation.BrainEngine(context)
    val mb = 1024L * 1024L

    val rejected = engine.evaluateStoragePreFlight(
      fileName = "ubuntu.iso",
      contentLengthBytes = 1000L * mb,
      availableBytes = 1200L * mb // 1000 + 500 safety margin = 1500 MB required
    )
    org.junit.Assert.assertFalse(rejected.allowed)
    org.junit.Assert.assertTrue(
      rejected.reason?.contains("به دلیل کمبود فضای حافظه رد شد") == true
    )

    val accepted = engine.evaluateStoragePreFlight(
      fileName = "ubuntu.iso",
      contentLengthBytes = 1000L * mb,
      availableBytes = 1600L * mb
    )
    org.junit.Assert.assertTrue(accepted.allowed)
  }

  @Test
  fun `brain engine airplane mode reset toggles airplane on and off and restores network`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val engine = com.example.automation.BrainEngine(
      context = context,
      airplaneResetWaitMs = 20L,
      airplanePanelSettlingMs = 10L
    )

    var quickSettingsOpened = 0
    var airplaneToggles = 0
    var panelClosed = 0
    var netChecks = 0

    val ok = engine.performAirplaneModeReset(
      openQuickSettings = {
        quickSettingsOpened++
        true
      },
      toggleAirplaneNode = {
        airplaneToggles++
        true
      },
      closePanel = { panelClosed++ },
      awaitNetworkBack = {
        netChecks++
        true
      },
      onLog = { _, _, _ -> }
    )

    org.junit.Assert.assertTrue(ok)
    assertEquals(2, quickSettingsOpened)
    assertEquals(2, airplaneToggles)
    assertEquals(1, panelClosed)
    assertEquals(1, netChecks)
    assertEquals(com.example.automation.BrainState.DOWNLOADING, engine.snapshot.value.state)
  }

  @Test
  fun `zip auto extractor unpacks verifies integrity and deletes source zip`() {
    val tempRoot = java.nio.file.Files.createTempDirectory("nightdrop_zip_test").toFile()
    try {
      val zipFile = java.io.File(tempRoot, "bundle.zip")
      val payload = "NightDrop Auto-Unzip Integrity Payload!".toByteArray()
      java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("nested/readme.txt"))
        zos.write(payload)
        zos.closeEntry()
      }

      val targetDir = java.io.File(tempRoot, "NightDrop_Extracted/bundle")
      val outcome = com.example.automation.ZipAutoExtractor.extractZipFileAndVerify(zipFile, targetDir)

      org.junit.Assert.assertTrue(outcome.wasZipFile)
      org.junit.Assert.assertTrue(outcome.extractedSuccess)
      assertEquals(1, outcome.extractedFileCount)
      org.junit.Assert.assertTrue(outcome.deletedSourceArchive)
      org.junit.Assert.assertFalse(zipFile.exists())

      val extracted = java.io.File(targetDir, "nested/readme.txt")
      org.junit.Assert.assertTrue(extracted.exists())
      assertEquals("NightDrop Auto-Unzip Integrity Payload!", extracted.readText())
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `zip auto extractor blocks zip slip traversal`() {
    val tempRoot = java.nio.file.Files.createTempDirectory("nightdrop_zipslip_test").toFile()
    try {
      val zipFile = java.io.File(tempRoot, "evil.zip")
      java.util.zip.ZipOutputStream(java.io.FileOutputStream(zipFile)).use { zos ->
        zos.putNextEntry(java.util.zip.ZipEntry("../../outside.txt"))
        zos.write("malicious".toByteArray())
        zos.closeEntry()
      }

      val targetDir = java.io.File(tempRoot, "safe_dir")
      val outcome = com.example.automation.ZipAutoExtractor.extractZipFileAndVerify(zipFile, targetDir)

      org.junit.Assert.assertFalse(outcome.extractedSuccess)
      org.junit.Assert.assertFalse(outcome.deletedSourceArchive)
      org.junit.Assert.assertTrue(zipFile.exists())
    } finally {
      tempRoot.deleteRecursively()
    }
  }

  @Test
  fun `adaptive timing memory keeps rolling average of last 3 nights`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val prefs = com.example.data.local.NightDropPreferences(context)

    prefs.recordAdaptiveConnectionDuration(3_000L)
    prefs.recordAdaptiveConnectionDuration(6_000L)
    prefs.recordAdaptiveConnectionDuration(9_000L)
    val avgAfter3 = prefs.recordAdaptiveConnectionDuration(12_000L) // window is [6000, 9000, 12000] -> 9000

    assertEquals(9_000L, avgAfter3)
    val settings = prefs.getCurrentSettings()
    assertEquals(listOf(6_000L, 9_000L, 12_000L), settings.adaptiveTimingSamplesMs)
    assertEquals(9_000L, settings.learnedNetworkDelayMs)

    org.junit.Assert.assertTrue(
      com.example.automation.MacroAccessibilityService.MOBILE_DATA_KEYWORDS.contains("داده تلفن همراه")
    )
    org.junit.Assert.assertTrue(
      com.example.automation.MacroAccessibilityService.AIRPLANE_MODE_KEYWORDS.contains("حالت هواپیما")
    )
  }

  @Test
  fun `self optimizer calibrates drifted coordinates and scales network thresholds after frequent disconnects`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val macroDao = db.macroDao()
      val logDao = db.executionLogDao()
      val prefs = com.example.data.local.NightDropPreferences(context)
      val optimizer = com.example.automation.SelfOptimizer()

      val insertedId = macroDao.insertStep(
        com.example.data.local.MacroStepEntity(
          phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD.name,
          orderIndex = 0,
          actionType = com.example.data.local.GestureActionType.TAP.name,
          normStartX = 0.2f,
          normStartY = 0.2f,
          normEndX = 0.2f,
          normEndY = 0.2f,
          pixelStartX = 200,
          pixelStartY = 400,
          pixelEndX = 200,
          pixelEndY = 400,
          durationMs = 100L,
          delayAfterMs = 800L,
          label = "کلیک «Mobile data»",
          nodeText = "Mobile data"
        )
      )
      val step = macroDao.getStepById(insertedId)!!
      val calibration = optimizer.calibrateStepIfDrifted(
        step = step,
        result = com.example.automation.MacroStepExecutionResult(
          succeeded = true,
          usedSmartNodeClick = true,
          usedSemanticFinder = true,
          matchedNodeLabel = "Mobile data",
          resolvedBoundsCenterX = 540,
          resolvedBoundsCenterY = 860,
          resolvedScreenWidth = 1080,
          resolvedScreenHeight = 2400
        ),
        macroDao = macroDao
      )

      org.junit.Assert.assertNotNull(calibration)
      val updatedStep = macroDao.getStepById(insertedId)!!
      assertEquals(540, updatedStep.pixelStartX)
      assertEquals(860, updatedStep.pixelStartY)

      val now = System.currentTimeMillis()
      repeat(4) { idx ->
        logDao.insertLog(
          com.example.data.local.ExecutionLogEntity(
            taskId = 1L,
            title = "احیای خودکار آنتن و شبکه #$idx",
            detail = "تست پینگ ناموفق بود",
            severity = com.example.data.local.LogSeverity.WARNING.name,
            timestampMillis = now + idx
          )
        )
      }

      val report = optimizer.analyzeNightLogsAndOptimize(
        sessionStartMillis = now - 100L,
        executionLogDao = logDao,
        preferences = prefs
      )
      assertEquals(4, report.disconnectEventsObserved)
      org.junit.Assert.assertTrue(report.thresholdsChanged)
      assertEquals(5, report.newProbeIntervalSec)
      assertEquals(45, report.newPingTimeoutSec)
      val savedSettings = prefs.getCurrentSettings()
      assertEquals(5, savedSettings.adaptiveProbeIntervalSeconds)
      assertEquals(45, savedSettings.adaptivePingTimeoutSeconds)
    } finally {
      db.close()
    }
  }

  @Test
  fun `strategy ranker demotes failed strategy and promotes fallback to rank 1`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val ranker = com.example.automation.StrategyRanker(db.strategyDao())
      val initialRanked = ranker.getRankedStrategies(com.example.automation.StrategyGoal.ENABLE_DATA)
      assertEquals(com.example.automation.StrategyRanker.ID_ENABLE_MACRO, initialRanked.first().id)

      // Strategy A fails tonight (100 - 25 = 75), so Strategy B (85) becomes #1!
      ranker.recordOutcome(com.example.automation.StrategyRanker.ID_ENABLE_MACRO, succeeded = false)
      val reranked = ranker.getRankedStrategies(com.example.automation.StrategyGoal.ENABLE_DATA)
      assertEquals(com.example.automation.StrategyRanker.ID_ENABLE_SEMANTIC_BAR, reranked.first().id)
      assertEquals(75, reranked.first { it.id == com.example.automation.StrategyRanker.ID_ENABLE_MACRO }.successScore)
    } finally {
      db.close()
    }
  }

  @Test
  fun `gemini cloud troubleshooter sends ui tree executes generated steps and evolves brain engine`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      var capturedApiKeyHeader: String? = null
      var capturedPromptBody: String? = null

      val mockClient = okhttp3.OkHttpClient.Builder()
        .addInterceptor { chain ->
          val req = chain.request()
          capturedApiKeyHeader = req.header("x-goog-api-key")
          val buffer = okio.Buffer()
          req.body?.writeTo(buffer)
          capturedPromptBody = buffer.readUtf8()

          val generatedMacroJson = """
            [
              {
                "actionType": "TAP",
                "startX": 720,
                "startY": 960,
                "durationMs": 120,
                "delayBeforeNextMs": 600,
                "nodeText": "SIM 2",
                "contentDescription": "Use SIM 2 data",
                "label": "انتخاب دیتای سیمکارت 2"
              }
            ]
          """.trimIndent()

          val geminiEnvelope = org.json.JSONObject().put(
            "candidates",
            org.json.JSONArray().put(
              org.json.JSONObject().put(
                "content",
                org.json.JSONObject().put(
                  "parts",
                  org.json.JSONArray().put(
                    org.json.JSONObject().put("text", generatedMacroJson)
                  )
                )
              )
            )
          ).toString()

          okhttp3.Response.Builder()
            .request(req)
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(
              okhttp3.ResponseBody.Companion.run {
                geminiEnvelope.toResponseBody("application/json".let { okhttp3.MediaType.Companion.run { it.toMediaType() } })
              }
            )
            .build()
        }
        .build()

      val troubleshooter = com.example.automation.GeminiApiTroubleshooter(client = mockClient)
      val ranker = com.example.automation.StrategyRanker(db.strategyDao())
      val repo = com.example.data.local.MacroRepository(
        db.macroDao(),
        db.downloadTaskDao(),
        db.executionLogDao(),
        db.strategyDao()
      )
      val brain = com.example.automation.BrainEngine(context)

      val evolvedSuccess = brain.executeWithStrategyRanking(
        goal = com.example.automation.StrategyGoal.ENABLE_DATA,
        strategyRanker = ranker,
        executeStrategy = { false }, // All offline strategies hit a dead-end
        runGeminiFallback = {
          val res = troubleshooter.troubleshootAndGenerateMacro(
            apiKey = "AIzaTestKey123",
            goalDescription = "روشن کردن دیتای سیمکارت 2",
            phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD,
            uiNodes = listOf(
              com.example.automation.UiNodeSnapshot(
                text = "SIM 2",
                contentDescription = "Use SIM 2 data",
                viewId = "com.android.settings:id/sim2",
                className = "Button",
                isClickable = true,
                centerX = 720,
                centerY = 960
              )
            ),
            screenWidthPx = 1080,
            screenHeightPx = 2400
          )
          res.succeeded to res.generatedSteps
        },
        persistLearnedMacro = { steps ->
          repo.replacePhaseWithLearnedSteps(com.example.data.local.MacroPhase.PRE_DOWNLOAD, steps)
        },
        onLog = { _, _, _ -> }
      )

      org.junit.Assert.assertTrue(evolvedSuccess)
      assertEquals("AIzaTestKey123", capturedApiKeyHeader)
      org.junit.Assert.assertTrue(capturedPromptBody?.contains("SIM 2") == true)
      org.junit.Assert.assertTrue(capturedPromptBody?.contains("روشن کردن دیتای سیمکارت 2") == true)

      val savedSteps = repo.getSteps(com.example.data.local.MacroPhase.PRE_DOWNLOAD)
      assertEquals(1, savedSteps.size)
      assertEquals("SIM 2", savedSteps.first().nodeText)
      assertEquals(720, savedSteps.first().pixelStartX)
      assertEquals(960, savedSteps.first().pixelStartY)
    } finally {
      db.close()
    }
  }

  @Test
  fun `version checker detects newer github release manifest`() = kotlinx.coroutines.runBlocking {
    val mockClient = okhttp3.OkHttpClient.Builder()
      .addInterceptor { chain ->
        val json = """
          {
            "tag_name": "v2.4.0",
            "body": "بهبود پایداری شبانه و کالیبراسیون هوشمند",
            "html_url": "https://github.com/nightdrop-app/nightdrop-android/releases/tag/v2.4.0"
          }
        """.trimIndent()
        okhttp3.Response.Builder()
          .request(chain.request())
          .protocol(okhttp3.Protocol.HTTP_1_1)
          .code(200)
          .message("OK")
          .body(
            okhttp3.ResponseBody.Companion.run {
              json.toResponseBody("application/json".let { okhttp3.MediaType.Companion.run { it.toMediaType() } })
            }
          )
          .build()
      }
      .build()

    val checker = com.example.automation.VersionChecker(client = mockClient)
    val result = checker.checkForUpdate(
      manifestUrl = "https://api.github.com/repos/nightdrop-app/nightdrop-android/releases/latest",
      currentVersionCode = 1,
      currentVersionName = "1.0"
    )

    org.junit.Assert.assertTrue(result.succeeded)
    org.junit.Assert.assertTrue(result.hasUpdate)
    assertEquals("v2.4.0", result.latestVersionName)
    assertEquals("بهبود پایداری شبانه و کالیبراسیون هوشمند", result.releaseNotes)
    assertEquals("https://github.com/nightdrop-app/nightdrop-android/releases/tag/v2.4.0", result.releaseUrl)
  }

  @Test
  fun `deepseek compact serializer and regex extractor handle fenced and inline json`() {
    val compact = com.example.automation.DeepSeekAppTroubleshooter.serializeCompactUiTree(
      listOf(
        com.example.automation.UiNodeSnapshot(
          text = "Mobile data",
          contentDescription = "Toggle mobile data",
          viewId = "com.android.systemui:id/mobile_tile",
          className = "FrameLayout",
          isClickable = true,
          centerX = 270,
          centerY = 510
        ),
        com.example.automation.UiNodeSnapshot(
          text = null,
          contentDescription = null,
          viewId = null,
          className = "View",
          isClickable = false,
          centerX = 10,
          centerY = 10
        )
      )
    )
    org.junit.Assert.assertTrue(compact.contains("\"Mobile data\""))
    org.junit.Assert.assertTrue(compact.contains("@ (270,510)"))
    org.junit.Assert.assertFalse(compact.contains("(10,10)"))

    val chatOutput = """
      برای فعال کردن اینترنت این مراحل را اجرا کن:
      ```json
      [
        {"actionType": "TAP", "x": 270, "y": 510, "nodeText": "Mobile data", "delayBeforeNextMs": 900}
      ]
      ```
      موفق باشی!
    """.trimIndent()

    val extracted = com.example.automation.DeepSeekAppTroubleshooter.extractJsonArrayWithRegex(chatOutput)
    org.junit.Assert.assertNotNull(extracted)

    val parsed = com.example.automation.GeminiApiTroubleshooter.parseGeminiMacroResponse(
      rawResponse = extracted!!,
      phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD,
      screenWidthPx = 1080,
      screenHeightPx = 2400
    )
    assertEquals(1, parsed.size)
    assertEquals(270, parsed.first().startX)
    assertEquals(510, parsed.first().startY)
    assertEquals("Mobile data", parsed.first().nodeText)
  }

  @Test
  fun `deepseek app troubleshooter polls until stable response extracts json and triggers back`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    var launchedPkg: String? = null
    var injectedPrompt: String? = null
    var sendClicked = false
    var backPressed = false
    var pollCount = 0

    val finalJson = """
      این دستورات را اجرا کن:
      [{"actionType":"TAP","x":810,"y":1240,"nodeText":"Confirm","delayBeforeNextMs":700}]
    """.trimIndent()

    val fakeController = object : com.example.automation.DeepSeekUiController {
      override fun isPackageInstalled(packageName: String): Boolean =
        packageName == com.example.automation.DeepSeekAppTroubleshooter.DEEPSEEK_PACKAGE_NAME

      override fun launchApp(packageName: String): Boolean {
        launchedPkg = packageName
        return true
      }

      override fun findInputAndInjectText(prompt: String): Boolean {
        injectedPrompt = prompt
        return true
      }

      override fun clickSendButton(): Boolean {
        sendClicked = true
        return true
      }

      override fun readLatestResponse(promptSignature: String): String? {
        pollCount++
        return when (pollCount) {
          1 -> "در حال تحلیل صفحه..."
          2 -> "این دستورات را اجرا کن:\n[{\"actionType\":\"TAP\",\"x\":810" // streaming partial
          else -> finalJson // stable from poll 3, 4, 5
        }
      }

      override fun pressGlobalBack(): Boolean {
        backPressed = true
        return true
      }
    }

    val troubleshooter = com.example.automation.DeepSeekAppTroubleshooter(
      context = context,
      uiControllerFactory = { fakeController },
      loadWaitMs = 10L,
      postInjectWaitMs = 5L,
      pollIntervalMs = 20L,
      stableDurationMs = 40L, // 2 consecutive identical polls
      maxPollTimeoutMs = 500L,
      backSettleMs = 5L
    )

    val result = troubleshooter.solveViaDeepSeekApp(
      goalDescription = "روشن کردن دیتای سیم‌کارت",
      phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD,
      uiNodes = listOf(
        com.example.automation.UiNodeSnapshot(
          text = "Confirm",
          contentDescription = "Confirm mobile data",
          viewId = "android:id/button1",
          className = "Button",
          isClickable = true,
          centerX = 810,
          centerY = 1240
        )
      ),
      screenWidthPx = 1080,
      screenHeightPx = 2400
    )

    assertEquals("com.deepseek.chat", launchedPkg)
    org.junit.Assert.assertTrue(injectedPrompt?.contains("Confirm") == true)
    org.junit.Assert.assertTrue(injectedPrompt?.contains("روشن کردن دیتای سیم‌کارت") == true)
    org.junit.Assert.assertTrue(sendClicked)
    org.junit.Assert.assertTrue(backPressed)
    org.junit.Assert.assertTrue(result.succeeded)
    assertEquals(1, result.generatedSteps.size)
    assertEquals(810, result.generatedSteps.first().startX)
    assertEquals(1240, result.generatedSteps.first().startY)
    assertEquals("Confirm", result.generatedSteps.first().nodeText)
  }

  @Test
  fun `brain engine escalates from timed out gemini tier1 to deepseek tier2 and persists macro`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val db = androidx.room.Room.inMemoryDatabaseBuilder(
      context,
      com.example.data.local.AppDatabase::class.java
    ).allowMainThreadQueries().build()

    try {
      val ranker = com.example.automation.StrategyRanker(db.strategyDao())
      val repo = com.example.data.local.MacroRepository(
        db.macroDao(),
        db.downloadTaskDao(),
        db.executionLogDao(),
        db.strategyDao()
      )
      val brain = com.example.automation.BrainEngine(context)

      var geminiInvoked = false
      var deepSeekInvoked = false
      val loggedTitles = mutableListOf<String>()

      val deepSeekStep = com.example.data.local.MacroStep(
        phase = com.example.data.local.MacroPhase.PRE_DOWNLOAD,
        orderIndex = 0,
        type = com.example.data.local.GestureActionType.TAP,
        startX = 640,
        startY = 1120,
        endX = 640,
        endY = 1120,
        normStartX = 0.59f,
        normStartY = 0.47f,
        normEndX = 0.59f,
        normEndY = 0.47f,
        duration = 100L,
        nodeText = "Irancell",
        contentDescription = "SIM 1 Irancell",
        delayBeforeNextMs = 800L,
        label = "کلیک DeepSeek روی ایرانسل"
      )

      val ok = brain.executeWithStrategyRanking(
        goal = com.example.automation.StrategyGoal.ENABLE_DATA,
        strategyRanker = ranker,
        executeStrategy = { false }, // all offline strategies fail
        geminiTimeoutMs = 50L, // fast timeout for test
        runGeminiFallback = {
          geminiInvoked = true
          kotlinx.coroutines.delay(300L) // exceeds 50ms timeout!
          true to emptyList()
        },
        runDeepSeekFallback = {
          deepSeekInvoked = true
          true to listOf(deepSeekStep)
        },
        persistLearnedMacro = { steps ->
          repo.replacePhaseWithLearnedSteps(com.example.data.local.MacroPhase.PRE_DOWNLOAD, steps)
        },
        onLog = { title, _, _ -> loggedTitles.add(title) }
      )

      org.junit.Assert.assertTrue(ok)
      org.junit.Assert.assertTrue(geminiInvoked)
      org.junit.Assert.assertTrue(deepSeekInvoked)
      org.junit.Assert.assertTrue(
        loggedTitles.any { it.contains("DeepSeek") }
      )

      val saved = repo.getSteps(com.example.data.local.MacroPhase.PRE_DOWNLOAD)
      assertEquals(1, saved.size)
      assertEquals("Irancell", saved.first().nodeText)
      assertEquals(640, saved.first().pixelStartX)
      assertEquals(1120, saved.first().pixelStartY)
    } finally {
      db.close()
    }
  }

  @Test
  fun `qa crash proofing rejects invalid urls and safely handles missing zip or disconnected accessibility`() = kotlinx.coroutines.runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()

    val badTask = com.example.data.local.DownloadTaskEntity(
      id = 99L,
      url = "ftp://invalid-host.example/file.zip",
      fileName = "file.zip",
      scheduledHour = 2,
      scheduledMinute = 0,
      scheduledTriggerAtMillis = 0L,
      status = com.example.data.local.TaskStatus.QUEUED.name
    )
    var thrown: Throwable? = null
    try {
      com.example.automation.DownloadEnqueuer.enqueueDownload(context, badTask)
    } catch (t: Throwable) {
      thrown = t
    }
    org.junit.Assert.assertTrue(thrown is IllegalArgumentException)

    val brain = com.example.automation.BrainEngine(context)
    org.junit.Assert.assertNull(brain.activeAccessibilityService())
    org.junit.Assert.assertFalse(brain.isAccessibilityConnected.value)

    val missingZipOutcome = com.example.automation.ZipAutoExtractor.extractZipFileAndVerify(
      java.io.File(context.cacheDir, "does_not_exist_12345.zip"),
      java.io.File(context.cacheDir, "out_dir")
    )
    org.junit.Assert.assertFalse(missingZipOutcome.extractedSuccess)
  }

  @Test
  fun `optimization shared network client reuses connection pool and adaptive watchdog scales 5s to 15s`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    val base = com.example.automation.NetworkClient.httpClient
    val ping = com.example.automation.NetworkClient.pingClient
    val gemini = com.example.automation.NetworkClient.geminiClient
    val ota = com.example.automation.NetworkClient.otaClient

    org.junit.Assert.assertSame(base.connectionPool, ping.connectionPool)
    org.junit.Assert.assertSame(base.connectionPool, gemini.connectionPool)
    org.junit.Assert.assertSame(base.connectionPool, ota.connectionPool)
    org.junit.Assert.assertSame(base.dispatcher, ping.dispatcher)
    org.junit.Assert.assertSame(base.dispatcher, gemini.dispatcher)
    org.junit.Assert.assertSame(base.dispatcher, ota.dispatcher)
    org.junit.Assert.assertFalse(ping.followRedirects)
    assertEquals(8_000, gemini.callTimeoutMillis)

    val brain = com.example.automation.BrainEngine(context)
    assertEquals(
      com.example.automation.BrainEngine.STEADY_WATCHDOG_INTERVAL_MS,
      brain.computeAdaptiveWatchdogIntervalMs(isSteadyDownloading = true, zeroSpeedTicks = 0)
    )
    assertEquals(
      com.example.automation.BrainEngine.ACTIVE_WATCHDOG_INTERVAL_MS,
      brain.computeAdaptiveWatchdogIntervalMs(isSteadyDownloading = false, zeroSpeedTicks = 0)
    )
    assertEquals(
      com.example.automation.BrainEngine.ACTIVE_WATCHDOG_INTERVAL_MS,
      brain.computeAdaptiveWatchdogIntervalMs(isSteadyDownloading = true, zeroSpeedTicks = 1)
    )
  }
}
