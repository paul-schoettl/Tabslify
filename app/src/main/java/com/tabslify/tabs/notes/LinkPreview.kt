package com.tabslify.tabs.notes

import android.content.Context
import android.text.Html
import androidx.core.content.edit
import com.tabslify.core.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.Charset

@Serializable
data class LinkPreviewRecord(
    val url: String,
    val title: String? = null,
    val description: String? = null,
    val imageUrl: String? = null,
    val siteName: String? = null,
    val fetchedAt: Long = 0L,
    val failed: Boolean = false
)

private const val PREFS_NAME = "notes_prefs"
private const val PREFS_KEY = "link_previews"
private const val PREVIEW_TTL_MS = 7L * 24 * 60 * 60 * 1000
private const val FAILED_TTL_MS = 60L * 60 * 1000
private const val MAX_PREVIEWS = 200
private const val MAX_BODY_BYTES = 256 * 1024
private const val TIMEOUT_MS = 2000
private const val MAX_LINKS = 10
private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

private val previewJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

private val urlPattern = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
private const val URL_TRAILING_CHARS = ".,;:!?)]}\"'"

private val metaTagPattern = Regex("<meta\\s[^>]*>", RegexOption.IGNORE_CASE)
private val titlePattern = Regex(
    "<title[^>]*>(.*?)</title>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val attributePattern = Regex("""([A-Za-z:_.-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+))""")
private val charsetPattern = Regex("""(?i)charset=["']?([^;"'\s]+)""")

fun linkHost(url: String): String =
    runCatching { URI(url).host }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?.removePrefix("www.")
        ?: url

fun LinkPreviewRecord.isStale(now: Long = System.currentTimeMillis()): Boolean =
    now - fetchedAt > if (failed) FAILED_TTL_MS else PREVIEW_TTL_MS

fun extractUrls(text: String, limit: Int = MAX_LINKS): List<String> {
    if (text.isBlank() || limit <= 0) return emptyList()
    val urls = LinkedHashSet<String>()
    urlPattern.findAll(text).forEach { match ->
        val normalized = normalizeUrl(match.value) ?: return@forEach
        urls += normalized
        if (urls.size >= limit) return urls.toList()
    }
    return urls.toList()
}

private fun normalizeUrl(raw: String): String? {
    val cleaned = raw.trimEnd { it in URL_TRAILING_CHARS }
    if (cleaned.isEmpty()) return null
    val withScheme = if (cleaned.startsWith("www.", ignoreCase = true)) "https://$cleaned" else cleaned
    if (!withScheme.startsWith("http://", ignoreCase = true) &&
        !withScheme.startsWith("https://", ignoreCase = true)
    ) {
        return null
    }
    return runCatching { URI(withScheme).normalize().toString() }.getOrNull() ?: withScheme
}

fun loadLinkPreviews(context: Context): Map<String, LinkPreviewRecord> {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(PREFS_KEY, null) ?: return emptyMap()
    val records = runCatching { previewJson.decodeFromString<List<LinkPreviewRecord>>(raw) }
        .getOrNull() ?: return emptyMap()
    return records.associateBy { it.url }
}

fun saveLinkPreviews(context: Context, records: Collection<LinkPreviewRecord>) {
    if (records.isEmpty()) return
    val merged = loadLinkPreviews(context).toMutableMap()
    records.forEach { merged[it.url] = it }
    val now = System.currentTimeMillis()
    val pruned = merged.values
        .filter { now - it.fetchedAt <= PREVIEW_TTL_MS }
        .sortedByDescending { it.fetchedAt }
        .take(MAX_PREVIEWS)
    val encoded = runCatching { previewJson.encodeToString(pruned) }.getOrNull() ?: return
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit(commit = true) { putString(PREFS_KEY, encoded) }
}

suspend fun fetchLinkPreview(url: String): LinkPreviewRecord = withContext(Dispatchers.IO) {
    val now = System.currentTimeMillis()
    try {
        val meta = fetchMeta(url)
        if (meta == null) {
            LinkPreviewRecord(url = url, fetchedAt = now, failed = true)
        } else {
            LinkPreviewRecord(
                url = url,
                title = meta.title,
                description = meta.description,
                imageUrl = meta.imageUrl,
                siteName = meta.siteName,
                fetchedAt = now
            )
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        AppLog.w("NoteLinkPreview", "Vorschau fehlgeschlagen: ${error.message}")
        LinkPreviewRecord(url = url, fetchedAt = now, failed = true)
    }
}

private data class Meta(
    val title: String?,
    val description: String?,
    val imageUrl: String?,
    val siteName: String?
)

private fun fetchMeta(url: String): Meta? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = TIMEOUT_MS
        readTimeout = TIMEOUT_MS
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", USER_AGENT)
        setRequestProperty("Accept", "text/html,application/xhtml+xml")
        setRequestProperty("Accept-Language", "de-DE,de;q=0.9,en;q=0.8")
    }
    try {
        if (connection.responseCode !in 200..299) return null
        val contentType = connection.contentType.orEmpty()
        if (contentType.isNotEmpty() &&
            !contentType.startsWith("text/html", ignoreCase = true) &&
            !contentType.startsWith("application/xhtml", ignoreCase = true)
        ) {
            return null
        }
        val charset = charsetFrom(contentType)
        val html = connection.inputStream.use { stream ->
            val buffer = ByteArray(MAX_BODY_BYTES)
            var total = 0
            while (total < MAX_BODY_BYTES) {
                val read = stream.read(buffer, total, MAX_BODY_BYTES - total)
                if (read <= 0) break
                total += read
            }
            String(buffer, 0, total, charset)
        }
        return parseMeta(html, connection.url.toString())
    } finally {
        connection.disconnect()
    }
}

private fun charsetFrom(contentType: String): Charset {
    val name = charsetPattern.find(contentType)?.groupValues?.getOrNull(1) ?: return Charsets.UTF_8
    return runCatching { Charset.forName(name) }.getOrDefault(Charsets.UTF_8)
}

private fun parseMeta(html: String, baseUrl: String): Meta? {
    val values = mutableMapOf<String, String>()
    metaTagPattern.findAll(html).take(400).forEach { tag ->
        val attributes = attributePattern.findAll(tag.value).associate { match ->
            val value = match.groupValues.drop(2).firstOrNull { it.isNotEmpty() }.orEmpty()
            match.groupValues[1].lowercase() to value
        }
        val key = (attributes["property"] ?: attributes["name"])?.lowercase()
        val content = attributes["content"]
        if (key != null && !content.isNullOrBlank()) values.putIfAbsent(key, content)
    }
    val title = decodeHtml(
        values["og:title"] ?: values["twitter:title"] ?: titlePattern.find(html)?.groupValues?.getOrNull(1)
    )?.take(300)
    val description = decodeHtml(
        values["og:description"] ?: values["twitter:description"] ?: values["description"]
    )?.take(600)
    val siteName = decodeHtml(values["og:site_name"] ?: values["application-name"])?.take(120)
    val rawImage = values["og:image"]
        ?: values["og:image:url"]
        ?: values["og:image:secure_url"]
        ?: values["twitter:image"]
        ?: values["twitter:image:src"]
    val imageUrl = rawImage?.let { resolveImageUrl(baseUrl, it) }
    if (title.isNullOrBlank() && description.isNullOrBlank() && imageUrl == null) return null
    return Meta(
        title = title?.takeIf { it.isNotBlank() },
        description = description?.takeIf { it.isNotBlank() },
        imageUrl = imageUrl,
        siteName = siteName?.takeIf { it.isNotBlank() }
    )
}

private fun decodeHtml(raw: String?): String? {
    if (raw == null) return null
    val decoded = runCatching { Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString() }
        .getOrDefault(raw)
    return decoded.replace(Regex("\\s+"), " ").trim().ifBlank { null }
}

private fun resolveImageUrl(baseUrl: String, raw: String): String? {
    val decoded = decodeHtml(raw) ?: return null
    val resolved = runCatching { URI(baseUrl).resolve(decoded).toString() }.getOrNull() ?: return null
    return resolved.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    }
}
