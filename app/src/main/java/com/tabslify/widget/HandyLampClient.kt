package com.tabslify.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.tabslify.quiethoursnotificationhelper.laptopIp
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

object HandyLampClient {

    private const val TAG = "LampWidget"
    private const val HOST_PREFS = "commandcode_usage"
    private const val KEY_PC_HOST = "pc_host"
    private const val STATE_PREFS = "lamp_widget"
    private const val KEY_ON = "on"
    private const val API_PORT = 8886
    private const val TOGGLE_ENDPOINT = "/api/lampe/toggle"
    private const val STATE_ENDPOINT = "/api/lampe/state"
    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 6_000
    private const val STATE_CONNECT_TIMEOUT_MS = 1_500
    private const val STATE_READ_TIMEOUT_MS = 3_000
    private const val SCAN_CONNECT_TIMEOUT_MS = 350
    private const val SCAN_READ_TIMEOUT_MS = 400
    private const val SCAN_THREADS = 64
    private const val SCAN_BUDGET_MS = 4_000L

    fun cachedOn(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_ON)) prefs.getBoolean(KEY_ON, false) else null
    }

    fun flipCached(context: Context) {
        storeOn(context, cachedOn(context)?.let { !it } ?: true)
    }

    fun toggle(context: Context): Boolean {
        if (!shouldTryLan(context)) {
            Log.i(TAG, "Mobilfunk ohne VPN – LAN übersprungen, direkter Supabase-Weg")
            return false
        }
        for (host in priorityHosts(context)) {
            val result = postToggle(host)
            if (result.ok) {
                rememberHost(context, host)
                storeOn(context, result.state ?: getState(host) ?: cachedOn(context)?.let { !it } ?: true)
                return true
            }
        }
        val prefix = localIpv4()?.substringBeforeLast('.') ?: return false
        val hit = scanSubnet(prefix) ?: return false
        val result = postToggle(hit)
        if (!result.ok) return false
        rememberHost(context, hit)
        storeOn(context, result.state ?: getState(hit) ?: cachedOn(context)?.let { !it } ?: true)
        return true
    }

    fun refresh(context: Context) {
        if (!shouldTryLan(context)) return
        for (host in priorityHosts(context)) {
            val state = getState(host)
            if (state != null) {
                storeOn(context, state)
                return
            }
        }
        val prefix = localIpv4()?.substringBeforeLast('.') ?: return
        val hit = scanSubnet(prefix) ?: return
        getState(hit)?.let { storeOn(context, it) }
    }

    private fun storeOn(context: Context, on: Boolean) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ON, on).apply()
    }

    private fun shouldTryLan(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching true
        val active = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }.getOrDefault(true)

    private fun priorityHosts(context: Context): List<String> {
        val remembered = runCatching {
            context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE).getString(KEY_PC_HOST, "")
        }.getOrNull().orEmpty()
        val runtime = runCatching { laptopIp }.getOrNull().orEmpty()
        return listOf(remembered, runtime)
            .filter { it.isNotBlank() && it.count { char -> char == '.' } == 3 }
            .distinct()
    }

    private fun rememberHost(context: Context, host: String) {
        runCatching {
            context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PC_HOST, host).apply()
        }
    }

    private fun scanSubnet(prefix: String): String? {
        val pool = Executors.newFixedThreadPool(SCAN_THREADS) { runnable ->
            Thread(runnable, "lamp-host-scan").apply { isDaemon = true }
        }
        val hit = AtomicReference<String?>(null)
        try {
            val pending = CountDownLatch(254)
            for (last in 1..254) {
                val host = "$prefix.$last"
                pool.execute {
                    try {
                        if (portOpen(host)) hit.compareAndSet(null, host)
                    } catch (_: Exception) {
                    } finally {
                        pending.countDown()
                    }
                }
            }
            val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
            while (System.currentTimeMillis() < deadline && hit.get() == null) {
                TimeUnit.MILLISECONDS.sleep(80)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pool.shutdownNow()
        }
        return hit.get()
    }

    private fun portOpen(host: String): Boolean {
        val connection = URL("http://$host:$API_PORT/").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = SCAN_CONNECT_TIMEOUT_MS
            connection.readTimeout = SCAN_READ_TIMEOUT_MS
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }
            true
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private data class ToggleResult(val ok: Boolean, val state: Boolean?)

    private fun postToggle(host: String): ToggleResult {
        val connection = URL("http://$host:$API_PORT$TOGGLE_ENDPOINT").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(ByteArray(0)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Toggle an $host abgelehnt: HTTP $code")
                return ToggleResult(false, null)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Log.i(TAG, "Lampe umgeschaltet über $host")
            ToggleResult(true, parseToggleState(body))
        } catch (_: Exception) {
            ToggleResult(false, null)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseToggleState(body: String): Boolean? = runCatching {
        val json = JSONObject(body)
        if (json.has("state") && !json.isNull("state")) json.optBoolean("state") else null
    }.getOrNull()

    private fun getState(host: String): Boolean? {
        val connection = URL("http://$host:$API_PORT$STATE_ENDPOINT").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = STATE_CONNECT_TIMEOUT_MS
            connection.readTimeout = STATE_READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) return null
            parseStateBody(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseStateBody(body: String): Boolean? = runCatching {
        val devices = JSONObject(body).optJSONArray("devices") ?: return null
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            if (device.has("state") && !device.isNull("state")) return device.optBoolean("state")
        }
        null
    }.getOrNull()

    private fun localIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val candidate = interfaces.nextElement() ?: continue
            val addresses = candidate.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    val host = address.hostAddress ?: continue
                    if (host.startsWith("192.168.") || host.startsWith("10.")) return host
                }
            }
        }
        return null
    }
}
