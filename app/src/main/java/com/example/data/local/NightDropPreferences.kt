package com.example.data.local

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.nightDropDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "nightdrop_settings"
)

@Immutable
data class SchedulerSettings(
    val startHour: Int = 2,
    val startMinute: Int = 30,
    val cutoffHour: Int = 6,
    val cutoffMinute: Int = 55,
    val cutoffEnabled: Boolean = true,
    val networkStabilizationSeconds: Int = 5,
    val wakeScreenOnAlarm: Boolean = true,
    val wakeOnDownloadComplete: Boolean = true,
    val vibrateOnComplete: Boolean = true,
    val adaptiveTimingSamplesMs: List<Long> = emptyList(),
    val learnedNetworkDelayMs: Long = 3_000L,
    val geminiApiKey: String = "",
    val otaManifestUrl: String = DEFAULT_OTA_MANIFEST_URL,
    val adaptiveProbeIntervalSeconds: Int = 3,
    val adaptivePingTimeoutSeconds: Int = 30,
    val lastOptimizationSummary: String = ""
) {
    companion object {
        const val DEFAULT_OTA_MANIFEST_URL =
            "https://api.github.com/repos/nightdrop-app/nightdrop-android/releases/latest"
    }
}

class NightDropPreferences(private val context: Context) {

    private object Keys {
        val START_HOUR = intPreferencesKey("start_hour")
        val START_MINUTE = intPreferencesKey("start_minute")
        val CUTOFF_HOUR = intPreferencesKey("cutoff_hour")
        val CUTOFF_MINUTE = intPreferencesKey("cutoff_minute")
        val CUTOFF_ENABLED = booleanPreferencesKey("cutoff_enabled")
        val NETWORK_WAIT_SECONDS = intPreferencesKey("network_wait_seconds")
        val WAKE_ON_ALARM = booleanPreferencesKey("wake_on_alarm")
        val WAKE_ON_COMPLETE = booleanPreferencesKey("wake_on_complete")
        val VIBRATE_ON_COMPLETE = booleanPreferencesKey("vibrate_on_complete")
        val ADAPTIVE_TIMING_SAMPLES = stringPreferencesKey("adaptive_timing_samples_csv")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val OTA_MANIFEST_URL = stringPreferencesKey("ota_manifest_url")
        val ADAPTIVE_PROBE_INTERVAL_SEC = intPreferencesKey("adaptive_probe_interval_sec")
        val ADAPTIVE_PING_TIMEOUT_SEC = intPreferencesKey("adaptive_ping_timeout_sec")
        val LAST_OPTIMIZATION_SUMMARY = stringPreferencesKey("last_optimization_summary")
    }

    val settingsFlow: Flow<SchedulerSettings> = context.nightDropDataStore.data.map { prefs ->
        val samples = parseSamples(prefs[Keys.ADAPTIVE_TIMING_SAMPLES])
        val manualSeconds = (prefs[Keys.NETWORK_WAIT_SECONDS] ?: 5).coerceIn(3, 30)
        val learnedDelay = computeAdaptiveAverageMs(
            samples = samples,
            fallbackMs = manualSeconds * 1_000L
        )
        SchedulerSettings(
            startHour = (prefs[Keys.START_HOUR] ?: 2).coerceIn(0, 23),
            startMinute = (prefs[Keys.START_MINUTE] ?: 30).coerceIn(0, 59),
            cutoffHour = (prefs[Keys.CUTOFF_HOUR] ?: 6).coerceIn(0, 23),
            cutoffMinute = (prefs[Keys.CUTOFF_MINUTE] ?: 55).coerceIn(0, 59),
            cutoffEnabled = prefs[Keys.CUTOFF_ENABLED] ?: true,
            networkStabilizationSeconds = manualSeconds,
            wakeScreenOnAlarm = prefs[Keys.WAKE_ON_ALARM] ?: true,
            wakeOnDownloadComplete = prefs[Keys.WAKE_ON_COMPLETE] ?: true,
            vibrateOnComplete = prefs[Keys.VIBRATE_ON_COMPLETE] ?: true,
            adaptiveTimingSamplesMs = samples,
            learnedNetworkDelayMs = learnedDelay,
            geminiApiKey = prefs[Keys.GEMINI_API_KEY].orEmpty(),
            otaManifestUrl = prefs[Keys.OTA_MANIFEST_URL]
                ?.takeIf { it.isNotBlank() }
                ?: SchedulerSettings.DEFAULT_OTA_MANIFEST_URL,
            adaptiveProbeIntervalSeconds = (prefs[Keys.ADAPTIVE_PROBE_INTERVAL_SEC] ?: 3).coerceIn(2, 12),
            adaptivePingTimeoutSeconds = (prefs[Keys.ADAPTIVE_PING_TIMEOUT_SEC] ?: 30).coerceIn(20, 90),
            lastOptimizationSummary = prefs[Keys.LAST_OPTIMIZATION_SUMMARY].orEmpty()
        )
    }.flowOn(Dispatchers.IO)

    suspend fun getCurrentSettings(): SchedulerSettings = withContext(Dispatchers.IO) {
        settingsFlow.first()
    }

    /**
     * Records a measured network acquisition duration (time from turning on mobile data
     * to validated 204-ping connection) and retains a 3-night rolling window.
     */
    suspend fun recordAdaptiveConnectionDuration(durationMs: Long): Long = withContext(Dispatchers.IO) {
        val clamped = durationMs.coerceIn(1_000L, 45_000L)
        var newAverage = clamped
        context.nightDropDataStore.edit { prefs ->
            val current = parseSamples(prefs[Keys.ADAPTIVE_TIMING_SAMPLES])
            val updated = (current + clamped).takeLast(MAX_NIGHT_SAMPLES)
            prefs[Keys.ADAPTIVE_TIMING_SAMPLES] = updated.joinToString(",")
            newAverage = computeAdaptiveAverageMs(updated, fallbackMs = clamped)
        }
        newAverage
    }

    suspend fun setGeminiApiKey(apiKey: String) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.GEMINI_API_KEY] = apiKey.trim()
        }
    }

    suspend fun setOtaManifestUrl(url: String) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.OTA_MANIFEST_URL] = url.trim().ifBlank {
                SchedulerSettings.DEFAULT_OTA_MANIFEST_URL
            }
        }
    }

    suspend fun updateAdaptiveNetworkThresholds(
        probeIntervalSeconds: Int,
        pingTimeoutSeconds: Int,
        optimizationSummary: String
    ) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.ADAPTIVE_PROBE_INTERVAL_SEC] = probeIntervalSeconds.coerceIn(2, 12)
            prefs[Keys.ADAPTIVE_PING_TIMEOUT_SEC] = pingTimeoutSeconds.coerceIn(20, 90)
            prefs[Keys.LAST_OPTIMIZATION_SUMMARY] = optimizationSummary
        }
    }

    suspend fun setStartTime(hour: Int, minute: Int) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.START_HOUR] = hour.coerceIn(0, 23)
            prefs[Keys.START_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setCutoffTime(hour: Int, minute: Int) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.CUTOFF_HOUR] = hour.coerceIn(0, 23)
            prefs[Keys.CUTOFF_MINUTE] = minute.coerceIn(0, 59)
        }
    }

    suspend fun setCutoffEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.CUTOFF_ENABLED] = enabled
        }
    }

    suspend fun setNetworkStabilizationSeconds(seconds: Int) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.NETWORK_WAIT_SECONDS] = seconds.coerceIn(3, 30)
        }
    }

    suspend fun setWakeScreenOnAlarm(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.WAKE_ON_ALARM] = enabled
        }
    }

    suspend fun setWakeOnDownloadComplete(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.WAKE_ON_COMPLETE] = enabled
        }
    }

    suspend fun setVibrateOnComplete(enabled: Boolean) = withContext(Dispatchers.IO) {
        context.nightDropDataStore.edit { prefs ->
            prefs[Keys.VIBRATE_ON_COMPLETE] = enabled
        }
    }

    companion object {
        const val MAX_NIGHT_SAMPLES = 3

        fun parseSamples(raw: String?): List<Long> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(",")
                .mapNotNull { it.trim().toLongOrNull() }
                .takeLast(MAX_NIGHT_SAMPLES)
        }

        fun computeAdaptiveAverageMs(samples: List<Long>, fallbackMs: Long = 3_000L): Long {
            if (samples.isEmpty()) return fallbackMs.coerceIn(1_000L, 45_000L)
            val recent = samples.takeLast(MAX_NIGHT_SAMPLES)
            return (recent.sum() / recent.size).coerceIn(1_000L, 45_000L)
        }
    }
}
