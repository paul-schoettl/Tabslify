package com.tabslify.tabs.school.lexicon

import org.json.JSONArray
import org.json.JSONObject

data class LexiconPageResult(
    val entries: List<LexiconEntry>,
    val source: String?,
    val raw: String,
    val error: String?
) {
    val isEmpty: Boolean get() = entries.isEmpty()
}

const val REGISTER_SOURCE = "Grundwortschatz"

fun parseLexiconPage(raw: String, page: Int, fallbackSource: String?): LexiconPageResult {
    val cleaned = raw.trim()
        .removePrefix("```json")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    if (cleaned.isEmpty()) {
        return LexiconPageResult(emptyList(), fallbackSource, raw, "leere Antwort")
    }
    return try {
        val objectText = jsonSection(cleaned, '{', '}')
        if (objectText != null) {
            val o = JSONObject(objectText)
            val array = o.optJSONArray("entries")
                ?: o.optJSONArray("eintraege")
                ?: o.optJSONArray("vokabeln")
                ?: o.optJSONArray("data")
                ?: o.optJSONArray("items")
                ?: o.optJSONArray("liste")
            if (array != null) {
                val source = sourceFrom(
                    o.optText("source")
                        ?: o.optText("quelle")
                        ?: o.optText("lernwortschatz")
                        ?: o.optText("lektion"),
                    fallbackSource
                )
                return LexiconPageResult(readEntries(array, source, page), source, raw, null)
            }
        }
        val array = jsonSection(cleaned, '[', ']')?.let(::JSONArray)
            ?: return LexiconPageResult(
                emptyList(),
                fallbackSource,
                raw,
                "kein JSON gefunden: ${preview(cleaned)}"
            )
        LexiconPageResult(readEntries(array, fallbackSource, page), fallbackSource, raw, null)
    } catch (e: Exception) {
        LexiconPageResult(
            emptyList(),
            fallbackSource,
            raw,
            "Parse-Fehler (${e.message ?: "unbekannt"}): ${preview(cleaned)}"
        )
    }
}

fun sourceFrom(value: String?, fallback: String?): String? {
    val text = value?.trim().orEmpty()
    if (text.isEmpty()) return fallback
    if (text.none { it.isLetter() } && text.any { it.isDigit() }) return "Lernwortschatz $text"
    return text
}

fun mergeLexicon(entries: List<LexiconEntry>): List<LexiconEntry> {
    val groups = LinkedHashMap<String, LexiconEntry>()
    entries.forEach { entry ->
        val key = normalizeLexicon(entry.headword)
        if (key.isEmpty()) return@forEach
        val existing = groups[key]
        groups[key] = if (existing == null) entry else existing.copy(
            meanings = (existing.meanings + entry.meanings).distinct(),
            forms = (existing.forms + entry.forms).distinct(),
            relatedWords = (existing.relatedWords + entry.relatedWords).distinct(),
            partOfSpeech = existing.partOfSpeech ?: entry.partOfSpeech,
            chapter = existing.chapter ?: entry.chapter,
            source = existing.source ?: entry.source,
            page = existing.page ?: entry.page
        )
    }
    return groups.values.mapIndexed { index, entry -> entry.copy(id = index) }
}

private fun preview(text: String): String =
    text.replace(Regex("\\s+"), " ").trim().take(120)

private fun jsonSection(text: String, start: Char, end: Char): String? {
    val from = text.indexOf(start)
    val to = text.lastIndexOf(end)
    return if (from >= 0 && to > from) text.substring(from, to + 1) else null
}

private fun readEntries(array: JSONArray, source: String?, page: Int): List<LexiconEntry> =
    (0 until array.length()).mapNotNull { index ->
        val o = array.optJSONObject(index) ?: return@mapNotNull null
        val headword = firstText(
            o,
            "headword",
            "stichwort",
            "latein",
            "lateinisch",
            "lemma",
            "wort",
            "vokabel"
        ) ?: return@mapNotNull null
        LexiconEntry(
            id = index,
            headword = headword,
            meanings = firstList(
                o,
                "meanings",
                "bedeutungen",
                "bedeutung",
                "deutsch",
                "uebersetzung",
                "übersetzung",
                "meaning",
                "translation"
            ),
            partOfSpeech = firstText(o, "partOfSpeech", "wortart", "pos", "partofspeech", "part_of_speech"),
            forms = firstList(
                o,
                "forms",
                "formen",
                "stammformen",
                "genitiv",
                "genus",
                "principal_parts"
            ),
            relatedWords = firstList(
                o,
                "relatedWords",
                "verwandtschaften",
                "wortverwandtschaften",
                "verwandt",
                "herkunft",
                "related",
                "related_words"
            ),
            chapter = firstText(o, "chapter", "kapitel", "kap"),
            source = sourceFrom(firstText(o, "source", "quelle", "lernwortschatz", "lektion"), source),
            page = page
        )
    }

private fun firstText(o: JSONObject, vararg keys: String): String? {
    keys.forEach { key ->
        o.optText(key)?.let { return it }
    }
    return null
}

private fun firstList(o: JSONObject, vararg keys: String): List<String> {
    keys.forEach { key ->
        val list = o.textList(key)
        if (list.isNotEmpty()) return list
    }
    return emptyList()
}
