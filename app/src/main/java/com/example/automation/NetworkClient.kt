package com.example.automation

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttpClient hub so ConnectivityChecker, DownloadEnqueuer, GeminiApiTroubleshooter,
 * and VersionChecker reuse one ConnectionPool and Dispatcher thread pool with tight timeouts,
 * eliminating redundant thread/socket allocations and lowering battery & RAM overhead.
 */
object NetworkClient {

    private val sharedConnectionPool = ConnectionPool(5, 30L, TimeUnit.SECONDS)

    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(sharedConnectionPool)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * Captive-portal-safe client for 204 ping checks (no redirects, 2.5s I/O timeout).
     * Shares [httpClient]'s ConnectionPool and Dispatcher.
     */
    val pingClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(2_500L, TimeUnit.MILLISECONDS)
            .readTimeout(2_500L, TimeUnit.MILLISECONDS)
            .writeTimeout(2_500L, TimeUnit.MILLISECONDS)
            .callTimeout(3_000L, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /**
     * Tier-1 Gemini REST troubleshooter client capped at 8 seconds total call time.
     * Shares [httpClient]'s ConnectionPool and Dispatcher.
     */
    val geminiClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /**
     * OTA release manifest client sharing [httpClient]'s ConnectionPool and Dispatcher.
     */
    val otaClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
