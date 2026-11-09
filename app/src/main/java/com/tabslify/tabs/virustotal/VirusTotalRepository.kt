package com.tabslify.tabs.virustotal

import android.content.Context
import android.util.Base64
import com.tabslify.core.objects.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONObject
import kotlin.time.Duration.Companion.milliseconds

class VirusTotalRepository(private val context: Context) {

    private suspend fun proxy(payload: JSONObject, timeoutMs: Int = 30_000): Pair<Int, String?> =
        withContext(Dispatchers.IO) {
            val connection = Config.openApiProxyConnection(context, timeoutMs)
                ?: return@withContext 0 to null
            try {
                val body = JSONObject()
                    .put("action", "virustotal")
                    .put("payload", payload)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code == 200) connection.inputStream else connection.errorStream
                code to stream?.bufferedReader()?.use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }

    suspend fun lookupHash(hash: String): VirusTotalState = withContext(Dispatchers.IO) {
        try {
            val (code, text) = proxy(
                JSONObject().put("op", "file_report").put("id", hash)
            )
            if (code != 200 || text == null) {
                return@withContext VirusTotalState.Error("VirusTotal Fehler: HTTP $code")
            }
            val data = Json.parseToJsonElement(text).jsonObject["data"]?.jsonObject
            val stats = data?.get("attributes")?.jsonObject?.get("last_analysis_stats")?.jsonObject
            val id = data?.get("id")?.jsonPrimitive?.content ?: hash
            VirusTotalState.Result(parseStats(stats), "https://www.virustotal.com/gui/file/$id")
        } catch (e: Exception) {
            VirusTotalState.Error("Anfrage fehlgeschlagen: ${e.localizedMessage}")
        }
    }

    suspend fun scanUrl(url: String): VirusTotalState = withContext(Dispatchers.IO) {
        try {
            val urlId = Base64.encodeToString(
                url.toByteArray(),
                Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            )

            val existing = proxy(
                JSONObject().put("op", "url_report").put("id", urlId)
            )
            val existingText = existing.second
            if (existing.first == 200 && existingText != null) {
                val stats = Json.parseToJsonElement(existingText)
                    .jsonObject["data"]?.jsonObject
                    ?.get("attributes")?.jsonObject
                    ?.get("last_analysis_stats")?.jsonObject
                if (stats != null) {
                    return@withContext VirusTotalState.Result(
                        parseStats(stats),
                        "https://www.virustotal.com/gui/url/$urlId"
                    )
                }
            }

            val submit = proxy(
                JSONObject().put("op", "url_scan").put("url", url)
            )
            val submitText = submit.second
            if (submit.first != 200 || submitText == null) {
                return@withContext VirusTotalState.Error(
                    "Einreichen fehlgeschlagen: HTTP ${submit.first}"
                )
            }
            val analysisId = Json.parseToJsonElement(submitText)
                .jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                ?: return@withContext VirusTotalState.Error("Keine Analyse-ID erhalten")

            pollAnalysis(analysisId, "https://www.virustotal.com/gui/url/$urlId")
        } catch (e: Exception) {
            VirusTotalState.Error("Scan fehlgeschlagen: ${e.localizedMessage}")
        }
    }

    suspend fun scanFile(fileName: String, bytes: ByteArray): VirusTotalState = withContext(Dispatchers.IO) {
        try {
            val response = proxy(
                JSONObject()
                    .put("op", "file_scan")
                    .put("filename", fileName)
                    .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)),
                120_000
            )
            val responseText = response.second
            if (response.first != 200 || responseText == null) {
                return@withContext VirusTotalState.Error(
                    "Upload fehlgeschlagen: HTTP ${response.first}"
                )
            }
            val analysisId = Json.parseToJsonElement(responseText)
                .jsonObject["data"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                ?: return@withContext VirusTotalState.Error("Keine Analyse-ID erhalten")

            pollAnalysis(analysisId, null)
        } catch (e: Exception) {
            VirusTotalState.Error("Upload fehlgeschlagen: ${e.localizedMessage}")
        }
    }

    private suspend fun pollAnalysis(analysisId: String, permalinkFallback: String?): VirusTotalState {
        var delayMs = 2000L
        repeat(15) {
            val response = proxy(
                JSONObject().put("op", "analysis").put("id", analysisId)
            )
            val responseText = response.second
            if (response.first == 200 && responseText != null) {
                val attributes = Json.parseToJsonElement(responseText)
                    .jsonObject["data"]?.jsonObject?.get("attributes")?.jsonObject
                if (attributes?.get("status")?.jsonPrimitive?.content == "completed") {
                    val stats = attributes["stats"]?.jsonObject
                    val permalink = permalinkFallback
                        ?: "https://www.virustotal.com/gui/analyses/$analysisId"
                    return parseStats(stats).let { VirusTotalState.Result(it, permalink) }
                }
            }
            delay(delayMs.milliseconds)
            delayMs = (delayMs * 2).coerceAtMost(8000L)
        }
        return VirusTotalState.Error("Analyse dauert zu lange, bitte später erneut versuchen")
    }

    private fun parseStats(stats: JsonObject?): VirusTotalStats {
        fun value(key: String) = stats?.get(key)?.jsonPrimitive?.intOrNull ?: 0
        return VirusTotalStats(
            malicious = value("malicious"),
            suspicious = value("suspicious"),
            harmless = value("harmless"),
            undetected = value("undetected"),
            timeout = value("timeout")
        )
    }
}
