package com.tabslify.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.tabslify.core.objects.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.math.max
import kotlin.math.min

data class CommandCodeUsage(
    val planId: String,
    val used: Double,
    val cap: Double,
    val pacePct: Double,
    val periodEnd: Long,
    val offlineDays: Int
) {
    val pct: Double get() = if (cap > 0.0) (used / cap) * 100.0 else 0.0
    val overPace: Boolean get() = pct > pacePct
}

object CommandCodeUsageRepository {

    private const val PREFS = "commandcode_usage"
    private const val KEY_COOKIE = "cookie"
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

    fun readCookie(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_COOKIE, "").orEmpty().trim()

    fun writeCookie(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_COOKIE, value.trim()).apply()
    }

    suspend fun load(context: Context): CommandCodeUsage = withContext(Dispatchers.IO) {
        val cookie = readCookie(context)
        if (cookie.isEmpty()) throw IllegalStateException("no-cookie")

        val creditsDeferred = async { httpGet(CREDITS_URL, listOf("Cookie" to cookie)) }
        val subscriptionsDeferred = async { httpGet(SUBSCRIPTIONS_URL, listOf("Cookie" to cookie)) }
        val offlineDeferred = async { fetchOfflineRanges(context) }

        val creditsText = creditsDeferred.await()
        val subscriptionsText = subscriptionsDeferred.await()
        val offline = offlineDeferred.await()

        val creditsRoot = JSONObject(creditsText)
        val credits = creditsRoot.optJSONObject("credits") ?: creditsRoot
        if (!credits.has("monthlyCredits") && !credits.has("monthlyCreditsGranted")) {
            throw IllegalStateException("unexpected-credits")
        }
        val granted = credits.optDouble("monthlyCreditsGranted", 0.0)
        val remaining = credits.optDouble("monthlyCredits", 0.0)

        val subscriptionsRoot = JSONObject(subscriptionsText)
        val data: JSONObject? = when (val value = subscriptionsRoot.opt("data")) {
            is JSONObject -> value
            is JSONArray -> if (value.length() > 0) value.optJSONObject(0) else null
            else -> null
        }
        val planId = data?.optString("planId", "").orEmpty()
        val quantity = data?.optInt("quantity", 1) ?: 1
        val periodStart = parseIso(data?.optString("currentPeriodStart"))
        val periodEnd = parseIso(data?.optString("currentPeriodEnd"))

        val seats = if (planId.startsWith("teams")) max(1, quantity) else 1
        val tierTotal = (tierCredits[planId] ?: 0.0) * seats
        val cap = max(granted, tierTotal)
        val used = (cap - remaining).coerceIn(0.0, if (cap > 0.0) cap else 0.0)

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

        CommandCodeUsage(
            planId = planId,
            used = used,
            cap = cap,
            pacePct = pacePct,
            periodEnd = periodEnd ?: 0L,
            offlineDays = offlineDays
        )
    }

    private fun fetchOfflineRanges(context: Context): List<Pair<Long, Long>> = runCatching {
        val url = "${Config.SUPABASE_URL}/rest/v1/commandcode_offline_ranges" +
            "?owner=eq.paul&select=from_ts,to_ts&order=from_ts.asc"
        val text = httpGet(
            url,
            listOf(
                "apikey" to Config.SUPABASE_PUBLISHABLE_KEY,
                "Authorization" to "Bearer ${Config.SUPABASE_PUBLISHABLE_KEY}"
            )
        )
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val entry = array.optJSONObject(index) ?: return@mapNotNull null
            val from = parseIso(entry.optString("from_ts"))
            val to = parseIso(entry.optString("to_ts"))
            if (from != null && to != null && to > from) Pair(from, to) else null
        }
    }.getOrDefault(emptyList())

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
            if (code !in 200..299) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP $code $errorBody")
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
            val start = max(from, rangeFrom)
            val end = min(to, rangeTo)
            if (end > start) total += end - start
        }
        return total
    }

    private fun onlinePct(ranges: List<Pair<Long, Long>>, start: Long, end: Long, now: Long): Double {
        val span = end - start
        if (span <= 0L) return 0.0
        val clampedNow = now.coerceIn(start, end)
        val elapsed = clampedNow - start
        val offlineNow = overlap(ranges, start, clampedNow)
        val offlineAll = overlap(ranges, start, end)
        val denominator = (span - offlineAll).toDouble()
        if (denominator <= 0.0) return 0.0
        return (((elapsed - offlineNow).toDouble() / denominator) * 100.0).coerceIn(0.0, 100.0)
    }

    private fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).toEpochMilli() }.getOrElse {
            runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}

object CommandCodeBarRenderer {

    private const val WIDTH = 480
    private const val HEIGHT = 84

    private val track = Color.parseColor("#33FFFFFF")
    private val orange = Color.parseColor("#FFFF8C42")
    private val red = Color.parseColor("#FFE53935")

    fun render(pct: Double, pacePct: Double): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val radius = HEIGHT / 3f
        val height = HEIGHT.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = track }
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), height), radius, radius, paint)

        val usedRight = (WIDTH * (pct.coerceIn(0.0, 100.0) / 100.0)).toFloat()
        val paceX = (WIDTH * (pacePct.coerceIn(0.0, 100.0) / 100.0)).toFloat()

        paint.color = orange
        canvas.drawRoundRect(RectF(0f, 0f, usedRight, height), radius, radius, paint)

        if (usedRight > paceX) {
            paint.color = red
            canvas.drawRect(RectF(paceX, 0f, usedRight, height), paint)
        }

        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = red
            strokeWidth = 6f
        }
        val lineX = paceX.coerceIn(3f, WIDTH - 3f)
        canvas.drawLine(lineX, 0f, lineX, height, line)

        return bitmap
    }
}
