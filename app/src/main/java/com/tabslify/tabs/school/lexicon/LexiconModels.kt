package com.tabslify.tabs.school.lexicon

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_LANGUAGE = "Latein"
const val DEFAULT_TARGET_LANGUAGE = "Deutsch"

val lexiconJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    coerceInputValues = true
    explicitNulls = false
}

enum class VocabularyType(val key: String, val legacyKey: String) {
    REGISTER("register", "register"),
    LESSON("lesson", "lernwortschatz");

    companion object {
        fun fromKey(value: String?): VocabularyType =
            entries.firstOrNull { it.key == value || it.legacyKey == value } ?: REGISTER
    }
}

internal object VocabularyTypeSerializer : KSerializer<VocabularyType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("VocabularyType", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): VocabularyType {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return VocabularyType.fromKey(decoder.decodeString())
        val text = (jsonDecoder.decodeJsonElement() as? JsonPrimitive)?.content
        return VocabularyType.fromKey(text?.trim()?.takeIf { it.isNotEmpty() })
    }

    override fun serialize(encoder: Encoder, value: VocabularyType) {
        encoder.encodeString(value.key)
    }
}

@Serializable
data class LexiconEntry(
    val id: Int = 0,
    @JsonNames("stichwort")
    val headword: String = "",
    @JsonNames("bedeutungen")
    val meanings: List<String> = emptyList(),
    @JsonNames("wortart")
    val partOfSpeech: String? = null,
    @JsonNames("formen")
    val forms: List<String> = emptyList(),
    @JsonNames("verwandtschaften")
    val relatedWords: List<String> = emptyList(),
    @JsonNames("kapitel")
    val chapter: String? = null,
    @JsonNames("quelle")
    val source: String? = null,
    @JsonNames("seite")
    val page: Int? = null
)

@Serializable
data class VocabularyMeta(
    val id: String = "",
    val name: String = "",
    @Serializable(with = VocabularyTypeSerializer::class)
    @JsonNames("typ")
    val type: VocabularyType = VocabularyType.REGISTER,
    @JsonNames("anzahl")
    val count: Int = 0,
    @JsonNames("angelegt")
    val createdAt: Long = 0L,
    @JsonNames("sprache")
    val language: String = DEFAULT_LANGUAGE
)

@Serializable
data class Vocabulary(
    val id: String = "",
    val name: String = "",
    @Serializable(with = VocabularyTypeSerializer::class)
    @JsonNames("typ")
    val type: VocabularyType = VocabularyType.REGISTER,
    @JsonNames("sprache")
    val language: String = DEFAULT_LANGUAGE,
    @JsonNames("zielsprache")
    val targetLanguage: String = DEFAULT_TARGET_LANGUAGE,
    @JsonNames("angelegt")
    val createdAt: Long = 0L,
    @JsonNames("eintraege")
    val entries: List<LexiconEntry> = emptyList(),
    @JsonNames("auffaelligkeiten")
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
}

fun decodeLexiconEntry(raw: String): LexiconEntry? =
    runCatching { lexiconJson.decodeFromString<LexiconEntry>(raw) }.getOrNull()

fun decodeVocabulary(raw: String): Vocabulary? =
    runCatching { lexiconJson.decodeFromString<Vocabulary>(raw) }.getOrNull()

fun decodeCatalog(raw: String): List<VocabularyMeta>? = runCatching {
    val elements = lexiconJson.decodeFromString<List<JsonElement>>(raw)
    elements.mapNotNull { element ->
        runCatching {
            lexiconJson.decodeFromJsonElement(VocabularyMeta.serializer(), element)
        }.getOrNull()
    }
}.getOrNull()

fun encodeVocabulary(vocabulary: Vocabulary): String =
    lexiconJson.encodeToString(Vocabulary.serializer(), vocabulary)

fun encodeCatalog(metas: List<VocabularyMeta>): String =
    lexiconJson.encodeToString(ListSerializer(VocabularyMeta.serializer()), metas)

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
