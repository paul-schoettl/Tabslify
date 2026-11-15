package com.tabslify.tabs.school.lexicon

import java.text.Normalizer

data class LexiconIndexRow(
    val vocabularyId: String,
    val vocabularyName: String,
    val entry: LexiconEntry,
    val head: String,
    val headFirst: String,
    val content: String
)

data class LexiconMatch(val row: LexiconIndexRow, val rank: Int)

const val RANK_EXACT = 0
const val RANK_PREFIX = 1
const val RANK_WORD_START = 2
const val RANK_PARTIAL_WORD = 3
const val RANK_CONTENT = 4

fun normalizeLexicon(text: String): String {
    val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return folded
        .replace("ß", "ss")
        .replace("ae", "a")
        .replace("oe", "o")
        .replace("ue", "u")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

fun buildLexiconIndex(vocabularies: List<Vocabulary>): List<LexiconIndexRow> =
    vocabularies.flatMap { vocabulary ->
        vocabulary.entries.map { entry ->
            val head = normalizeLexicon(entry.headword)
            LexiconIndexRow(
                vocabularyId = vocabulary.id,
                vocabularyName = vocabulary.name,
                entry = entry,
                head = head,
                headFirst = head.substringBefore(' '),
                content = normalizeLexicon(
                    buildString {
                        append(entry.meanings.joinToString(" "))
                        append(' ')
                        append(entry.forms.joinToString(" "))
                        append(' ')
                        append(entry.relatedWords.joinToString(" "))
                        append(' ')
                        append(entry.partOfSpeech.orEmpty())
                    }
                )
            )
        }
    }

fun searchLexicon(
    index: List<LexiconIndexRow>,
    query: String,
    limit: Int = 80
): List<LexiconMatch> {
    val searchWord = normalizeLexicon(query)
    if (searchWord.isEmpty()) return emptyList()
    val parts = searchWord.split(' ').filter { it.isNotEmpty() }
    val matches = ArrayList<LexiconMatch>()
    index.forEach { row ->
        val whole = row.head + ' ' + row.content
        if (parts.any { !whole.contains(it) }) return@forEach
        matches += LexiconMatch(row, rankFor(row, searchWord))
    }
    return matches
        .sortedWith(compareBy({ it.rank }, { it.row.head }, { it.row.vocabularyName }))
        .take(limit)
}

private fun rankFor(row: LexiconIndexRow, searchWord: String): Int = when {
    row.headFirst == searchWord || row.head == searchWord -> RANK_EXACT
    row.headFirst.startsWith(searchWord) || row.head.startsWith(searchWord) -> RANK_PREFIX
    row.head.contains(" $searchWord") -> RANK_WORD_START
    row.head.contains(searchWord) -> RANK_PARTIAL_WORD
    else -> RANK_CONTENT
}
