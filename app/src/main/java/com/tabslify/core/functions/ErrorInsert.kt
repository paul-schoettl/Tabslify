package com.tabslify.core.functions

import com.tabslify.core.activities.Tabslify.Companion.serviceScope
import com.tabslify.core.objects.Config
import io.github.jan.supabase.exceptions.HttpRequestException as SupabaseHttpRequestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

fun errorInsert(serviceName: String, errorMessage: String, createdAt: String, severity: String) {
    if (!Config.realDevice) {
        println(mapOf(
            "serviceName" to serviceName,
            "errorMessage" to errorMessage,
            "createdAt" to createdAt,
            "severity" to severity
        ))
        return
    }
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
        } catch (e: Exception) {
            println("Error invoking Edge Function: ${e.message}")
        }
    }
}

fun isNetworkFailure(e: Throwable): Boolean =
    e is HttpRequestTimeoutException ||
        e is SupabaseHttpRequestException ||
        e is UnknownHostException ||
        e is ConnectException ||
        e is SocketTimeoutException

fun neverReachedServer(e: Throwable): Boolean =
    e is UnknownHostException ||
        e.message?.contains("Unable to resolve host", ignoreCase = true) == true