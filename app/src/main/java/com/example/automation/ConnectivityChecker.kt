package com.example.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.net.HttpURLConnection

data class ConnectivityCheckResult(
    val hasValidatedCapability: Boolean,
    val httpPingSucceeded: Boolean,
    val httpStatusCode: Int
) {
    val isOnline: Boolean get() = hasValidatedCapability && httpPingSucceeded
}

object ConnectivityChecker {

    private const val PING_URL = "https://clients3.google.com/generate_204"

    fun hasValidatedNetwork(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val activeNetwork = cm.activeNetwork ?: return@runCatching false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    fun isAnyInternetConnected(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val activeNetwork = cm.activeNetwork ?: return@runCatching false
        val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }.getOrDefault(false)

    suspend fun checkRealInternet(context: Context): ConnectivityCheckResult = withContext(Dispatchers.IO) {
        val validated = hasValidatedNetwork(context)
        if (!validated) {
            return@withContext ConnectivityCheckResult(
                hasValidatedCapability = false,
                httpPingSucceeded = false,
                httpStatusCode = -1
            )
        }

        val request = Request.Builder()
            .url(PING_URL)
            .header("Cache-Control", "no-cache, no-store")
            .head()
            .build()

        val statusCode = try {
            NetworkClient.pingClient.newCall(request).execute().use { response ->
                response.code
            }
        } catch (_: Exception) {
            -1
        }

        val pingOk = statusCode == HttpURLConnection.HTTP_NO_CONTENT
        ConnectivityCheckResult(
            hasValidatedCapability = validated,
            httpPingSucceeded = pingOk,
            httpStatusCode = statusCode
        )
    }
}
