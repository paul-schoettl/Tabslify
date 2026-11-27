package com.tabslify.tabs.tiktok

import android.content.Context
import androidx.core.content.edit
import com.tabslify.core.logging.Log
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object TikTokDump {

    private const val TAG = "TikTokDump"
    const val PREFS = "tiktok_dump"
    const val ORDNER = "tiktok/dump"
    const val MAX_DATEIEN = 200
    const val MAX_BYTES = 50L * 1024L * 1024L

    private const val KEY_AKTIV = "aktiv"
    private const val CSV_NAME = "dump.csv"
    private const val CSV_KOPF = "seq;source;role;bytes;status;zeit"
    private const val ENDUNG = ".bin"
    private const val MAX_UNTERFELD = 256 * 1024
    private const val PAKET_PREFIX = "tiktok-dump-"
    private const val MAX_PAKETE = 3

    private val lock = Any()
    private var sequenceNumber = 0
    private var oldStateChecked = false

    fun isActive(context: Context): Boolean = prefs(context).getBoolean(KEY_AKTIV, false)

    fun setActive(context: Context, active: Boolean) {
        prefs(context).edit { putBoolean(KEY_AKTIV, active) }
        clearOldState(context)
        Log.i(TAG, "Raw capture ${if (active) "on" else "off"}")
    }

    fun write(
        context: Context?,
        source: String,
        role: String,
        bytes: ByteArray,
        status: Int = -1
    ) {
        val app = context?.applicationContext ?: return
        if (!isActive(app)) return
        synchronized(lock) {
            runCatching {
                clearOldState(app)
                sequenceNumber++
                val number = sequenceNumber
                val target = folder(app)
                val fileName = sanitize(source) + "-" + sanitize(role) + "-" +
                    System.currentTimeMillis() + "-" + number + ENDUNG
                File(target, fileName).writeBytes(bytes)
                File(target, fileName.removeSuffix(ENDUNG) + ".txt")
                    .writeText(TikTokProto.describeFrame(bytes))
                csv(app, number, source, role, bytes.size, status)
                Log.d(TAG, "$source/$role ${bytes.size}B status=$status")
                trim(app)
            }.onFailure {
                Log.w(TAG, "Raw data not storable: ${it.message}")
            }
        }
    }

    fun folder(context: Context): File {
        val app = context.applicationContext
        val basis = app.getExternalFilesDir(null) ?: app.filesDir
        return File(basis, ORDNER).apply { if (!exists()) mkdirs() }
    }

    fun count(context: Context): Int = files(context).count { it.name.endsWith(ENDUNG) }

    fun sizeBytes(context: Context): Long = files(context).sumOf { it.length() }

    fun clear(context: Context): Int {
        val app = context.applicationContext
        synchronized(lock) {
            val alle = files(app)
            val weg = alle.count { it.name.endsWith(ENDUNG) }
            for (file in alle) runCatching { file.delete() }
            Log.i(TAG, "Rohdaten gelöscht: $weg Aufzeichnungen")
            return weg
        }
    }

    fun zip(context: Context): File? {
        val app = context.applicationContext
        val source = folder(app).listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?: return null
        if (source.isEmpty()) return null
        return synchronized(lock) {
            runCatching {
                val targetFile = File(
                    folder(app).parentFile,
                    PAKET_PREFIX + System.currentTimeMillis() + ".zip"
                )
                ZipOutputStream(targetFile.outputStream().buffered()).use { out ->
                    for (file in source) {
                        out.putNextEntry(ZipEntry(file.name))
                        file.inputStream().buffered().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                Log.i(TAG, "Bundled raw data: ${targetFile.absolutePath} (${source.size})")
                targetFile
            }.onFailure {
                Log.w(TAG, "Bundling failed: ${it.message}")
            }.getOrNull()
        }
    }

    private fun trim(context: Context) {
        val alle = files(context).filter { it.name.endsWith(ENDUNG) }
        var sum = sizeBytes(context)
        var zuViel = alle.size - MAX_DATEIEN
        for (file in alle) {
            if (zuViel <= 0 && sum <= MAX_BYTES) break
            val length = file.length()
            runCatching { file.delete() }
            runCatching { File(file.parentFile, file.nameWithoutExtension + ".txt").delete() }
            sum -= length
            zuViel--
        }
        trimBundles(context)
    }

    private fun trimBundles(context: Context) {
        val wurzel = folder(context).parentFile ?: return
        val pakete = wurzel.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PAKET_PREFIX) && it.name.endsWith(".zip") }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (file in pakete.dropLast(MAX_PAKETE)) {
            runCatching { file.delete() }
            Log.i(TAG, "Altes Paket entfernt: ${file.name}")
        }
    }

    private fun clearOldState(context: Context) {
        if (oldStateChecked) return
        oldStateChecked = true
        val alt = File(context.applicationContext.filesDir, ORDNER)
        runCatching {
            if (!alt.isDirectory) return@runCatching
            val content = alt.listFiles().orEmpty()
            for (file in content) entferne(file)
            runCatching { alt.delete() }
            Log.i(TAG, "Alter Rohdaten-Ordner im internen Speicher entfernt: ${content.size} Einträge")
        }.onFailure {
            Log.w(TAG, "Alter Ordner nicht zu removeItem: ${it.message}")
        }
    }

    private fun entferne(file: File) {
        if (file.isDirectory) {
            for (kind in file.listFiles().orEmpty()) entferne(kind)
            runCatching { file.delete() }
        } else {
            runCatching { file.delete() }
        }
    }

    private fun csv(
        context: Context,
        number: Int,
        source: String,
        role: String,
        bytes: Int,
        status: Int
    ) {
        val file = File(folder(context), CSV_NAME)
        if (!file.exists()) file.writeText(CSV_KOPF + "\n")
        file.appendText("$number;$source;$role;$bytes;$status;${System.currentTimeMillis()}\n")
    }

    private fun files(context: Context): List<File> =
        folder(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (character in raw) {
            val erlaubt = (character in 'a'..'z') || (character in 'A'..'Z') ||
                (character in '0'..'9') || character == '-' || character == '_'
            out.append(if (erlaubt) character else '_')
        }
        return out.toString().take(24).ifEmpty { "unbekannt" }
    }
}