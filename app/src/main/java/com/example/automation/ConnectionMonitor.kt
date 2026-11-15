package com.example.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull

data class ConnectionMonitorProgress(
    val round: Int,
    val attempt: Int,
    val remainingSeconds: Int,
    val checkResult: ConnectivityCheckResult
)

data class ConnectionMonitorOutcome(
    val isConnected: Boolean,
    val roundReached: Int,
    val retriedPreMacro: Boolean,
    val lastResult: ConnectivityCheckResult?
)

class ConnectionMonitor(
    private val context: Context,
    private val windowMillis: Long = 30_000L,
    private val probeIntervalMillis: Long = 3_000L,
    private val internetProbe: suspend () -> ConnectivityCheckResult = {
        ConnectivityChecker.checkRealInternet(context)
    }
) {

    /**
     * Emits whenever ConnectivityManager reports capability changes (specifically
     * NET_CAPABILITY_VALIDATED) or when the periodic probe ticker fires.
     */
    fun observeNetworkValidationTriggers(): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Emit initial snapshot trigger immediately
        trySend(ConnectivityChecker.hasValidatedNetwork(context))

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(ConnectivityChecker.hasValidatedNetwork(context))
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val validated =
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                        networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                trySend(validated)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }

        val registered = runCatching {
            cm.registerDefaultNetworkCallback(callback)
            true
        }.getOrDefault(false)

        awaitClose {
            if (registered) {
                runCatching { cm.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun periodicTickerFlow(): Flow<Boolean> = flow {
        while (true) {
            delay(probeIntervalMillis)
            emit(true)
        }
    }

    suspend fun awaitValidatedWindow(
        round: Int,
        onProgress: suspend (ConnectionMonitorProgress) -> Unit = {}
    ): Pair<Boolean, ConnectivityCheckResult?> {
        val startedAt = System.currentTimeMillis()
        var attempt = 0
        var lastResult: ConnectivityCheckResult? = null

        val connected = withTimeoutOrNull(windowMillis) {
            merge(observeNetworkValidationTriggers(), periodicTickerFlow())
                .map {
                    attempt++
                    val result = internetProbe()
                    lastResult = result
                    val elapsed = System.currentTimeMillis() - startedAt
                    val remainingSec = ((windowMillis - elapsed).coerceAtLeast(0L) / 1_000L).toInt()
                    onProgress(
                        ConnectionMonitorProgress(
                            round = round,
                            attempt = attempt,
                            remainingSeconds = remainingSec,
                            checkResult = result
                        )
                    )
                    result.isOnline
                }
                .firstOrNull { isOnline -> isOnline } == true
        } ?: false

        return connected to lastResult
    }

    /**
     * Verifies network via ConnectivityManager.NET_CAPABILITY_VALIDATED + HTTP 204 ping.
     * If not validated within [windowMillis] (30s default), invokes [onRetryPreMacro] once
     * and monitors for a second [windowMillis] window.
     */
    suspend fun awaitInternetWithMacroRetry(
        onProgress: suspend (ConnectionMonitorProgress) -> Unit = {},
        onRetryPreMacro: suspend () -> Unit
    ): ConnectionMonitorOutcome {
        val (round1Ok, round1Last) = awaitValidatedWindow(round = 1, onProgress = onProgress)
        if (round1Ok) {
            return ConnectionMonitorOutcome(
                isConnected = true,
                roundReached = 1,
                retriedPreMacro = false,
                lastResult = round1Last
            )
        }

        // 30s elapsed without validated internet -> re-execute pre-download macro once
        onRetryPreMacro()

        val (round2Ok, round2Last) = awaitValidatedWindow(round = 2, onProgress = onProgress)
        return ConnectionMonitorOutcome(
            isConnected = round2Ok,
            roundReached = 2,
            retriedPreMacro = true,
            lastResult = round2Last
        )
    }
}
