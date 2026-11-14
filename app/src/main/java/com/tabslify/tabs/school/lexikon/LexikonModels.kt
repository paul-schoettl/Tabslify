package com.tabslify.tabs.school.lexikon

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

object LexikonNavigation {
    var openRequested by mutableStateOf(false)
}

enum class WortschatzTyp(val schluessel: String) {
    REGISTER("register"),
    LERNWORTSCHATZ("lernwortschatz");

    companion object {
        fun ausSchluessel(wert: String?): WortschatzTyp =
            entries.firstOrNull { it.schluessel == wert } ?: REGISTER
    }
}

data class LexikonEintrag(
    val id: Int,
    val stichwort: String,
    val bedeutungen: List<String> = emptyList(),
    val wortart: String? = null,
    val formen: List<String> = emptyList(),
    val verwandtschaften: List<String> = emptyList(),
    val quelle: String? = null,
    val seite: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("stichwort", stichwort)
        put("bedeutungen", JSONArray(bedeutungen))
        if (wortart != null) put("wortart", wortart)
        put("formen", JSONArray(formen))
        put("verwandtschaften", JSONArray(verwandtschaften))
        if (quelle != null) put("quelle", quelle)
        if (seite != null) put("seite", seite)
    }

    companion object {
        fun ausJson(o: JSONObject): LexikonEintrag = LexikonEintrag(
            id = o.optInt("id", 0),
            stichwort = o.optString("stichwort").trim(),
            bedeutungen = o.textListe("bedeutungen"),
            wortart = o.optString("wortart").trim().takeIf { it.isNotEmpty() },
            formen = o.textListe("formen"),
            verwandtschaften = o.textListe("verwandtschaften"),
            quelle = o.optString("quelle").trim().takeIf { it.isNotEmpty() },
            seite = if (o.has("seite") && !o.isNull("seite")) o.optInt("seite") else null
        )
    }
}

data class WortschatzMeta(
    val id: String,
    val name: String,
    val typ: WortschatzTyp,
    val anzahl: Int,
    val angelegt: Long,
    val sprache: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("typ", typ.schluessel)
        put("anzahl", anzahl)
        put("angelegt", angelegt)
        put("sprache", sprache)
    }

    companion object {
        fun ausJson(o: JSONObject): WortschatzMeta = WortschatzMeta(
            id = o.optString("id"),
            name = o.optString("name"),
            typ = WortschatzTyp.ausSchluessel(o.optString("typ")),
            anzahl = o.optInt("anzahl", 0),
            angelegt = o.optLong("angelegt", 0L),
            sprache = o.optString("sprache", "Latein")
        )
    }
}

data class Wortschatz(
    val id: String,
    val name: String,
    val typ: WortschatzTyp,
    val sprache: String = "Latein",
    val zielsprache: String = "Deutsch",
    val angelegt: Long = System.currentTimeMillis(),
    val eintraege: List<LexikonEintrag> = emptyList(),
    val auffaelligkeiten: List<String> = emptyList()
) {
    fun meta(): WortschatzMeta = WortschatzMeta(
        id = id,
        name = name,
        typ = typ,
        anzahl = eintraege.size,
        angelegt = angelegt,
        sprache = sprache
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("typ", typ.schluessel)
        put("sprache", sprache)
        put("zielsprache", zielsprache)
        put("angelegt", angelegt)
        put("eintraege", JSONArray().apply { eintraege.forEach { put(it.toJson()) } })
        put("auffaelligkeiten", JSONArray(auffaelligkeiten))
    }

    companion object {
        fun ausJson(o: JSONObject): Wortschatz = Wortschatz(
            id = o.optString("id"),
            name = o.optString("name"),
            typ = WortschatzTyp.ausSchluessel(o.optString("typ")),
            sprache = o.optString("sprache", "Latein"),
            zielsprache = o.optString("zielsprache", "Deutsch"),
            angelegt = o.optLong("angelegt", 0L),
            eintraege = (o.optJSONArray("eintraege") ?: JSONArray()).let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let(LexikonEintrag::ausJson)
                }
            },
            auffaelligkeiten = o.optJSONArray("auffaelligkeiten")?.zuListe() ?: emptyList()
        )
    }
}

fun JSONArray.zuListe(): List<String> = (0 until length()).mapNotNull { i ->
    val wert = optString(i).trim()
    wert.takeIf { it.isNotEmpty() }
}

fun JSONObject.textListe(schluessel: String): List<String> = when (val wert = opt(schluessel)) {
    is JSONArray -> wert.zuListe()
    is String -> wert.split(';', '|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    else -> emptyList()
}

fun JSONObject.optText(schluessel: String): String? =
    optString(schluessel).trim().takeIf { it.isNotEmpty() }
