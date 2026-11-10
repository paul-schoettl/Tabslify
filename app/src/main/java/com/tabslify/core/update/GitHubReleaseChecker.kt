package com.tabslify.core.update

import com.tabslify.BuildConfig
import com.tabslify.core.logging.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class GitHubRelease(
    val versionName: String,
    val htmlUrl: String
)

object GitHubReleaseChecker {

    private const val TAG = "GitHubReleaseChecker"
    private const val API_URL =
        "https://api.github.com/repos/paul-schoettl/Tabslify/releases?per_page=20"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    suspend fun fetchLatest(): Result<GitHubRelease?> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Tabslify/${BuildConfig.VERSION_NAME}")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    throw IllegalStateException("GitHub HTTP ${connection.responseCode}")
                }
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val releases = JSONArray(text)
                var latest: GitHubRelease? = null
                for (index in 0 until releases.length()) {
                    val json = releases.optJSONObject(index) ?: continue
                    if (json.optBoolean("draft")) continue
                    val tag = json.optString("tag_name").trim()
                    val url = json.optString("html_url").trim()
                    if (tag.isBlank() || url.isBlank()) continue
                    val release = GitHubRelease(tag, url)
                    if (latest == null || compareVersions(release.versionName, latest.versionName) > 0) {
                        latest = release
                    }
                }
                latest
            } finally {
                connection.disconnect()
            }
        }.onFailure {
            Log.w(TAG, "GitHub-Releases konnten nicht geladen werden", it)
        }
    }

    fun isNewer(candidate: String, current: String): Boolean =
        compareVersions(candidate, current) > 0

    private fun compareVersions(left: String, right: String): Int {
        val (leftCore, leftPre) = splitVersion(left)
        val (rightCore, rightPre) = splitVersion(right)
        val core = compareTokens(leftCore, rightCore)
        if (core != 0) return core
        if (leftPre == null && rightPre == null) return 0
        if (leftPre == null) return 1
        if (rightPre == null) return -1
        return compareTokens(leftPre, rightPre)
    }

    private fun splitVersion(raw: String): Pair<List<String>, List<String>?> {
        val value = raw.trim().removePrefix("v").removePrefix("V")
        val dash = value.indexOf('-')
        val core = if (dash >= 0) value.substring(0, dash) else value
        val pre = if (dash >= 0) value.substring(dash + 1) else null
        return core.split('.').filter { it.isNotEmpty() } to pre?.let(::tokenize)
    }

    private fun tokenize(value: String): List<String> =
        Regex("[0-9]+|[A-Za-z]+").findAll(value).map { it.value.lowercase() }.toList()

    private fun compareTokens(left: List<String>, right: List<String>): Int {
        val size = maxOf(left.size, right.size)
        for (index in 0 until size) {
            val a = left.getOrNull(index) ?: "0"
            val b = right.getOrNull(index) ?: "0"
            val aNumber = a.toLongOrNull()
            val bNumber = b.toLongOrNull()
            val result = when {
                aNumber != null && bNumber != null -> aNumber.compareTo(bNumber)
                aNumber != null -> -1
                bNumber != null -> 1
                else -> a.compareTo(b)
            }
            if (result != 0) return result
        }
        return 0
    }
}
