package com.tabslify.tabs.school.lexikon

import android.content.Context
import org.json.JSONArray
import java.io.File

object LexikonStore {
    private const val ORDNER = "lexikon"
    private const val INDEX_DATEI = "index.json"
    private const val IMPORT_ORDNER = "imports"

    private fun ordner(context: Context): File =
        File(context.applicationContext.filesDir, ORDNER).apply { mkdirs() }

    private fun dateiFuer(context: Context, id: String): File =
        File(ordner(context), "$id.json")

    private fun indexDatei(context: Context): File = File(ordner(context), INDEX_DATEI)

    fun ladeIndex(context: Context): List<WortschatzMeta> {
        val gespeichert = try {
            val datei = indexDatei(context)
            if (!datei.exists()) return synchronisiereIndex(context)
            val arr = JSONArray(datei.readText())
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let(WortschatzMeta::ausJson)
            }
        } catch (_: Exception) {
            return synchronisiereIndex(context)
        }
        val vorhanden = vorhandeneDateien(context).map { it.nameWithoutExtension }.toSet()
        return if (gespeichert.map { it.id }.toSet() == vorhanden) gespeichert
        else synchronisiereIndex(context)
    }

    fun synchronisiereIndex(context: Context): List<WortschatzMeta> {
        val metas = vorhandeneDateien(context).mapNotNull { datei ->
            runCatching { Wortschatz.ausJson(org.json.JSONObject(datei.readText())).meta() }.getOrNull()
        }.sortedByDescending { it.angelegt }
        schreibeIndex(context, metas)
        return metas
    }

    fun ladeWortschatz(context: Context, id: String): Wortschatz? = runCatching {
        Wortschatz.ausJson(org.json.JSONObject(dateiFuer(context, id).readText()))
    }.getOrNull()

    fun ladeAlle(context: Context): List<Wortschatz> =
        ladeIndex(context).mapNotNull { ladeWortschatz(context, it.id) }

    fun speichere(context: Context, wortschatz: Wortschatz): List<WortschatzMeta> {
        val ziel = dateiFuer(context, wortschatz.id)
        val temp = File(ziel.parentFile, "${ziel.name}.tmp")
        temp.writeText(wortschatz.toJson().toString())
        if (!temp.renameTo(ziel)) {
            ziel.writeText(temp.readText())
            temp.delete()
        }
        return synchronisiereIndex(context)
    }

    fun loesche(context: Context, id: String): List<WortschatzMeta> {
        dateiFuer(context, id).delete()
        return synchronisiereIndex(context)
    }

    fun ersetzeEintrag(
        context: Context,
        wortschatzId: String,
        eintrag: LexikonEintrag
    ): Wortschatz? {
        val wortschatz = ladeWortschatz(context, wortschatzId) ?: return null
        val neu = wortschatz.copy(
            eintraege = wortschatz.eintraege.map { if (it.id == eintrag.id) eintrag else it }
        )
        speichere(context, neu)
        return neu
    }

    fun benenne(context: Context, wortschatzId: String, name: String): Wortschatz? {
        val wortschatz = ladeWortschatz(context, wortschatzId) ?: return null
        val neu = wortschatz.copy(name = name.trim().ifEmpty { wortschatz.name })
        speichere(context, neu)
        return neu
    }

    fun speichereRoheSeite(context: Context, importId: String, seite: Int, roh: String) {
        val ziel = File(importOrdner(context, importId), "seite_$seite.json")
        ziel.writeText(roh)
    }

    fun ladeRoheSeite(context: Context, importId: String, seite: Int): String? {
        val datei = File(importOrdner(context, importId), "seite_$seite.json")
        return if (datei.exists()) datei.readText() else null
    }

    fun ladeRoheSeiten(context: Context, importId: String): Map<Int, String> {
        val ordner = importOrdner(context, importId)
        if (!ordner.exists()) return emptyMap()
        return (ordner.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.startsWith("seite_") }
            .mapNotNull { datei ->
                val nummer = datei.nameWithoutExtension.removePrefix("seite_").toIntOrNull()
                nummer?.let { it to datei.readText() }
            }
            .toMap()
    }

    fun loescheImport(context: Context, importId: String) {
        importOrdner(context, importId).deleteRecursively()
    }

    fun neuerImportOrdner(context: Context): String {
        val id = "import_${System.currentTimeMillis()}"
        importOrdner(context, id)
        return id
    }

    fun neuerWortschatzId(): String = "ws_${System.currentTimeMillis()}"

    private fun importOrdner(context: Context, importId: String): File =
        File(ordner(context), "$IMPORT_ORDNER/$importId").apply { mkdirs() }

    private fun vorhandeneDateien(context: Context): List<File> =
        (ordner(context).listFiles() ?: emptyArray()).filter {
            it.isFile && it.extension == "json" && it.name != INDEX_DATEI
        }

    private fun schreibeIndex(context: Context, metas: List<WortschatzMeta>) {
        val ziel = indexDatei(context)
        val temp = File(ziel.parentFile, "${ziel.name}.tmp")
        temp.writeText(JSONArray().apply { metas.forEach { put(it.toJson()) } }.toString())
        if (!temp.renameTo(ziel)) {
            ziel.writeText(temp.readText())
            temp.delete()
        }
    }
}
