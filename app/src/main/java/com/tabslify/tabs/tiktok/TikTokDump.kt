package com.tabslify.tabs.tiktok

import android.content.Context
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
    private const val CSV_KOPF = "seq;quelle;rolle;bytes;status;zeit"
    private const val ENDUNG = ".bin"
    private const val MAX_UNTERFELD = 256 * 1024
    private const val PAKET_PREFIX = "tiktok-dump-"
    private const val MAX_PAKETE = 3

    private val sperre = Any()
    private var folge = 0
    private var alterStandGeprueft = false

    fun istAktiv(context: Context): Boolean = prefs(context).getBoolean(KEY_AKTIV, false)

    fun setzeAktiv(context: Context, aktiv: Boolean) {
        prefs(context).edit().putBoolean(KEY_AKTIV, aktiv).apply()
        raeumeAltenStand(context)
        Log.i(TAG, "Rohdaten-Aufzeichnung ${if (aktiv) "an" else "aus"}")
    }

    fun schreibe(
        context: Context?,
        quelle: String,
        rolle: String,
        bytes: ByteArray,
        status: Int = -1
    ) {
        val app = context?.applicationContext ?: return
        if (!istAktiv(app)) return
        synchronized(sperre) {
            runCatching {
                raeumeAltenStand(app)
                folge++
                val nummer = folge
                val ziel = ordner(app)
                val name = sanitize(quelle) + "-" + sanitize(rolle) + "-" +
                    System.currentTimeMillis() + "-" + nummer + ENDUNG
                File(ziel, name).writeBytes(bytes)
                File(ziel, name.removeSuffix(ENDUNG) + ".txt")
                    .writeText(TikTokProto.beschreibeFrame(bytes))
                csv(app, nummer, quelle, rolle, bytes.size, status)
                Log.d(TAG, "$quelle/$rolle ${bytes.size}B status=$status")
                kuerzen(app)
            }.onFailure {
                Log.w(TAG, "Rohdaten nicht speicherbar: ${it.message}")
            }
        }
    }

    fun ordner(context: Context): File {
        val app = context.applicationContext
        val basis = app.getExternalFilesDir(null) ?: app.filesDir
        return File(basis, ORDNER).apply { if (!exists()) mkdirs() }
    }

    fun anzahl(context: Context): Int = dateien(context).count { it.name.endsWith(ENDUNG) }

    fun groesse(context: Context): Long = dateien(context).sumOf { it.length() }

    fun leeren(context: Context): Int {
        val app = context.applicationContext
        synchronized(sperre) {
            val alle = dateien(app)
            val weg = alle.count { it.name.endsWith(ENDUNG) }
            for (datei in alle) runCatching { datei.delete() }
            Log.i(TAG, "Rohdaten gelöscht: $weg Aufzeichnungen")
            return weg
        }
    }

    fun zip(context: Context): File? {
        val app = context.applicationContext
        val quelle = ordner(app).listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?: return null
        if (quelle.isEmpty()) return null
        return synchronized(sperre) {
            runCatching {
                val ziel = File(
                    ordner(app).parentFile,
                    PAKET_PREFIX + System.currentTimeMillis() + ".zip"
                )
                ZipOutputStream(ziel.outputStream().buffered()).use { out ->
                    for (datei in quelle) {
                        out.putNextEntry(ZipEntry(datei.name))
                        datei.inputStream().buffered().use { it.copyTo(out) }
                        out.closeEntry()
                    }
                }
                Log.i(TAG, "Rohdaten gebündelt: ${ziel.absolutePath} (${quelle.size})")
                ziel
            }.onFailure {
                Log.w(TAG, "Bündeln fehlgeschlagen: ${it.message}")
            }.getOrNull()
        }
    }

    private fun kuerzen(context: Context) {
        val alle = dateien(context).filter { it.name.endsWith(ENDUNG) }
        var summe = groesse(context)
        var zuViel = alle.size - MAX_DATEIEN
        for (datei in alle) {
            if (zuViel <= 0 && summe <= MAX_BYTES) break
            val laenge = datei.length()
            runCatching { datei.delete() }
            runCatching { File(datei.parentFile, datei.nameWithoutExtension + ".txt").delete() }
            summe -= laenge
            zuViel--
        }
        paketeKuerzen(context)
    }

    private fun paketeKuerzen(context: Context) {
        val wurzel = ordner(context).parentFile ?: return
        val pakete = wurzel.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PAKET_PREFIX) && it.name.endsWith(".zip") }
            ?.sortedBy { it.lastModified() }
            ?: return
        for (datei in pakete.dropLast(MAX_PAKETE)) {
            runCatching { datei.delete() }
            Log.i(TAG, "Altes Paket entfernt: ${datei.name}")
        }
    }

    private fun raeumeAltenStand(context: Context) {
        if (alterStandGeprueft) return
        alterStandGeprueft = true
        val alt = File(context.applicationContext.filesDir, ORDNER)
        runCatching {
            if (!alt.isDirectory) return@runCatching
            val inhalt = alt.listFiles().orEmpty()
            for (datei in inhalt) entferne(datei)
            runCatching { alt.delete() }
            Log.i(TAG, "Alter Rohdaten-Ordner im internen Speicher entfernt: ${inhalt.size} Einträge")
        }.onFailure {
            Log.w(TAG, "Alter Ordner nicht zu entfernen: ${it.message}")
        }
    }

    private fun entferne(datei: File) {
        if (datei.isDirectory) {
            for (kind in datei.listFiles().orEmpty()) entferne(kind)
            runCatching { datei.delete() }
        } else {
            runCatching { datei.delete() }
        }
    }

    private fun csv(
        context: Context,
        nummer: Int,
        quelle: String,
        rolle: String,
        bytes: Int,
        status: Int
    ) {
        val datei = File(ordner(context), CSV_NAME)
        if (!datei.exists()) datei.writeText(CSV_KOPF + "\n")
        datei.appendText("$nummer;$quelle;$rolle;$bytes;$status;${System.currentTimeMillis()}\n")
    }

    private fun dateien(context: Context): List<File> =
        ordner(context).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sanitize(raw: String): String {
        val out = StringBuilder(raw.length)
        for (zeichen in raw) {
            val erlaubt = (zeichen in 'a'..'z') || (zeichen in 'A'..'Z') ||
                (zeichen in '0'..'9') || zeichen == '-' || zeichen == '_'
            out.append(if (erlaubt) zeichen else '_')
        }
        return out.toString().take(24).ifEmpty { "unbekannt" }
    }
}