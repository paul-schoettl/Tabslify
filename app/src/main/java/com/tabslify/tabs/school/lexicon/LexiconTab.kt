package com.tabslify.tabs.school.lexicon

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.tabslify.R
import com.tabslify.core.ui.AccentFill
import com.tabslify.core.ui.AccentViolet
import com.tabslify.core.ui.AlertDialogTabslify
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.ErrorSoft
import com.tabslify.core.ui.OnAccentFill
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import com.tabslify.tabs.school.RegisterTabBack
import com.tabslify.tabs.school.SchoolHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LexiconScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onImport: () -> Unit,
    focusRequest: Int = 0
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var vocabularies by remember { mutableStateOf<List<Vocabulary>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var reloadToken by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Vocabulary?>(null) }

    LaunchedEffect(reloadToken) {
        vocabularies = withContext(Dispatchers.IO) {
            LexiconStore.mergeRegisters(context)
            LexiconStore.loadAll(context)
        }
        loaded = true
    }

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0) {
            withFrameNanos { }
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }

    val index = remember(vocabularies) { buildLexiconIndex(vocabularies) }
    val filtered = remember(index, filter) {
        if (filter == null) index else index.filter { it.vocabularyId == filter }
    }
    val matches = remember(filtered, query) { searchLexicon(filtered, query) }
    val totalEntries = vocabularies.sumOf { it.entries.size }

    BackHandler {
        onBack()
    }

    if (pendingDelete != null) {
        AlertDialogTabslify(
            title = stringResource(R.string.vocabulary_delete),
            text = stringResource(R.string.vocabulary_delete_text, pendingDelete!!.name),
            onConfirm = {
                val target = pendingDelete!!
                pendingDelete = null
                scope.launch {
                    withContext(Dispatchers.IO) { LexiconStore.delete(context, target.id) }
                    filter = null
                    reloadToken++
                }
            },
            onDismiss = { pendingDelete = null }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SchoolHeader(
            title = stringResource(R.string.vocabulary),
            subtitle = if (totalEntries == 0) stringResource(R.string.vocabulary_empty_hint)
            else stringResource(R.string.vocabulary_entries, totalEntries),
            onBack = onBack,
            savedSets = emptyList(),
            showDashboard = false,
            paddingValues = paddingValues,
            actions = {
                IconButton(onClick = onImport) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.vocabulary_import),
                        tint = TextPrimary
                    )
                }
            }
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .focusRequester(searchFocus),
            placeholder = { Text(stringResource(R.string.vocabulary_search_hint), color = TextTertiary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.loschen),
                            tint = TextTertiary
                        )
                    }
                }
            }
        )

        if (vocabularies.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterChip(
                        text = stringResource(R.string.vocabulary_all),
                        active = filter == null,
                        onClick = { filter = null }
                    )
                }
                items(vocabularies, key = { it.id }) { vocabulary ->
                    FilterChip(
                        text = "${vocabulary.name} (${vocabulary.entries.size})",
                        active = filter == vocabulary.id,
                        onClick = { filter = if (filter == vocabulary.id) null else vocabulary.id }
                    )
                }
                if (filter != null) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(BgCard)
                                .clickable {
                                    pendingDelete = vocabularies.firstOrNull { it.id == filter }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.vocabulary_delete),
                                tint = ErrorSoft,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Spacer(Modifier.height(10.dp))
        }

        when {
            !loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentFill)
            }

            vocabularies.isEmpty() -> EmptyVocabulary(onImport = onImport)

            query.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.vocabulary_tip),
                    color = TextTertiary,
                    fontSize = 14.sp
                )
            }

            matches.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.vocabulary_no_match, query),
                    color = TextTertiary,
                    fontSize = 14.sp
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(matches, key = { "${it.row.vocabularyId}:${it.row.entry.id}" }) { match ->
                    val key = "${match.row.vocabularyId}:${match.row.entry.id}"
                    EntryCard(
                        entry = match.row.entry,
                        vocabularyName = match.row.vocabularyName,
                        editing = editing == key,
                        onToggleEdit = {
                            editing = if (editing == key) null else key
                        },
                        onSave = { updated ->
                            editing = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LexiconStore.replaceEntry(context, match.row.vocabularyId, updated)
                                }
                                reloadToken++
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyVocabulary(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("📚", fontSize = 52.sp)
            Text(
                stringResource(R.string.vocabulary_empty_title),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.vocabulary_empty_text),
                color = TextTertiary,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(AccentFill)
                    .clickable { onImport() }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    stringResource(R.string.vocabulary_import),
                    color = OnAccentFill,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) AccentFill else BgCard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (active) OnAccentFill else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EntryCard(
    entry: LexiconEntry,
    vocabularyName: String,
    editing: Boolean,
    onToggleEdit: () -> Unit,
    onSave: (LexiconEntry) -> Unit
) {
    var headword by remember(entry.id, editing) { mutableStateOf(entry.headword) }
    var meanings by remember(entry.id, editing) {
        mutableStateOf(entry.meanings.joinToString("; "))
    }
    var partOfSpeech by remember(entry.id, editing) { mutableStateOf(entry.partOfSpeech.orEmpty()) }
    var chapter by remember(entry.id, editing) { mutableStateOf(entry.chapter.orEmpty()) }
    var forms by remember(entry.id, editing) { mutableStateOf(entry.forms.joinToString("; ")) }
    var relatedWords by remember(entry.id, editing) {
        mutableStateOf(entry.relatedWords.joinToString("; "))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BgCard)
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                entry.headword,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (!entry.chapter.isNullOrBlank()) {
                Text(
                    stringResource(R.string.vocabulary_chapter_short, entry.chapter!!),
                    color = AccentViolet,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentFill.copy(alpha = 0.16f))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                )
                Spacer(Modifier.width(8.dp))
            }
            if (!editing) {
                IconButton(onClick = onToggleEdit) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = stringResource(R.string.bearbeiten),
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                IconButton(onClick = onToggleEdit) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.abbrechen),
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        if (entry.meanings.isNotEmpty()) {
            Text(
                entry.meanings.joinToString(" · "),
                color = TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        val meta = listOfNotNull(
            entry.partOfSpeech?.takeIf { it.isNotBlank() },
            entry.forms.joinToString(", ").takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (meta.isNotEmpty()) {
            Text(
                meta,
                color = TextTertiary,
                fontSize = 12.sp,
                fontStyle = FontStyle.Italic,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        if (entry.relatedWords.isNotEmpty()) {
            Text(
                entry.relatedWords.joinToString(", "),
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        val footnote = listOfNotNull(entry.source, vocabularyName).distinct().joinToString(" · ")
        if (footnote.isNotBlank()) {
            Text(
                footnote,
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (editing) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = headword,
                onValueChange = { headword = it },
                singleLine = true,
                label = { Text(stringResource(R.string.vocabulary_headword)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = meanings,
                onValueChange = { meanings = it },
                label = { Text(stringResource(R.string.vocabulary_meanings)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            OutlinedTextField(
                value = partOfSpeech,
                onValueChange = { partOfSpeech = it },
                singleLine = true,
                label = { Text(stringResource(R.string.vocabulary_part_of_speech)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            OutlinedTextField(
                value = chapter,
                onValueChange = { chapter = it },
                singleLine = true,
                label = { Text(stringResource(R.string.vocabulary_chapter)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            OutlinedTextField(
                value = forms,
                onValueChange = { forms = it },
                label = { Text(stringResource(R.string.vocabulary_forms)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            OutlinedTextField(
                value = relatedWords,
                onValueChange = { relatedWords = it },
                label = { Text(stringResource(R.string.vocabulary_related_words)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(AccentFill)
                        .clickable {
                            onSave(
                                entry.copy(
                                    headword = headword.trim().ifEmpty { entry.headword },
                                    meanings = splitList(meanings),
                                    partOfSpeech = partOfSpeech.trim().takeIf { it.isNotEmpty() },
                                    chapter = chapter.trim().takeIf { it.isNotEmpty() },
                                    forms = splitList(forms),
                                    relatedWords = splitList(relatedWords)
                                )
                            )
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp)
                ) {
                    Text(
                        stringResource(R.string.speichern),
                        color = OnAccentFill,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private fun splitList(text: String): List<String> = text
    .split(';', '|')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private data class PageRun(
    val index: Int,
    val entries: List<LexiconEntry>,
    val source: String?,
    val notice: String?
)

@Composable
fun LexiconImportScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onDone: (Vocabulary) -> Unit,
    registerBack: RegisterTabBack? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pages by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var type by remember { mutableStateOf(VocabularyType.REGISTER) }
    var name by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var currentPage by remember { mutableStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var finished by remember { mutableStateOf<Vocabulary?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var importId by remember { mutableStateOf<String?>(null) }
    var notices by remember { mutableStateOf<List<String>>(emptyList()) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) pages = (pages + uris).distinctBy { it.toString() }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        val file = cameraFile
        if (success && file != null) {
            pages = pages + Uri.fromFile(file)
        }
    }

    fun takePhoto() {
        val file = File(context.cacheDir, "lexicon_${System.currentTimeMillis()}.jpg")
        cameraFile = file
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        cameraLauncher.launch(uri)
    }

    fun startImport() {
        if (pages.isEmpty() || running) return
        error = null
        finished = null
        notices = emptyList()
        currentPage = 0
        running = true
        job = scope.launch {
            val runId = importId ?: LexiconStore.newImportDir(context).also { importId = it }
            val collected = mutableListOf<LexiconEntry>()
            val collectedNotices = mutableListOf<String>()
            var source: String? = if (type == VocabularyType.REGISTER) REGISTER_SOURCE else null
            suspend fun readPage(index: Int, hint: String?, preloaded: ByteArray?): PageRun {
                val pageNumber = index + 1
                var raw = withContext(Dispatchers.IO) {
                    LexiconStore.loadRawPage(context, runId, pageNumber)
                }
                var notice: String? = null
                if (raw == null) {
                    val jpeg = preloaded ?: withContext(Dispatchers.IO) {
                        pageAsJpeg(context, pages[index])
                    }
                    if (jpeg == null) {
                        raw = ""
                        notice = "Seite $pageNumber: Bild nicht lesbar"
                    } else {
                        val text = try {
                            ocrLexiconPage(context, jpeg, pageNumber, hint) { }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            notice = "Seite $pageNumber: ${e.localizedMessage ?: "OCR-Fehler"}"
                            ""
                        }
                        if (text.isNotBlank()) {
                            withContext(Dispatchers.IO) {
                                LexiconStore.saveRawPage(context, runId, pageNumber, text)
                            }
                        }
                        raw = text
                    }
                }
                val content = raw
                val result = if (content.isNullOrBlank()) {
                    LexiconPageResult(emptyList(), hint, "", "leer")
                } else {
                    parseLexiconPage(content, pageNumber, hint)
                }
                if (notice == null && result.isEmpty) {
                    notice = "Seite $pageNumber: keine Einträge (${result.error ?: "leer"})"
                }
                return PageRun(index, result.entries, result.source, notice)
            }

            fun applyRun(run: PageRun) {
                run.notice?.let { collectedNotices += it }
                collected += run.entries
                if (!run.source.isNullOrBlank()) source = run.source
            }

            try {
                if (type == VocabularyType.REGISTER) {
                    val images = HashMap<Int, ByteArray>()
                    pages.forEachIndexed { index, uri ->
                        val cached = withContext(Dispatchers.IO) {
                            LexiconStore.loadRawPage(context, runId, index + 1)
                        }
                        if (cached == null) {
                            val jpeg = withContext(Dispatchers.IO) { pageAsJpeg(context, uri) }
                            if (jpeg != null) images[index] = jpeg
                        }
                    }
                    var done = 0
                    pages.indices
                        .map { index ->
                            async {
                                readPage(index, REGISTER_SOURCE, images[index]).also {
                                    done++
                                    currentPage = done
                                }
                            }
                        }
                        .awaitAll()
                        .sortedBy { it.index }
                        .forEach(::applyRun)
                } else {
                    pages.forEachIndexed { index, _ ->
                        applyRun(readPage(index, source, null))
                        currentPage = index + 1
                    }
                }
                val entries = mergeLexicon(collected)
                if (entries.isEmpty()) {
                    error = context.getString(R.string.vocabulary_import_empty)
                    return@launch
                }
                val existing = if (type == VocabularyType.REGISTER) {
                    withContext(Dispatchers.IO) {
                        LexiconStore.loadAll(context).filter { it.type == VocabularyType.REGISTER }
                    }
                } else {
                    emptyList()
                }
                val previous = existing.minByOrNull { it.createdAt }
                val vocabulary = Vocabulary(
                    id = previous?.id ?: LexiconStore.newVocabularyId(),
                    name = if (type == VocabularyType.REGISTER) {
                        context.getString(R.string.vocabulary_register)
                    } else {
                        name.trim().ifEmpty { context.getString(R.string.vocabulary_lesson) }
                    },
                    type = type,
                    createdAt = previous?.createdAt ?: System.currentTimeMillis(),
                    entries = mergeLexicon(
                        existing.flatMap { it.entries } + entries
                    ),
                    notices = (existing.flatMap { it.notices } + collectedNotices).distinct()
                )
                withContext(Dispatchers.IO) {
                    LexiconStore.save(context, vocabulary)
                    existing.filter { it.id != vocabulary.id }.forEach { LexiconStore.delete(context, it.id) }
                }
                importId = null
                finished = vocabulary
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.localizedMessage
            } finally {
                notices = collectedNotices.toList()
                running = false
            }
        }
    }

    val backAction = rememberUpdatedState<() -> Unit> { onBack }
    LaunchedEffect(registerBack, running) {
        registerBack?.invoke(!running) { backAction.value() }
    }

    BackHandler(enabled = !running) {
        backAction.value()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SchoolHeader(
            title = stringResource(R.string.vocabulary_import),
            subtitle = stringResource(R.string.vocabulary_import_hint),
            onBack = if (running) null else onBack,
            savedSets = emptyList(),
            showDashboard = false,
            paddingValues = paddingValues
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(AccentFill)
                            .clickable(enabled = !running) { pickerLauncher.launch("image/*") }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("🖼️", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.vocabulary_choose_pages),
                            color = OnAccentFill,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(BgCard)
                            .clickable(enabled = !running) { takePhoto() }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("📷", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.vocabulary_photo),
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (pages.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(pages, key = { it.toString() }) { uri ->
                            val position = pages.indexOf(uri) + 1
                            Box {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(BgSurface)
                                )
                                Text(
                                    position.toString(),
                                    color = OnAccentFill,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AccentFill.copy(alpha = 0.9f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                                if (!running) {
                                    IconButton(
                                        onClick = { pages = pages - uri; importId = null },
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(22.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.loschen),
                                            tint = ErrorSoft,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        text = stringResource(R.string.vocabulary_register),
                        active = type == VocabularyType.REGISTER,
                        onClick = { type = VocabularyType.REGISTER }
                    )
                    FilterChip(
                        text = stringResource(R.string.vocabulary_lesson),
                        active = type == VocabularyType.LESSON,
                        onClick = { type = VocabularyType.LESSON }
                    )
                }
            }

            if (type == VocabularyType.LESSON) {
                item {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                        enabled = !running,
                        label = { Text(stringResource(R.string.vocabulary_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.vocabulary_register_info),
                        color = TextTertiary,
                        fontSize = 13.sp
                    )
                }
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.vocabulary_page_count, pages.size),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (pages.isEmpty() || running) BgSurface else AccentFill)
                            .clickable(enabled = pages.isNotEmpty() && !running) { startImport() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            stringResource(R.string.vocabulary_start),
                            color = if (pages.isEmpty() || running) TextTertiary else OnAccentFill,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (running) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            color = AccentFill,
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            stringResource(R.string.vocabulary_progress, currentPage, pages.size),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (running) {
                item {
                    Text(
                        stringResource(R.string.vocabulary_cancel),
                        color = ErrorSoft,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            job?.cancel()
                            running = false
                        }
                    )
                }
            }

            error?.let { message ->
                item {
                    Text(message, color = ErrorSoft, fontSize = 13.sp)
                }
            }

            if (notices.isNotEmpty() && !running) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(BgCard)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            stringResource(R.string.vocabulary_notices),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        notices.forEach { notice ->
                            Text(notice, color = TextTertiary, fontSize = 12.sp)
                        }
                    }
                }
            }

            finished?.let { vocabulary ->
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(BgCard)
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(R.string.vocabulary_finished),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.vocabulary_finished_text, vocabulary.entries.size),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AccentFill)
                                    .clickable { onDone(vocabulary) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    stringResource(R.string.vocabulary_open),
                                    color = OnAccentFill,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            IconButton(onClick = {
                                error = null
                                finished = null
                                pages = emptyList()
                                importId = null
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(R.string.loschen),
                                    tint = TextTertiary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
