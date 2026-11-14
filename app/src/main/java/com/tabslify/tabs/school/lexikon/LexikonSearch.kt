package com.tabslify.tabs.school.lexikon

import java.text.Normalizer

data class LexikonIndexZeile(
    val wortschatzId: String,
    val wortschatzName: String,
    val eintrag: LexikonEintrag,
    val kopf: String,
    val kopfErst: String,
    val inhalt: String
)

data class LexikonTreffer(val zeile: LexikonIndexZeile, val rang: Int)

const val RANG_EXAKT = 0
const val RANG_PRAEFIX = 1
const val RANG_WORTANFANG = 2
const val RANG_TEILWORT = 3
const val RANG_INHALT = 4

fun normalisiereLexikon(text: String): String {
    val entfaltet = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return entfaltet
        .replace("ß", "ss")
        .replace("ae", "a")
        .replace("oe", "o")
        .replace("ue", "u")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
}

fun baueLexikonIndex(wortschaetze: List<Wortschatz>): List<LexikonIndexZeile> =
    wortschaetze.flatMap { wortschatz ->
        wortschatz.eintraege.map { eintrag ->
            val kopf = normalisiereLexikon(eintrag.stichwort)
            LexikonIndexZeile(
                wortschatzId = wortschatz.id,
                wortschatzName = wortschatz.name,
                eintrag = eintrag,
                kopf = kopf,
                kopfErst = kopf.substringBefore(' '),
                inhalt = normalisiereLexikon(
                    buildString {
                        append(eintrag.bedeutungen.joinToString(" "))
                        append(' ')
                        append(eintrag.formen.joinToString(" "))
                        append(' ')
                        append(eintrag.verwandtschaften.joinToString(" "))
                        append(' ')
                        append(eintrag.wortart.orEmpty())
                    }
                )
            )
        }
    }

fun sucheLexikon(
    index: List<LexikonIndexZeile>,
    anfrage: String,
    grenze: Int = 80
): List<LexikonTreffer> {
    val suchwort = normalisiereLexikon(anfrage)
    if (suchwort.isEmpty()) return emptyList()
    val teile = suchwort.split(' ').filter { it.isNotEmpty() }
    val treffer = ArrayList<LexikonTreffer>()
    index.forEach { zeile ->
        val gesamt = zeile.kopf + ' ' + zeile.inhalt
        if (teile.any { !gesamt.contains(it) }) return@forEach
        treffer += LexikonTreffer(zeile, rangFuer(zeile, suchwort))
    }
    return treffer
        .sortedWith(compareBy({ it.rang }, { it.zeile.kopf }, { it.zeile.wortschatzName }))
        .take(grenze)
}

private fun rangFuer(zeile: LexikonIndexZeile, suchwort: String): Int = when {
    zeile.kopfErst == suchwort || zeile.kopf == suchwort -> RANG_EXAKT
    zeile.kopfErst.startsWith(suchwort) || zeile.kopf.startsWith(suchwort) -> RANG_PRAEFIX
    zeile.kopf.contains(" $suchwort") -> RANG_WORTANFANG
    zeile.kopf.contains(suchwort) -> RANG_TEILWORT
    else -> RANG_INHALT
}
