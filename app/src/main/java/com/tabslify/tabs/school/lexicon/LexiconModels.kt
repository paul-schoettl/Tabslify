package com.tabslify.tabs.school.lexicon

import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_LANGUAGE = "Latein"
const val DEFAULT_TARGET_LANGUAGE = "Deutsch"

enum class VocabularyType(val key: String, val legacyKey: String) {
    REGISTER("register", "register"),
    LESSON("lesson", "lernwortschatz");

    companion object {
        fun fromKey(value: String?): VocabularyType =
            entries.firstOrNull { it.key == value || it.legacyKey == value } ?: REGISTER
    }
}

data class LexiconEntry(
    val id: Int,
    val headword: String,
    val meanings: List<String> = emptyList(),
    val partOfSpeech: String? = null,
    val forms: List<String> = emptyList(),
    val relatedWords: List<String> = emptyList(),
    val chapter: String? = null,
    val source: String? = null,
    val page: Int? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("headword", headword)
        put("meanings", JSONArray(meanings))
        if (partOfSpeech != null) put("partOfSpeech", partOfSpeech)
        put("forms", JSONArray(forms))
        put("relatedWords", JSONArray(relatedWords))
        if (chapter != null) put("chapter", chapter)
        if (source != null) put("source", source)
        if (page != null) put("page", page)
    }

    companion object {
        fun fromJson(o: JSONObject): LexiconEntry = LexiconEntry(
            id = o.optInt("id", 0),
            headword = (o.textCompat("headword", "stichwort") ?: "").trim(),
            meanings = o.listCompat("meanings", "bedeutungen"),
            partOfSpeech = o.textCompat("partOfSpeech", "wortart"),
            forms = o.listCompat("forms", "formen"),
            relatedWords = o.listCompat("relatedWords", "verwandtschaften"),
            chapter = o.textCompat("chapter", "kapitel"),
            source = o.textCompat("source", "quelle"),
            page = o.intCompat("page", "seite")
        )
    }
}

data class VocabularyMeta(
    val id: String,
    val name: String,
    val type: VocabularyType,
    val count: Int,
    val createdAt: Long,
    val language: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.key)
        put("count", count)
        put("createdAt", createdAt)
        put("language", language)
    }

    companion object {
        fun fromJson(o: JSONObject): VocabularyMeta = VocabularyMeta(
            id = o.optString("id"),
            name = o.optString("name"),
            type = VocabularyType.fromKey(o.textCompat("type", "typ")),
            count = o.intCompat("count", "anzahl") ?: 0,
            createdAt = o.intCompatLong("createdAt", "angelegt") ?: 0L,
            language = o.textCompat("language", "sprache") ?: DEFAULT_LANGUAGE
        )
    }
}

data class Vocabulary(
    val id: String,
    val name: String,
    val type: VocabularyType,
    val language: String = DEFAULT_LANGUAGE,
    val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
    val createdAt: Long = System.currentTimeMillis(),
    val entries: List<LexiconEntry> = emptyList(),
    val notices: List<String> = emptyList()
) {
    fun meta(): VocabularyMeta = VocabularyMeta(
        id = id,
        name = name,
        type = type,
        count = entries.size,
        createdAt = createdAt,
        language = language
    )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("type", type.key)
        put("language", language)
        put("targetLanguage", targetLanguage)
        put("createdAt", createdAt)
        put("entries", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        put("notices", JSONArray(notices))
    }

    companion object {
        fun fromJson(o: JSONObject): Vocabulary = Vocabulary(
            id = o.optString("id"),
            name = o.optString("name"),
            type = VocabularyType.fromKey(o.textCompat("type", "typ")),
            language = o.textCompat("language", "sprache") ?: DEFAULT_LANGUAGE,
            targetLanguage = o.textCompat("targetLanguage", "zielsprache") ?: DEFAULT_TARGET_LANGUAGE,
            createdAt = o.intCompatLong("createdAt", "angelegt") ?: 0L,
            entries = (o.optJSONArray("entries") ?: o.optJSONArray("eintraege") ?: JSONArray()).let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(LexiconEntry::fromJson)
                }
            },
            notices = (o.optJSONArray("notices") ?: o.optJSONArray("auffaelligkeiten"))?.toStringList()
                ?: emptyList()
        )
    }
}

fun JSONArray.toStringList(): List<String> = (0 until length()).mapNotNull { index ->
    val value = optString(index).trim()
    value.takeIf { it.isNotEmpty() }
}

fun JSONObject.textList(key: String): List<String> = when (val value = opt(key)) {
    is JSONArray -> value.toStringList()
    is String -> value.split(';', '|')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    else -> emptyList()
}

fun JSONObject.optText(key: String): String? =
    optString(key).trim().takeIf { it.isNotEmpty() }

private fun JSONObject.textCompat(primary: String, legacy: String): String? =
    optText(primary) ?: optText(legacy)

private fun JSONObject.listCompat(primary: String, legacy: String): List<String> {
    val list = textList(primary)
    return if (list.isNotEmpty()) list else textList(legacy)
}

private fun JSONObject.intCompat(primary: String, legacy: String): Int? =
    existingKey(primary, legacy)?.let { optInt(it) }

private fun JSONObject.intCompatLong(primary: String, legacy: String): Long? =
    existingKey(primary, legacy)?.let { optLong(it) }

private fun JSONObject.existingKey(primary: String, legacy: String): String? = when {
    has(primary) && !isNull(primary) -> primary
    has(legacy) && !isNull(legacy) -> legacy
    else -> null
}
