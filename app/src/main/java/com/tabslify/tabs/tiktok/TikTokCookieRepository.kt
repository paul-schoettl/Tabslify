package com.tabslify.tabs.tiktok

import android.content.Context
import android.util.Log
import com.tabslify.quiethoursnotificationhelper.laptopIp
import com.tabslify.quiethoursnotificationhelper.laptopName
import com.tabslify.quiethoursnotificationhelper.resolveSyncSecret
import com.tabslify.tabs.authenticator.TotpGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class TikTokSession(val cookie: String, val deviceId: String)

class TikTokCookieException(message: String) : Exception(message)

object TikTokCookieRepository {

    private const val TAG = "TikTokTab"
    private const val PREFS = "tiktok"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_FROM_PC = "cookie_from_pc"
    private const val KEY_PC_HOST = "pc_host"
    private const val API_PORT = 8886
    private const val COOKIE_ENDPOINT = "/tiktok/cookie"
    private const val SCAN_THREADS = 64
    private const val SCAN_BUDGET_MS = 4_000L

    suspend fun session(context: Context): TikTokSession = withContext(Dispatchers.IO) {
        var cached = readSession(context)
        if (cached.cookie.isEmpty() || !isFromPc(context)) {
            val fresh = fetchFromPc(context)
            if (fresh != null) {
                writeSession(context, fresh)
                markFromPc(context)
                cached = fresh
            } else if (cached.cookie.isEmpty()) {
                throw TikTokCookieException("kein Cookie")
            }
        }
        cached
    }

    suspend fun refresh(context: Context): TikTokSession? = withContext(Dispatchers.IO) {
        val fresh = fetchFromPc(context) ?: return@withContext null
        writeSession(context, fresh)
        markFromPc(context)
        fresh
    }

    private fun isFromPc(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_FROM_PC, false)

    private fun markFromPc(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FROM_PC, true).apply()
    }

    private suspend fun fetchFromPc(context: Context): TikTokSession? = withContext(Dispatchers.IO) {
        val secret = resolveSecret(context)
        if (secret.isEmpty()) {
            Log.w(TAG, "Kein PC-Sync-Secret – TikTok-Cookie kann nicht beim PC geholt werden")
            return@withContext null
        }
        for (host in pcHosts(context)) {
            val session = requestSession(host, secret)
            if (session != null) {
                Log.i(TAG, "TikTok-Cookie vom PC bezogen: $host")
                rememberHost(context, host)
                return@withContext session
            }
        }
        val prefix = localIpv4()?.substringBeforeLast('.')
        if (prefix == null) return@withContext null
        val hit = scanSubnet(prefix)
        if (hit != null) {
            Log.i(TAG, "PC per Netzwerkscan gefunden: $hit")
            val session = requestSession(hit, secret)
            if (session != null) rememberHost(context, hit)
            return@withContext session
        }
        Log.w(TAG, "Kein PC mit Port $API_PORT im Netz $prefix.0/24 gefunden")
        null
    }

    private fun resolveSecret(context: Context): String {
        val secrets = context.getSharedPreferences("pc_secrets", Context.MODE_PRIVATE)
        val registered = context.getSharedPreferences("registered_pcs", Context.MODE_PRIVATE)
        val names = linkedSetOf<String>()
        if (laptopName.isNotBlank()) names.add(laptopName)
        names.addAll(registered.all.keys)
        names.addAll(secrets.all.keys)
        for (name in names) {
            resolveSyncSecret(context, name)?.let { if (it.isNotBlank()) return it }
        }
        return ""
    }

    private fun requestSession(host: String, secret: String): TikTokSession? {
        val connection = URL("http://$host:$API_PORT$COOKIE_ENDPOINT").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 4_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("X-Tabslify-Totp", TotpGenerator.generateTOTP(secret))
            connection.outputStream.use { it.write(ByteArray(0)) }
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val cookie = sanitizeCookie(json.optString("cookie"))
            if (cookie.isEmpty()) return null
            TikTokSession(cookie, json.optString("device_id").trim())
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun pcHosts(context: Context): List<String> {
        val remembered = knownHost(context)
        val runtime = runCatching { laptopIp }.getOrNull().orEmpty()
        return listOf(remembered, runtime)
            .filter { it.isNotBlank() && it.count { char -> char == '.' } == 3 }
            .distinct()
    }

    private fun knownHost(context: Context): String = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PC_HOST, "").orEmpty()
    }.getOrDefault("")

    private fun rememberHost(context: Context, host: String) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_PC_HOST, host).apply()
        }
    }

    private fun scanSubnet(prefix: String): String? {
        val pool = Executors.newFixedThreadPool(SCAN_THREADS) { runnable ->
            Thread(runnable, "tiktok-host-scan").apply { isDaemon = true }
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
            connection.connectTimeout = 350
            connection.readTimeout = 400
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }
            true
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun localIpv4(): String? {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces() ?: return null
        while (interfaces.hasMoreElements()) {
            val candidate = interfaces.nextElement() ?: continue
            val addresses = candidate.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is java.net.Inet4Address && !address.isLoopbackAddress &&
                    !address.isLinkLocalAddress
                ) {
                    val host = address.hostAddress ?: continue
                    if (host.startsWith("192.168.") || host.startsWith("10.")) return host
                }
            }
        }
        return null
    }

    private fun readSession(context: Context): TikTokSession {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TikTokSession(
            sanitizeCookie(prefs.getString(KEY_COOKIE, "").orEmpty()),
            prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        )
    }

    private fun writeSession(context: Context, session: TikTokSession) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COOKIE, sanitizeCookie(session.cookie))
            .putString(KEY_DEVICE_ID, session.deviceId)
            .apply()
    }

    private fun sanitizeCookie(raw: String): String {
        var value = raw.replace("\r", "").replace("\n", "").replace("\t", "")
            .replace("\"", "").trim()
        if (value.startsWith("cookie:", ignoreCase = true)) {
            value = value.substring("cookie:".length).trim()
        }
        while (value.endsWith(">")) {
            value = value.dropLast(1).trimEnd()
        }
        return value
    }
}
