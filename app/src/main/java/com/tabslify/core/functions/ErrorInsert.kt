package com.tabslify.core.functions

import com.tabslify.core.activities.Tabslify.Companion.serviceScope
import com.tabslify.core.logging.AppLog
import com.tabslify.core.logging.LogLevel
import com.tabslify.core.objects.Config
import io.github.jan.supabase.exceptions.HttpRequestException as SupabaseHttpRequestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.UnknownHostException

fun errorInsert(serviceName: String, errorMessage: String, createdAt: String, severity: String) {
    AppLog.write(
        level = when (severity.uppercase()) {
            "ERROR" -> LogLevel.ERROR
            "WARN", "WARNING" -> LogLevel.WARN
            "DEBUG" -> LogLevel.DEBUG
            else -> LogLevel.INFO
        },
        tag = serviceName,
        message = errorMessage,
        throwable = null
    )

    if (!Config.realDevice) return

    serviceScope.launch {
        try {
            Config.client.functions.invoke(
                function = "report_error",
                body = mapOf(
                    "serviceName" to serviceName,
                    "errorMessage" to errorMessage,
                    "createdAt" to createdAt,
                    "severity" to severity
                )
            )
        } catch (_: Exception) {
        }
    }
}

fun isNetworkFailure(e: Throwable): Boolean =
    e is HttpRequestTimeoutException ||
        e is SupabaseHttpRequestException ||
        e is IOException

fun neverReachedServer(e: Throwable): Boolean =
    e is UnknownHostException ||
        e.message?.contains("Unable to resolve host", ignoreCase = true) == true