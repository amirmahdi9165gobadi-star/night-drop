package com.example.automation

import com.example.data.local.StrategyDao
import com.example.data.local.StrategyEntity

enum class StrategyGoal(val persianName: String) {
    ENABLE_DATA("روشن کردن اینترنت موبایل"),
    DISABLE_DATA("قطع اینترنت و بستن اتصالات"),
    POWER_OFF("باز کردن منوی خاموش کردن گوشی")
}

enum class StrategyMethod {
    RECORDED_MACRO,
    SEMANTIC_TOOLBAR,
    SETTINGS_INTENT
}

/**
 * Multi-Strategy Fallback & Ranking:
 * Maintains ranked strategies (A: Recorded Macro, B: Semantic Toolbar Search, C: Settings Intent)
 * backed by Room successScore values. Failed strategies are penalized (-25) so stronger
 * alternatives automatically promote to rank #1 on subsequent nights.
 */
class StrategyRanker(
    private val strategyDao: StrategyDao,
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun ensureSeeded() {
        if (strategyDao.countStrategies() == 0) {
            strategyDao.insertAllIgnore(defaultStrategies(clockMillis()))
        }
    }

    suspend fun getRankedStrategies(goal: StrategyGoal): List<StrategyEntity> {
        ensureSeeded()
        return strategyDao.getStrategiesForGoal(goal.name)
    }

    suspend fun recordOutcome(strategyId: String, succeeded: Boolean): StrategyEntity? {
        ensureSeeded()
        val existing = strategyDao.getStrategyById(strategyId) ?: return null
        val delta = if (succeeded) SCORE_REWARD else -SCORE_PENALTY
        val updatedScore = (existing.successScore + delta).coerceIn(MIN_SCORE, MAX_SCORE)
        val updated = existing.copy(
            successScore = updatedScore,
            successCount = if (succeeded) existing.successCount + 1 else existing.successCount,
            failureCount = if (!succeeded) existing.failureCount + 1 else existing.failureCount,
            lastUpdatedMillis = clockMillis()
        )
        strategyDao.updateStrategy(updated)
        return updated
    }

    suspend fun resetAllScores() {
        val now = clockMillis()
        defaultStrategies(now).forEach { seed ->
            val current = strategyDao.getStrategyById(seed.id)
            if (current == null) {
                strategyDao.insertAllIgnore(listOf(seed))
            } else {
                strategyDao.updateStrategy(seed.copy(lastUpdatedMillis = now))
            }
        }
    }

    companion object {
        const val SCORE_REWARD = 10
        const val SCORE_PENALTY = 25
        const val MIN_SCORE = 10
        const val MAX_SCORE = 200

        const val ID_ENABLE_MACRO = "ENABLE_DATA_MACRO"
        const val ID_ENABLE_SEMANTIC_BAR = "ENABLE_DATA_SEMANTIC_BAR"
        const val ID_ENABLE_SETTINGS_INTENT = "ENABLE_DATA_SETTINGS_INTENT"

        const val ID_DISABLE_MACRO = "DISABLE_DATA_MACRO"
        const val ID_DISABLE_SEMANTIC_BAR = "DISABLE_DATA_SEMANTIC_BAR"
        const val ID_DISABLE_SETTINGS_INTENT = "DISABLE_DATA_SETTINGS_INTENT"

        const val ID_POWER_MACRO = "POWER_OFF_MACRO"
        const val ID_POWER_SEMANTIC = "POWER_OFF_SEMANTIC"

        fun defaultStrategies(nowMillis: Long = System.currentTimeMillis()): List<StrategyEntity> = listOf(
            StrategyEntity(
                id = ID_ENABLE_MACRO,
                goal = StrategyGoal.ENABLE_DATA.name,
                strategyCode = StrategyMethod.RECORDED_MACRO.name,
                title = "استراتژی A: اجرای ماکروی ضبط‌شده",
                successScore = 100,
                lastUpdatedMillis = nowMillis
            ),
            StrategyEntity(
                id = ID_ENABLE_SEMANTIC_BAR,
                goal = StrategyGoal.ENABLE_DATA.name,
                strategyCode = StrategyMethod.SEMANTIC_TOOLBAR.name,
                title = "استراتژی B: جستجوی متنی در نوار ابزار سریع",
                successScore = 85,
                lastUpdatedMillis = nowMillis
            ),
            StrategyEntity(
                id = ID_ENABLE_SETTINGS_INTENT,
                goal = StrategyGoal.ENABLE_DATA.name,
                strategyCode = StrategyMethod.SETTINGS_INTENT.name,
                title = "استراتژی C: باز کردن پنل تنظیمات شبکه با Intent",
                successScore = 70,
                lastUpdatedMillis = nowMillis
            ),
            StrategyEntity(
                id = ID_DISABLE_MACRO,
                goal = StrategyGoal.DISABLE_DATA.name,
                strategyCode = StrategyMethod.RECORDED_MACRO.name,
                title = "استراتژی A: ماکروی ضبط‌شده قطع دیتا",
                successScore = 100,
                lastUpdatedMillis = nowMillis
            ),
            StrategyEntity(
                id = ID_DISABLE_SEMANTIC_BAR,
                goal = StrategyGoal.DISABLE_DATA.name,
                strategyCode = StrategyMethod.SEMANTIC_TOOLBAR.name,
                title = "استراتژی B: جستجوی متنی قطع دیتا در نوار ابزار",
                successScore = 85,
                lastUpdatedMillis = nowMillis
            ),
            StrategyEntity(
                id = ID_DISABLE_SETTINGS_INTENT,
                goal = StrategyGoal.DISABLE_DATA.name,
                strategyCode = StrategyMethod.SETTINGS_INTENT.name,
                title = "استراتژی C: باز کردن پنل شبکه با Intent برای قطع دیتا",
                successScore = 70,
                lastUpdatedMillis = nowMillis
            ),
            StrategyEntity(
                id = ID_POWER_MACRO,
                goal = StrategyGoal.POWER_OFF.name,
                strategyCode = StrategyMethod.RECORDED_MACRO.name,
                title = "استراتژی A: ماکروی ضبط‌شده منوی پاور",
                successScore = 100,
                lastUpdatedMillis = nowMillis
            ),
            StrategyEntity(
                id = ID_POWER_SEMANTIC,
                goal = StrategyGoal.POWER_OFF.name,
                strategyCode = StrategyMethod.SEMANTIC_TOOLBAR.name,
                title = "استراتژی B: دیالوگ پاور سیستم و کلیک متنی خاموشی",
                successScore = 85,
                lastUpdatedMillis = nowMillis
            )
        )
    }
}
