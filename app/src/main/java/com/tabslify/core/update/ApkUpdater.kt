package com.tabslify.core.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import com.tabslify.core.logging.Log
import com.tabslify.core.objects.Config
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import kotlin.math.roundToInt

data class ApkPart(
    val name: String,
    val size: Long,
    val sha256: String?
)

data class ApkUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val totalSize: Long,
    val partCount: Int,
    val partSizeLimit: Long,
    val sha256: String,
    val timestamp: String?,
    val parts: List<ApkPart>
)

object ApkUpdater {

    private const val TAG = "ApkUpdater"
    private const val BUCKET = "tabslify-apks"
    private const val METADATA_NAME = "tabslify-latest.json"
    private const val PREFIX = "tabslify-"
    private const val PREFS = "tabslify_update_prefs"
    private const val KEY_VERSION_CODE = "downloaded_version_code"
    private const val KEY_SHA256 = "downloaded_sha256"
    private const val MAX_ATTEMPTS = 3
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_PART_SIZE = 45L * 1024 * 1024
    private val SHA256_PATTERN = Regex("^[0-9a-fA-F]{64}$")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun updateDir(context: Context): File =
        File(context.cacheDir, "tabslify_updates").apply { mkdirs() }

    fun updateFile(context: Context): File =
        File(updateDir(context), "update_tabslify.apk")

    suspend fun fetchUpdateInfo(): Result<ApkUpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            Log.i(TAG, "Lade Update-Metadaten $BUCKET/$METADATA_NAME")
            val bytes = try {
                Config.client.storage.from(BUCKET).downloadPublic(METADATA_NAME)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: RestException) {
                if (error.statusCode == 404 || error.statusCode == 400) {
                    Log.i(TAG, "Keine Update-Metadaten vorhanden (HTTP ${error.statusCode})")
                    return@runCatching null
                }
                throw error
            }
            if (bytes.isEmpty()) {
                Log.i(TAG, "Update-Metadaten leer, kein Update vorhanden")
                return@runCatching null
            }
            Log.i(TAG, "Update-Metadaten geladen (${bytes.size} Bytes)")
            val text = bytes.toString(Charsets.UTF_8)
            if (text.isBlank()) {
                Log.i(TAG, "Update-Metadaten leer, kein Update vorhanden")
                return@runCatching null
            }
            val json = JSONObject(text)
            val partsArray = json.optJSONArray("parts")
            if (json.length() == 0 || partsArray == null || partsArray.length() == 0) {
                Log.i(TAG, "Update-Metadaten ohne Parts, kein Update vorhanden")
                return@runCatching null
            }
            parseMetadata(json)
        }.onFailure {
            if (it is CancellationException) throw it
            Log.e(TAG, "Update-Metadaten konnten nicht geladen werden", it)
        }
    }

    private fun parseMetadata(json: JSONObject): ApkUpdateInfo {
        val app = json.optString("app")
        if (app != "tabslify") {
            throw IllegalArgumentException("Falsche App im Metadatensatz: $app")
        }
        val versionName = json.optString("versionName")
        val versionCode = json.optLong("versionCode", -1L)
        val totalSize = json.optLong("totalSize", -1L)
        val partCount = json.optInt("partCount", -1)
        val partSizeLimit = json.optLong("partSizeLimit", -1L)
        val sha256 = json.optString("sha256")
        if (versionName.isBlank() || versionCode <= 0L || totalSize <= 0L ||
            partCount <= 0 || partSizeLimit <= 0L || partSizeLimit > MAX_PART_SIZE ||
            !SHA256_PATTERN.matches(sha256)
        ) {
            throw IllegalArgumentException("Ungueltige Update-Metadaten")
        }
        val partsArray = json.optJSONArray("parts")
            ?: throw IllegalArgumentException("parts fehlt in den Update-Metadaten")
        if (partsArray.length() != partCount) {
            throw IllegalArgumentException(
                "parts-Anzahl ${partsArray.length()} passt nicht zu partCount $partCount"
            )
        }
        val parts = ArrayList<ApkPart>(partCount)
        var sizeSum = 0L
        for (index in 0 until partsArray.length()) {
            val partJson = partsArray.getJSONObject(index)
            val name = partJson.optString("name")
            val size = partJson.optLong("size", -1L)
            if (!name.startsWith(PREFIX) || size <= 0L || size > partSizeLimit) {
                throw IllegalArgumentException("Ungueltiger Part $index: $name")
            }
            if (index < partCount - 1 && size != partSizeLimit) {
                throw IllegalArgumentException(
                    "Part $index hat $size Bytes statt $partSizeLimit"
                )
            }
            sizeSum += size
            parts.add(ApkPart(name, size, partJson.optString("sha256").takeIf { it.isNotBlank() }))
        }
        if (sizeSum != totalSize) {
            throw IllegalArgumentException("Part-Groessen $sizeSum != totalSize $totalSize")
        }
        return ApkUpdateInfo(
            versionName = versionName,
            versionCode = versionCode,
            totalSize = totalSize,
            partCount = partCount,
            partSizeLimit = partSizeLimit,
            sha256 = sha256.lowercase(),
            timestamp = json.optString("timestamp").takeIf { it.isNotBlank() },
            parts = parts
        )
    }

    fun findDownloadedFile(context: Context, info: ApkUpdateInfo): File? {
        val file = updateFile(context)
        if (!file.exists() || file.length() != info.totalSize) return null
        val prefs = prefs(context)
        if (prefs.getLong(KEY_VERSION_CODE, -1L) != info.versionCode) return null
        if (!info.sha256.equals(prefs.getString(KEY_SHA256, null), ignoreCase = true)) return null
        return file
    }

    suspend fun downloadAndAssemble(
        context: Context,
        info: ApkUpdateInfo,
        onProgress: (downloaded: Long, total: Long, partIndex: Int, partCount: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = updateDir(context)
            dir.listFiles()?.forEach { stale ->
                if (stale.isFile && stale.name.endsWith(".tmp")) {
                    if (stale.delete()) Log.i(TAG, "Alte Temp-Datei geloescht: ${stale.name}")
                }
            }
            val temp = File(dir, "update_tabslify.apk.tmp")
            if (temp.exists() && !temp.delete()) {
                throw IllegalStateException("Alte Temp-APK konnte nicht geloescht werden")
            }
            Log.i(
                TAG,
                "Download startet: ${info.partCount} Teile, ${info.totalSize} Bytes, " +
                    "Teilgroesse ${info.partSizeLimit} Bytes, " +
                    "Version ${info.versionName} (${info.versionCode})"
            )
            var downloaded = 0L
            try {
                temp.outputStream().use { output ->
                    info.parts.forEachIndexed { index, part ->
                        currentCoroutineContext().ensureActive()
                        val bytes = downloadPart(part, index, info.partCount)
                        if (bytes.size.toLong() != part.size) {
                            throw IllegalStateException(
                                "Teil ${part.name}: ${bytes.size} Bytes statt ${part.size}"
                            )
                        }
                        part.sha256?.let { expected ->
                            val actual = sha256Bytes(bytes)
                            if (!actual.equals(expected, ignoreCase = true)) {
                                throw IllegalStateException(
                                    "Teil ${part.name}: SHA-256 $actual statt $expected"
                                )
                            }
                        }
                        output.write(bytes)
                        downloaded += bytes.size
                        onProgress(downloaded, info.totalSize, index + 1, info.partCount)
                        Log.i(
                            TAG,
                            "Teil ${index + 1}/${info.partCount} geladen: ${part.name} " +
                                "(${bytes.size} Bytes, gesamt $downloaded/${info.totalSize})"
                        )
                    }
                    output.flush()
                }
                if (temp.length() != info.totalSize) {
                    throw IllegalStateException(
                        "Gesamtgroesse ${temp.length()} statt ${info.totalSize}"
                    )
                }
                val actualSha = sha256File(temp)
                if (!actualSha.equals(info.sha256, ignoreCase = true)) {
                    throw IllegalStateException(
                        "SHA-256 der APK $actualSha statt ${info.sha256}"
                    )
                }
                val target = updateFile(context)
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Alte APK konnte nicht ersetzt werden")
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                prefs(context).edit {
                    putLong(KEY_VERSION_CODE, info.versionCode)
                    putString(KEY_SHA256, info.sha256)
                }
                Log.i(TAG, "Download abgeschlossen, SHA-256 verifiziert: $actualSha")
                target
            } catch (error: Throwable) {
                Log.e(TAG, "Download/Zusammensetzen fehlgeschlagen", error)
                if (temp.exists()) temp.delete()
                throw error
            }
        }
    }

    private suspend fun downloadPart(part: ApkPart, index: Int, partCount: Int): ByteArray {
        var attempt = 1
        while (true) {
            try {
                Log.i(TAG, "Lade Teil ${index + 1}/$partCount: ${part.name} (Versuch $attempt)")
                return Config.client.storage.from(BUCKET).downloadPublic(part.name)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                if (attempt >= MAX_ATTEMPTS) {
                    Log.e(
                        TAG,
                        "Teil ${part.name} nach $attempt Versuchen endgueltig fehlgeschlagen",
                        error
                    )
                    throw error
                }
                Log.w(
                    TAG,
                    "Teil ${part.name} Versuch $attempt fehlgeschlagen: ${error.message}",
                    error
                )
                delay(1000L * attempt)
                attempt++
            }
        }
    }

    suspend fun deleteRemote(info: ApkUpdateInfo): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!Config.hasAuthSession()) {
                Log.w(TAG, "Keine Auth-Session, Remote-Parts bleiben erhalten")
                return@withContext false
            }
            val names = info.parts.map { it.name } + METADATA_NAME
            Config.client.storage.from(BUCKET).delete(names)
            Log.i(TAG, "Remote geloescht: ${info.partCount} Parts + Metadaten")
            true
        } catch (error: Exception) {
            Log.w(TAG, "Remote-Loeschung fehlgeschlagen", error)
            false
        }
    }

    fun deleteLocalFile(context: Context) {
        val file = updateFile(context)
        if (file.exists() && file.delete()) Log.i(TAG, "Lokale APK geloescht")
    }

    fun canInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(context: Context) {
        Log.w(TAG, "REQUEST_INSTALL_PACKAGES nicht erlaubt, oeffne Einstellungen")
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = "package:${context.packageName}".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun installApk(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "Installation abgebrochen: APK fehlt oder ist leer")
            return false
        }
        if (!canInstall(context)) {
            openInstallPermission(context)
            return false
        }
        return try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            Log.i(TAG, "Installer gestartet fuer ${file.name}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Installer konnte nicht gestartet werden", error)
            false
        }
    }

    fun formatSize(bytes: Long): String {
        val mb = (bytes.toDouble() / (1024.0 * 1024.0)).roundToInt()
        return "${mb.coerceAtLeast(1)} MB"
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Bytes(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
