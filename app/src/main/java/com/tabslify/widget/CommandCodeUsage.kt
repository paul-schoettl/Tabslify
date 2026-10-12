package com.tabslify.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.tabslify.core.objects.Config
import com.tabslify.quiethoursnotificationhelper.laptopIp
import com.tabslify.quiethoursnotificationhelper.laptopName
import com.tabslify.quiethoursnotificationhelper.resolveSyncSecret
import com.tabslify.tabs.authenticator.TotpGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CookieExpiredException(message: String) : Exception(message)

class CommandCodeBlockedException(message: String) : Exception(message)

class CommandCodeServerException(val status: Int, message: String) : Exception(message)

data class CommandCodeUsage(
    val planId: String,
    val used: Double,
    val cap: Double,
    val pct: Double,
    val pacePct: Double,
    val offlineDays: Int
) {
    val overPace: Boolean get() = pct > pacePct
}

object CommandCodeUsageRepository {

    private const val TAG = "CommandCodeWidget"
    private const val PREFS = "commandcode_usage"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_COOKIE_FROM_PC = "cookie_from_pc"
    private const val KEY_PC_HOST = "pc_host"
    private const val API_PORT = 8886
    private const val COOKIE_ENDPOINT = "/commandcode/cookie"
    private const val SCAN_THREADS = 64
    private const val SCAN_BUDGET_MS = 4_000L
    private const val CREDITS_URL = "https://api.commandcode.ai/internal/billing/credits"
    private const val SUBSCRIPTIONS_URL =
        "https://api.commandcode.ai/internal/billing/subscriptions?withPending=true"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Tabslify) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
    private const val DAY_MS = 86_400_000L

    private val tierCredits = mapOf(
        "individual-provider" to 15.0,
        "individual-go" to 10.0,
        "individual-go-v1" to 10.0,
        "individual-pro" to 30.0,
        "individual-pro-v1" to 80.0,
        "individual-goat" to 70.0,
        "individual-max" to 150.0,
        "individual-ultra" to 300.0,
        "teams-pro" to 40.0
    )

    suspend fun load(context: Context): CommandCodeUsage = withContext(Dispatchers.IO) {
        var cookie = readCookie(context)
        if (cookie.isEmpty() || !cookieIsFromPc(context)) {
            val fresh = refreshCookieFromPc(context)
            if (fresh.isNotEmpty()) {
                cookie = fresh
                markCookieFromPc(context)
            } else if (cookie.isEmpty()) {
                throw CookieExpiredException("kein Cookie")
            }
        }
        try {
            fetchUsage(cookie)
        } catch (expired: CookieExpiredException) {
            val refreshed = refreshCookieFromPc(context)
            if (refreshed.isEmpty() || refreshed == cookie) throw expired
            markCookieFromPc(context)
            Log.i(TAG, "Cookie war abgelaufen, neuer Versuch mit frischem Cookie")
            fetchUsage(refreshed)
        }
    }

    private suspend fun fetchUsage(cookie: String): CommandCodeUsage = coroutineScope {
        val creditsDeferred = async { httpGet(CREDITS_URL, listOf("Cookie" to cookie)) }
        val subscriptionsDeferred = async { httpGet(SUBSCRIPTIONS_URL, listOf("Cookie" to cookie)) }
        val offlineDeferred = async { fetchOfflineRanges() }

        val creditsText = creditsDeferred.await()
        val subscriptionsText = subscriptionsDeferred.await()
        val offline = offlineDeferred.await()

        val creditsRoot = JSONObject(creditsText)
        val credits = creditsRoot.optJSONObject("credits") ?: creditsRoot
        if (!credits.has("monthlyCredits") && !credits.has("monthlyCreditsGranted")) {
            throw CommandCodeServerException(0, "unerwartete Antwort")
        }
        val granted = credits.optDouble("monthlyCreditsGranted", 0.0)
        val remaining = credits.optDouble("monthlyCredits", 0.0)

        val subscriptionsRoot = JSONObject(subscriptionsText)
        val data: JSONObject? = when (val value = subscriptionsRoot.opt("data")) {
            is JSONObject -> value
            is org.json.JSONArray -> if (value.length() > 0) value.optJSONObject(0) else null
            else -> null
        }
        val planId = data?.optString("planId", "").orEmpty()
        val quantity = data?.optInt("quantity", 1) ?: 1
        val periodStart = parseIso(data?.optString("currentPeriodStart"))
        val periodEnd = parseIso(data?.optString("currentPeriodEnd"))

        val seats = if (planId.startsWith("teams") && quantity > 1) quantity else 1
        val tierTotal = (tierCredits[planId] ?: 0.0) * seats
        val cap = maxOf(granted, tierTotal)
        val used = (cap - remaining).coerceIn(0.0, if (cap > 0.0) cap else 0.0)
        val pct = if (cap > 0.0) (used / cap) * 100.0 else 0.0

        val now = System.currentTimeMillis()
        val pacePct = if (periodStart != null && periodEnd != null && periodEnd > periodStart) {
            onlinePct(offline, periodStart, periodEnd, now)
        } else {
            0.0
        }
        val offlineDays = if (periodStart != null && periodEnd != null) {
            (overlap(offline, periodStart, periodEnd) / DAY_MS).toInt()
        } else {
            0
        }

        Log.i(TAG, "Direkt von CommandCode: ${String.format("%.2f", pct)}% / Pace ${String.format("%.2f", pacePct)}%")
        CommandCodeUsage(planId, used, cap, pct, pacePct, offlineDays)
    }

    private suspend fun refreshCookieFromPc(context: Context): String {
        val fresh = fetchCookieFromPc(context)?.let { sanitizeCookie(it) }.orEmpty()
        if (fresh.isNotEmpty()) {
            writeCookie(context, fresh)
        }
        return fresh
    }

    private fun cookieIsFromPc(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COOKIE_FROM_PC, false)

    private fun markCookieFromPc(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COOKIE_FROM_PC, true).apply()
    }

    private suspend fun fetchCookieFromPc(context: Context): String? = withContext(Dispatchers.IO) {
        val secret = resolveSecret(context)
        if (secret.isEmpty()) {
            Log.w(TAG, "Kein PC-Sync-Secret – Cookie kann nicht beim PC geholt werden")
            return@withContext null
        }
        for (host in pcHosts(context)) {
            val cookie = requestCookie(host, secret)
            if (cookie != null) {
                Log.i(TAG, "Cookie vom PC bezogen: $host")
                rememberHost(context, host)
                return@withContext cookie
            }
        }
        val prefix = localIpv4()?.substringBeforeLast('.')
        if (prefix == null) return@withContext null
        val hit = scanSubnet(prefix)
        if (hit != null) {
            Log.i(TAG, "PC per Netzwerkscan gefunden: $hit")
            val cookie = requestCookie(hit, secret)
            if (cookie != null) rememberHost(context, hit)
            return@withContext cookie
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

    private fun requestCookie(host: String, secret: String): String? {
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
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                .optString("cookie").takeIf { it.isNotBlank() }
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
            Thread(runnable, "cc-host-scan").apply { isDaemon = true }
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

    private fun readCookie(context: Context): String = sanitizeCookie(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_COOKIE, "").orEmpty()
    )

    private fun writeCookie(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COOKIE, sanitizeCookie(value)).apply()
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

    private suspend fun fetchOfflineRanges(): List<Pair<Long, Long>> = withContext(Dispatchers.IO) {
        runCatching {
            val text = httpGet(
                "${Config.SUPABASE_URL}/rest/v1/commandcode_offline_ranges" +
                    "?owner=eq.paul&select=from_ts,to_ts&order=from_ts.asc",
                listOf(
                    "apikey" to Config.SUPABASE_PUBLISHABLE_KEY,
                    "Authorization" to "Bearer ${Config.SUPABASE_PUBLISHABLE_KEY}"
                )
            )
            val array = org.json.JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val entry = array.optJSONObject(index) ?: return@mapNotNull null
                val from = parseIso(entry.optString("from_ts"))
                val to = parseIso(entry.optString("to_ts"))
                if (from != null && to != null && to > from) from to to else null
            }
        }.getOrDefault(emptyList())
    }

    private fun httpGet(url: String, headers: List<Pair<String, String>>): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 8_000
            connection.readTimeout = 10_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val code = connection.responseCode
            if (code == 401) throw CookieExpiredException("HTTP 401")
            if (code == 403) throw CommandCodeBlockedException("HTTP 403")
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(body).optString("message") }.getOrNull().orEmpty()
                throw CommandCodeServerException(code, detail.ifEmpty { "HTTP $code" })
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun overlap(ranges: List<Pair<Long, Long>>, from: Long, to: Long): Long {
        if (to <= from) return 0L
        var total = 0L
        for ((rangeFrom, rangeTo) in ranges) {
            val start = maxOf(from, rangeFrom)
            val end = minOf(to, rangeTo)
            if (end > start) total += end - start
        }
        return total
    }

    private fun onlinePct(ranges: List<Pair<Long, Long>>, start: Long, end: Long, now: Long): Double {
        val span = end - start
        if (span <= 0L) return 0.0
        val clampedNow = now.coerceIn(start, end)
        val offlineNow = overlap(ranges, start, clampedNow)
        val offlineAll = overlap(ranges, start, end)
        val denominator = (span - offlineAll).toDouble()
        if (denominator <= 0.0) return 0.0
        return (((clampedNow - start - offlineNow).toDouble() / denominator) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrElse {
            runCatching {
                java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
            }.getOrNull()
        }
    }
}

object CommandCodeBarRenderer {

    private const val WIDTH = 480
    private const val HEIGHT = 48

    private val track = Color.parseColor("#33FFFFFF")
    private val inRange = Color.parseColor("#22C55E")
    private val over = Color.parseColor("#FFE53935")
    private val paceLine = Color.parseColor("#FFFF1744")
    private val paceEdge = Color.parseColor("#59000000")

    fun render(pct: Double, pacePct: Double): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = HEIGHT / 2.6f
        val height = HEIGHT.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), height), radius, radius, paint)

        val usedRight = (WIDTH * (pct.coerceIn(0.0, 100.0) / 100.0)).toFloat()
        val paceX = (WIDTH * (pacePct.coerceIn(0.0, 100.0) / 100.0)).toFloat()

        paint.color = if (pct > pacePct) over else inRange
        canvas.drawRoundRect(RectF(0f, 0f, usedRight, height), radius, radius, paint)

        val lineX = paceX.coerceIn(4f, WIDTH - 4f)
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paceEdge
            strokeWidth = 8f
        }
        canvas.drawLine(lineX, 0f, lineX, height, edge)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paceLine
            strokeWidth = 4f
        }
        canvas.drawLine(lineX, 0f, lineX, height, line)

        return bitmap
    }
}