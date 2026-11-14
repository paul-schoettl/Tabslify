package com.tabslify.tabs.school.lexikon

import org.json.JSONArray
import org.json.JSONObject

data class LexikonSeitenErgebnis(
    val eintraege: List<LexikonEintrag>,
    val quelle: String?,
    val roh: String,
    val fehler: String?
) {
    val leer: Boolean get() = eintraege.isEmpty()
}

const val QUELLE_REGISTER = "Grundwortschatz"

fun parseLexikonSeite(roh: String, seite: Int, fallbackQuelle: String?): LexikonSeitenErgebnis {
    val bereinigt = roh.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    if (bereinigt.isEmpty()) {
        return LexikonSeitenErgebnis(emptyList(), fallbackQuelle, roh, "leere Antwort")
    }
    return try {
        val objekt = bereich(bereinigt, '{', '}')
        if (objekt != null) {
            val o = JSONObject(objekt)
            val arr = o.optJSONArray("eintraege")
                ?: o.optJSONArray("entries")
                ?: o.optJSONArray("vokabeln")
            if (arr != null) {
                val quelle = quelleAus(
                    o.optText("quelle") ?: o.optText("lernwortschatz") ?: o.optText("lektion"),
                    fallbackQuelle
                )
                return LexikonSeitenErgebnis(leseEintraege(arr, quelle, seite), quelle, roh, null)
            }
        }
        val arr = bereich(bereinigt, '[', ']')?.let(::JSONArray)
            ?: return LexikonSeitenErgebnis(emptyList(), fallbackQuelle, roh, "kein JSON-Array gefunden")
        LexikonSeitenErgebnis(leseEintraege(arr, fallbackQuelle, seite), fallbackQuelle, roh, null)
    } catch (e: Exception) {
        LexikonSeitenErgebnis(emptyList(), fallbackQuelle, roh, e.message ?: "Parse-Fehler")
    }
}

fun quelleAus(wert: String?, fallback: String?): String? {
    val text = wert?.trim().orEmpty()
    if (text.isEmpty()) return fallback
    if (text.none { it.isLetter() } && text.any { it.isDigit() }) return "Lernwortschatz $text"
    return text
}

fun verschmelzeLexikon(eintraege: List<LexikonEintrag>): List<LexikonEintrag> {
    val gruppen = LinkedHashMap<String, LexikonEintrag>()
    eintraege.forEach { eintrag ->
        val schluessel = normalisiereLexikon(eintrag.stichwort)
        if (schluessel.isEmpty()) return@forEach
        val vorhanden = gruppen[schluessel]
        gruppen[schluessel] = if (vorhanden == null) eintrag else vorhanden.copy(
            bedeutungen = (vorhanden.bedeutungen + eintrag.bedeutungen).distinct(),
            formen = (vorhanden.formen + eintrag.formen).distinct(),
            verwandtschaften = (vorhanden.verwandtschaften + eintrag.verwandtschaften).distinct(),
            wortart = vorhanden.wortart ?: eintrag.wortart,
            quelle = vorhanden.quelle ?: eintrag.quelle,
            seite = vorhanden.seite ?: eintrag.seite
        )
    }
    return gruppen.values.mapIndexed { index, eintrag -> eintrag.copy(id = index) }
}

private fun bereich(text: String, start: Char, ende: Char): String? {
    val von = text.indexOf(start)
    val bis = text.lastIndexOf(ende)
    return if (von >= 0 && bis > von) text.substring(von, bis + 1) else null
}

private fun leseEintraege(arr: JSONArray, quelle: String?, seite: Int): List<LexikonEintrag> =
    (0 until arr.length()).mapNotNull { index ->
        val o = arr.optJSONObject(index) ?: return@mapNotNull null
        val stichwort = erstesText(o, "stichwort", "latein", "lemma", "wort")
            ?: return@mapNotNull null
        LexikonEintrag(
            id = index,
            stichwort = stichwort,
            bedeutungen = erstesListe(o, "bedeutungen", "bedeutung", "deutsch", "uebersetzung"),
            wortart = erstesText(o, "wortart", "pos"),
            formen = erstesListe(o, "formen", "stammformen", "genitiv", "genus"),
            verwandtschaften = erstesListe(
                o,
                "verwandtschaften",
                "wortverwandtschaften",
                "verwandt",
                "herkunft"
            ),
            quelle = quelleAus(erstesText(o, "quelle", "lernwortschatz", "lektion"), quelle),
            seite = seite
        )
    }

private fun erstesText(o: JSONObject, vararg schluessel: String): String? {
    schluessel.forEach { key ->
        o.optText(key)?.let { return it }
    }
    return null
}

private fun erstesListe(o: JSONObject, vararg schluessel: String): List<String> {
    schluessel.forEach { key ->
        val liste = o.textListe(key)
        if (liste.isNotEmpty()) return liste
    }
    return emptyList()
}
