package com.example.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

class MacroRepository(
    private val macroDao: MacroDao,
    private val downloadTaskDao: DownloadTaskDao,
    private val executionLogDao: ExecutionLogDao,
    private val strategyDao: StrategyDao? = null
) {
    fun observeSteps(phase: MacroPhase): Flow<List<MacroStepEntity>> =
        macroDao.observeStepsForPhase(phase.name).flowOn(Dispatchers.IO)

    suspend fun getSteps(phase: MacroPhase): List<MacroStepEntity> = withContext(Dispatchers.IO) {
        macroDao.getStepsForPhase(phase.name)
    }

    suspend fun addStep(
        phase: MacroPhase,
        actionType: GestureActionType,
        normStartX: Float = 0.5f,
        normStartY: Float = 0.5f,
        normEndX: Float = 0.5f,
        normEndY: Float = 0.5f,
        pixelStartX: Int = 0,
        pixelStartY: Int = 0,
        pixelEndX: Int = 0,
        pixelEndY: Int = 0,
        durationMs: Long = 120L,
        delayAfterMs: Long = 800L,
        label: String,
        nodeText: String? = null,
        contentDescription: String? = null,
        viewIdResourceName: String? = null
    ): Long = withContext(Dispatchers.IO) {
        val nextIndex = macroDao.getNextOrderIndex(phase.name)
        macroDao.insertStep(
            MacroStepEntity(
                phase = phase.name,
                orderIndex = nextIndex,
                actionType = actionType.name,
                normStartX = normStartX,
                normStartY = normStartY,
                normEndX = normEndX,
                normEndY = normEndY,
                pixelStartX = pixelStartX,
                pixelStartY = pixelStartY,
                pixelEndX = pixelEndX,
                pixelEndY = pixelEndY,
                durationMs = durationMs,
                delayAfterMs = delayAfterMs.coerceAtLeast(500L),
                label = label,
                nodeText = nodeText?.takeIf { it.isNotBlank() },
                contentDescription = contentDescription?.takeIf { it.isNotBlank() },
                viewIdResourceName = viewIdResourceName?.takeIf { it.isNotBlank() }
            )
        )
    }

    suspend fun updateStepDelayById(stepId: Long, delayBeforeNextMs: Long) = withContext(Dispatchers.IO) {
        macroDao.updateStepDelayById(stepId, delayBeforeNextMs.coerceIn(500L, 60_000L))
    }

    suspend fun undoLastStep(phase: MacroPhase): MacroStepEntity? = withContext(Dispatchers.IO) {
        val last = macroDao.getLastStepForPhase(phase.name) ?: return@withContext null
        macroDao.deleteStepById(last.id)
        macroDao.getLastStepForPhase(phase.name)
    }

    suspend fun getLastStep(phase: MacroPhase): MacroStepEntity? = withContext(Dispatchers.IO) {
        macroDao.getLastStepForPhase(phase.name)
    }

    suspend fun moveStep(phase: MacroPhase, stepId: Long, delta: Int) = withContext(Dispatchers.IO) {
        val steps = macroDao.getStepsForPhase(phase.name)
        val idx = steps.indexOfFirst { it.id == stepId }
        if (idx == -1) return@withContext
        val targetIdx = idx + delta
        if (targetIdx !in steps.indices) return@withContext

        val first = steps[idx]
        val second = steps[targetIdx]
        macroDao.updateStep(first.copy(orderIndex = targetIdx))
        macroDao.updateStep(second.copy(orderIndex = idx))
    }

    suspend fun applyPreset(phase: MacroPhase, items: List<Pair<GestureActionType, Pair<String, Long>>>) =
        withContext(Dispatchers.IO) {
            macroDao.clearPhase(phase.name)
            items.forEachIndexed { idx, (type, labelAndDelay) ->
                macroDao.insertStep(
                    MacroStepEntity(
                        phase = phase.name,
                        orderIndex = idx,
                        actionType = type.name,
                        normStartX = 0.5f,
                        normStartY = 0.5f,
                        normEndX = 0.5f,
                        normEndY = 0.5f,
                        pixelStartX = 540,
                        pixelStartY = 1200,
                        pixelEndX = 540,
                        pixelEndY = 1200,
                        durationMs = 200L,
                        delayAfterMs = labelAndDelay.second.coerceAtLeast(500L),
                        label = labelAndDelay.first
                    )
                )
            }
        }

    suspend fun updateStepDelay(step: MacroStepEntity, newDelayMs: Long) = withContext(Dispatchers.IO) {
        macroDao.updateStep(step.copy(delayAfterMs = newDelayMs.coerceIn(500L, 60_000L)))
    }

    suspend fun deleteStep(stepId: Long) = withContext(Dispatchers.IO) {
        macroDao.deleteStepById(stepId)
    }

    suspend fun clearPhase(phase: MacroPhase) = withContext(Dispatchers.IO) {
        macroDao.clearPhase(phase.name)
    }

    fun observeAllTasks(): Flow<List<DownloadTaskEntity>> =
        downloadTaskDao.observeAllTasks().flowOn(Dispatchers.IO)

    suspend fun getNextQueueOrder(): Int = withContext(Dispatchers.IO) {
        downloadTaskDao.getNextQueueOrder()
    }

    suspend fun getPendingOrActiveTasks(): List<DownloadTaskEntity> = withContext(Dispatchers.IO) {
        downloadTaskDao.getPendingOrActiveTasks()
    }

    suspend fun insertTask(task: DownloadTaskEntity): Long = withContext(Dispatchers.IO) {
        downloadTaskDao.insertTask(task)
    }

    suspend fun updateTask(task: DownloadTaskEntity) = withContext(Dispatchers.IO) {
        downloadTaskDao.updateTask(task)
    }

    suspend fun deleteTask(id: Long) = withContext(Dispatchers.IO) {
        downloadTaskDao.deleteTaskById(id)
    }

    suspend fun clearFinishedTasks() = withContext(Dispatchers.IO) {
        downloadTaskDao.clearFinishedTasks()
    }

    fun observeRecentLogs(): Flow<List<ExecutionLogEntity>> =
        executionLogDao.observeRecentLogs().flowOn(Dispatchers.IO)

    suspend fun logEvent(
        taskId: Long,
        title: String,
        detail: String,
        severity: LogSeverity = LogSeverity.INFO
    ) = withContext(Dispatchers.IO) {
        executionLogDao.insertLog(
            ExecutionLogEntity(
                taskId = taskId,
                title = title,
                detail = detail,
                severity = severity.name
            )
        )
    }

    suspend fun replacePhaseWithLearnedSteps(phase: MacroPhase, steps: List<MacroStep>) =
        withContext(Dispatchers.IO) {
            macroDao.clearPhase(phase.name)
            steps.forEachIndexed { idx, step ->
                macroDao.insertStep(
                    step.copy(
                        id = 0L,
                        phase = phase,
                        orderIndex = idx
                    ).toEntity()
                )
            }
        }

    fun observeAllStrategies(): Flow<List<StrategyEntity>> =
        (strategyDao?.observeAllStrategies() ?: kotlinx.coroutines.flow.flowOf(emptyList()))
            .flowOn(Dispatchers.IO)

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        executionLogDao.clearAllLogs()
    }
}
