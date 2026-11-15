package com.tabslify.tabs.school.lexicon

import android.content.Context
import com.tabslify.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object LexiconStore {
    private const val STORAGE_DIR = "lexicon"
    private const val LEGACY_STORAGE_DIR = "lexikon"
    private const val INDEX_FILE = "index.json"
    private const val IMPORT_DIR = "imports"
    private const val PAGE_FILE_PREFIX = "page_"
    private const val LEGACY_PAGE_FILE_PREFIX = "seite_"

    private fun storageDir(context: Context): File =
        File(context.applicationContext.filesDir, STORAGE_DIR).apply {
            mkdirs()
            migrateLegacyStorage(context)
        }

    private fun migrateLegacyStorage(context: Context) {
        val base = context.applicationContext.filesDir
        val legacy = File(base, LEGACY_STORAGE_DIR)
        val target = File(base, STORAGE_DIR)
        if (!legacy.exists() || !legacy.isDirectory) return
        val legacyFiles = legacy.walkTopDown().filter { it.isFile }.toList()
        if (legacyFiles.isEmpty()) {
            legacy.deleteRecursively()
            return
        }
        legacyFiles.forEach { file ->
            val parentPath = file.relativeTo(legacy).path.substringBeforeLast('/', "")
            val dir = if (parentPath.isEmpty()) target else File(target, parentPath).apply { mkdirs() }
            val name = file.nameWithoutExtension
            val targetName = if (name.startsWith(LEGACY_PAGE_FILE_PREFIX)) {
                PAGE_FILE_PREFIX + name.removePrefix(LEGACY_PAGE_FILE_PREFIX) + ".json"
            } else {
                file.name
            }
            val destination = File(dir, targetName)
            if (!destination.exists()) {
                file.copyTo(destination, overwrite = true)
            }
        }
        legacy.deleteRecursively()
    }

    private fun fileFor(context: Context, id: String): File =
        File(storageDir(context), "$id.json")

    private fun indexFile(context: Context): File = File(storageDir(context), INDEX_FILE)

    fun loadIndex(context: Context): List<VocabularyMeta> {
        val stored = try {
            val file = indexFile(context)
            if (!file.exists()) return rebuildIndex(context)
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(VocabularyMeta::fromJson)
            }
        } catch (_: Exception) {
            return rebuildIndex(context)
        }
        val existing = existingFiles(context).map { it.nameWithoutExtension }.toSet()
        return if (stored.map { it.id }.toSet() == existing) stored
        else rebuildIndex(context)
    }

    fun rebuildIndex(context: Context): List<VocabularyMeta> {
        val metas = existingFiles(context).mapNotNull { file ->
            runCatching { Vocabulary.fromJson(JSONObject(file.readText())).meta() }.getOrNull()
        }.sortedByDescending { it.createdAt }
        writeIndex(context, metas)
        return metas
    }

    fun loadVocabulary(context: Context, id: String): Vocabulary? = runCatching {
        Vocabulary.fromJson(JSONObject(fileFor(context, id).readText()))
    }.getOrNull()

    fun loadAll(context: Context): List<Vocabulary> =
        loadIndex(context).mapNotNull { loadVocabulary(context, it.id) }

    fun save(context: Context, vocabulary: Vocabulary): List<VocabularyMeta> {
        val target = fileFor(context, vocabulary.id)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(vocabulary.toJson().toString())
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
        return rebuildIndex(context)
    }

    fun delete(context: Context, id: String): List<VocabularyMeta> {
        fileFor(context, id).delete()
        return rebuildIndex(context)
    }

    fun replaceEntry(
        context: Context,
        vocabularyId: String,
        entry: LexiconEntry
    ): Vocabulary? {
        val vocabulary = loadVocabulary(context, vocabularyId) ?: return null
        val updated = vocabulary.copy(
            entries = vocabulary.entries.map { if (it.id == entry.id) entry else it }
        )
        save(context, updated)
        return updated
    }

    fun mergeRegisters(context: Context): List<VocabularyMeta> {
        val registers = loadAll(context).filter { it.type == VocabularyType.REGISTER }
        if (registers.size < 2) return loadIndex(context)
        val target = registers.minBy { it.createdAt }
        val merged = target.copy(
            name = context.getString(R.string.vocabulary_register),
            entries = mergeLexicon(registers.flatMap { it.entries }),
            notices = registers.flatMap { it.notices }.distinct()
        )
        save(context, merged)
        registers.filter { it.id != merged.id }.forEach { fileFor(context, it.id).delete() }
        return rebuildIndex(context)
    }

    fun rename(context: Context, vocabularyId: String, name: String): Vocabulary? {
        val vocabulary = loadVocabulary(context, vocabularyId) ?: return null
        val updated = vocabulary.copy(name = name.trim().ifEmpty { vocabulary.name })
        save(context, updated)
        return updated
    }

    fun saveRawPage(context: Context, importId: String, page: Int, raw: String) {
        val target = File(importDir(context, importId), "$PAGE_FILE_PREFIX$page.json")
        target.writeText(raw)
    }

    fun loadRawPage(context: Context, importId: String, page: Int): String? {
        val dir = importDir(context, importId)
        val file = File(dir, "$PAGE_FILE_PREFIX$page.json")
        if (file.exists()) return file.readText()
        val legacy = File(dir, "$LEGACY_PAGE_FILE_PREFIX$page.json")
        return if (legacy.exists()) legacy.readText() else null
    }

    fun loadRawPages(context: Context, importId: String): Map<Int, String> {
        val dir = importDir(context, importId)
        if (!dir.exists()) return emptyMap()
        return (dir.listFiles() ?: emptyArray())
            .filter { file ->
                file.isFile &&
                    (file.name.startsWith(PAGE_FILE_PREFIX) || file.name.startsWith(LEGACY_PAGE_FILE_PREFIX))
            }
            .mapNotNull { file ->
                val number = file.nameWithoutExtension
                    .removePrefix(PAGE_FILE_PREFIX)
                    .removePrefix(LEGACY_PAGE_FILE_PREFIX)
                    .toIntOrNull()
                number?.let { it to file.readText() }
            }
            .toMap()
    }

    fun deleteImport(context: Context, importId: String) {
        importDir(context, importId).deleteRecursively()
    }

    fun newImportDir(context: Context): String {
        val id = "import_${System.currentTimeMillis()}"
        importDir(context, id)
        return id
    }

    fun newVocabularyId(): String = "ws_${System.currentTimeMillis()}"

    private fun importDir(context: Context, importId: String): File =
        File(storageDir(context), "$IMPORT_DIR/$importId").apply { mkdirs() }

    private fun existingFiles(context: Context): List<File> =
        (storageDir(context).listFiles() ?: emptyArray()).filter {
            it.isFile && it.extension == "json" && it.name != INDEX_FILE
        }

    private fun writeIndex(context: Context, metas: List<VocabularyMeta>) {
        val target = indexFile(context)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(JSONArray().apply { metas.forEach { put(it.toJson()) } }.toString())
        if (!temp.renameTo(target)) {
            target.writeText(temp.readText())
            temp.delete()
        }
    }
}
