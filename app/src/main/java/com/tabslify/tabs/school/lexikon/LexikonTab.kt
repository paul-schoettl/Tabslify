package com.tabslify.tabs.school.lexikon

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.tabslify.R
import com.tabslify.core.ui.AccentFill
import com.tabslify.core.ui.AlertDialogTabslify
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.ErrorSoft
import com.tabslify.core.ui.OnAccentFill
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import com.tabslify.tabs.school.SchoolHeader
import com.tabslify.tabs.school.uriToBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun LexikonScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onImport: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wortschaetze by remember { mutableStateOf<List<Wortschatz>>(emptyList()) }
    var geladen by remember { mutableStateOf(false) }
    var neuLaden by remember { mutableStateOf(0) }
    var anfrage by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var bearbeiten by remember { mutableStateOf<String?>(null) }
    var loeschen by remember { mutableStateOf<Wortschatz?>(null) }

    LaunchedEffect(neuLaden) {
        wortschaetze = withContext(Dispatchers.IO) { LexikonStore.ladeAlle(context) }
        geladen = true
    }

    val index = remember(wortschaetze) { baueLexikonIndex(wortschaetze) }
    val gefiltert = remember(index, filter) {
        if (filter == null) index else index.filter { it.wortschatzId == filter }
    }
    val treffer = remember(gefiltert, anfrage) { sucheLexikon(gefiltert, anfrage) }
    val eintraegeGesamt = wortschaetze.sumOf { it.eintraege.size }

    BackHandler {
        onBack()
    }

    if (loeschen != null) {
        AlertDialogTabslify(
            title = stringResource(R.string.wortschatz_loeschen),
            text = stringResource(R.string.wortschatz_loeschen_text, loeschen!!.name),
            onConfirm = {
                val ziel = loeschen!!
                loeschen = null
                scope.launch {
                    withContext(Dispatchers.IO) { LexikonStore.loesche(context, ziel.id) }
                    filter = null
                    neuLaden++
                }
            },
            onDismiss = { loeschen = null }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SchoolHeader(
            title = stringResource(R.string.wortschatz),
            subtitle = if (eintraegeGesamt == 0) stringResource(R.string.wortschatz_leer_hinweis)
            else stringResource(R.string.wortschatz_eintraege, eintraegeGesamt),
            onBack = onBack,
            savedSets = emptyList(),
            showDashboard = false,
            paddingValues = paddingValues,
            actions = {
                IconButton(onClick = onImport) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.wortschatz_einlesen), tint = TextPrimary)
                }
            }
        )

        OutlinedTextField(
            value = anfrage,
            onValueChange = { anfrage = it },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            placeholder = { Text(stringResource(R.string.wortschatz_suchfeld), color = TextTertiary) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary) },
            trailingIcon = {
                if (anfrage.isNotEmpty()) {
                    IconButton(onClick = { anfrage = "" }) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.loschen), tint = TextTertiary)
                    }
                }
            }
        )

        if (wortschaetze.isNotEmpty()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    FilterMarke(
                        text = stringResource(R.string.wortschatz_alle),
                        aktiv = filter == null,
                        onClick = { filter = null }
                    )
                }
                items(wortschaetze, key = { it.id }) { wortschatz ->
                    FilterMarke(
                        text = "${wortschatz.name} (${wortschatz.eintraege.size})",
                        aktiv = filter == wortschatz.id,
                        onClick = { filter = if (filter == wortschatz.id) null else wortschatz.id }
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
                                    loeschen = wortschaetze.firstOrNull { it.id == filter }
                                }
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = stringResource(R.string.wortschatz_loeschen),
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
            !geladen -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = AccentFill)
            }

            wortschaetze.isEmpty() -> LeererWortschatz(onImport = onImport)

            anfrage.isBlank() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.wortschatz_tipp),
                    color = TextTertiary,
                    fontSize = 14.sp
                )
            }

            treffer.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.wortschatz_kein_treffer, anfrage),
                    color = TextTertiary,
                    fontSize = 14.sp
                )
            }

            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(treffer, key = { "${it.zeile.wortschatzId}:${it.zeile.eintrag.id}" }) { eintrag ->
                    val schluessel = "${eintrag.zeile.wortschatzId}:${eintrag.zeile.eintrag.id}"
                    EintragKarte(
                        eintrag = eintrag.zeile.eintrag,
                        wortschatzName = eintrag.zeile.wortschatzName,
                        bearbeiten = bearbeiten == schluessel,
                        onBearbeiten = {
                            bearbeiten = if (bearbeiten == schluessel) null else schluessel
                        },
                        onSpeichern = { neu ->
                            bearbeiten = null
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    LexikonStore.ersetzeEintrag(context, eintrag.zeile.wortschatzId, neu)
                                }
                                neuLaden++
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LeererWortschatz(onImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("📚", fontSize = 52.sp)
            Text(
                stringResource(R.string.wortschatz_leer_titel),
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                stringResource(R.string.wortschatz_leer_text),
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
                    stringResource(R.string.wortschatz_einlesen),
                    color = OnAccentFill,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun FilterMarke(
    text: String,
    aktiv: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (aktiv) AccentFill else BgCard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (aktiv) OnAccentFill else TextSecondary,
            fontSize = 13.sp,
            fontWeight = if (aktiv) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

@Composable
private fun EintragKarte(
    eintrag: LexikonEintrag,
    wortschatzName: String,
    bearbeiten: Boolean,
    onBearbeiten: () -> Unit,
    onSpeichern: (LexikonEintrag) -> Unit
) {
    var stichwort by remember(eintrag.id, bearbeiten) { mutableStateOf(eintrag.stichwort) }
    var bedeutungen by remember(eintrag.id, bearbeiten) {
        mutableStateOf(eintrag.bedeutungen.joinToString("; "))
    }
    var wortart by remember(eintrag.id, bearbeiten) { mutableStateOf(eintrag.wortart.orEmpty()) }
    var formen by remember(eintrag.id, bearbeiten) { mutableStateOf(eintrag.formen.joinToString("; ")) }
    var verwandt by remember(eintrag.id, bearbeiten) {
        mutableStateOf(eintrag.verwandtschaften.joinToString("; "))
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
                eintrag.stichwort,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (!bearbeiten) {
                IconButton(onClick = onBearbeiten) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.bearbeiten), tint = TextTertiary, modifier = Modifier.size(18.dp))
                }
            } else {
                IconButton(onClick = onBearbeiten) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.abbrechen), tint = TextTertiary, modifier = Modifier.size(18.dp))
                }
            }
        }

        if (eintrag.bedeutungen.isNotEmpty()) {
            Text(
                eintrag.bedeutungen.joinToString(" · "),
                color = TextSecondary,
                fontSize = 15.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        val meta = listOfNotNull(
            eintrag.wortart?.takeIf { it.isNotBlank() },
            eintrag.formen.joinToString(", ").takeIf { it.isNotBlank() }
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

        if (eintrag.verwandtschaften.isNotEmpty()) {
            Text(
                eintrag.verwandtschaften.joinToString(", "),
                color = TextTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }

        val fussnote = listOfNotNull(eintrag.quelle, wortschatzName).distinct().joinToString(" · ")
        if (fussnote.isNotBlank()) {
            Text(
                fussnote,
                color = TextTertiary,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        if (bearbeiten) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = stichwort,
                onValueChange = { stichwort = it },
                singleLine = true,
                label = { Text(stringResource(R.string.wortschatz_stichwort)) },
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bedeutungen,
                onValueChange = { bedeutungen = it },
                label = { Text(stringResource(R.string.wortschatz_bedeutungen)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            OutlinedTextField(
                value = wortart,
                onValueChange = { wortart = it },
                singleLine = true,
                label = { Text(stringResource(R.string.wortschatz_wortart)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            OutlinedTextField(
                value = formen,
                onValueChange = { formen = it },
                label = { Text(stringResource(R.string.wortschatz_formen)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            )
            OutlinedTextField(
                value = verwandt,
                onValueChange = { verwandt = it },
                label = { Text(stringResource(R.string.wortschatz_verwandtschaften)) },
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
                            onSpeichern(
                                eintrag.copy(
                                    stichwort = stichwort.trim().ifEmpty { eintrag.stichwort },
                                    bedeutungen = listeAus(bedeutungen),
                                    wortart = wortart.trim().takeIf { it.isNotEmpty() },
                                    formen = listeAus(formen),
                                    verwandtschaften = listeAus(verwandt)
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

private fun listeAus(text: String): List<String> = text
    .split(';', '|')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

@Composable
fun LexikonImportScreen(
    paddingValues: PaddingValues,
    onBack: () -> Unit,
    onFertig: (Wortschatz) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var seiten by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var typ by remember { mutableStateOf(WortschatzTyp.REGISTER) }
    var name by remember { mutableStateOf("") }
    var laeuft by remember { mutableStateOf(false) }
    var aktuell by remember { mutableStateOf(0) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var fertig by remember { mutableStateOf<Wortschatz?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var importId by remember { mutableStateOf<String?>(null) }
    var kameraDatei by remember { mutableStateOf<File?>(null) }

    val auswahlLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) seiten = (seiten + uris).distinctBy { it.toString() }
    }

    val kameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { erfolg ->
        val datei = kameraDatei
        if (erfolg && datei != null) {
            seiten = seiten + Uri.fromFile(datei)
        }
    }

    fun fotoAufnehmen() {
        val datei = File(context.cacheDir, "lexikon_${System.currentTimeMillis()}.jpg")
        kameraDatei = datei
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", datei)
        kameraLauncher.launch(uri)
    }

    fun starteImport() {
        if (seiten.isEmpty() || laeuft) return
        fehler = null
        fertig = null
        aktuell = 0
        laeuft = true
        job = scope.launch {
            val laufId = importId ?: LexikonStore.neuerImportOrdner(context).also { importId = it }
            val gesammelt = mutableListOf<LexikonEintrag>()
            val auffaelligkeiten = mutableListOf<String>()
            var quelle: String? = if (typ == WortschatzTyp.REGISTER) QUELLE_REGISTER else null
            try {
                seiten.forEachIndexed { index, uri ->
                    var roh = withContext(Dispatchers.IO) {
                        LexikonStore.ladeRoheSeite(context, laufId, index + 1)
                    }
                    if (roh == null) {
                        val bmp = withContext(Dispatchers.IO) { uriToBitmap(context, uri) }
                        if (bmp == null) {
                            auffaelligkeiten += "Seite ${index + 1}: Bild nicht lesbar"
                        } else {
                            val text = try {
                                ocrLexikonSeite(context, bmp, index + 1, quelle) { }
                            } catch (e: Exception) {
                                auffaelligkeiten += "Seite ${index + 1}: ${e.localizedMessage ?: "OCR-Fehler"}"
                                ""
                            } finally {
                                bmp.recycle()
                            }
                            if (text.isNotBlank()) {
                                withContext(Dispatchers.IO) {
                                    LexikonStore.speichereRoheSeite(context, laufId, index + 1, text)
                                }
                            }
                            roh = text
                        }
                    }
                    val inhalt = roh
                    if (!inhalt.isNullOrBlank()) {
                        val ergebnis = parseLexikonSeite(inhalt, index + 1, quelle)
                        if (!ergebnis.quelle.isNullOrBlank()) quelle = ergebnis.quelle
                        if (ergebnis.leer) {
                            auffaelligkeiten += "Seite ${index + 1}: keine Einträge (${ergebnis.fehler ?: "leer"})"
                        }
                        gesammelt += ergebnis.eintraege
                    }
                    aktuell = index + 1
                }
                val eintraege = verschmelzeLexikon(gesammelt)
                if (eintraege.isEmpty()) {
                    fehler = context.getString(R.string.wortschatz_import_leer)
                    return@launch
                }
                val wortschatz = Wortschatz(
                    id = LexikonStore.neuerWortschatzId(),
                    name = name.trim().ifEmpty {
                        if (typ == WortschatzTyp.REGISTER) context.getString(R.string.wortschatz_grundwortschatz)
                        else context.getString(R.string.wortschatz_lernwortschatz)
                    },
                    typ = typ,
                    eintraege = eintraege,
                    auffaelligkeiten = auffaelligkeiten
                )
                withContext(Dispatchers.IO) { LexikonStore.speichere(context, wortschatz) }
                importId = null
                fertig = wortschatz
            } catch (e: Exception) {
                fehler = e.localizedMessage
            } finally {
                laeuft = false
            }
        }
    }

    BackHandler(enabled = !laeuft) {
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SchoolHeader(
            title = stringResource(R.string.wortschatz_einlesen),
            subtitle = stringResource(R.string.wortschatz_import_hinweis),
            onBack = if (laeuft) null else onBack,
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
                            .clickable(enabled = !laeuft) { auswahlLauncher.launch("image/*") }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("🖼️", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.wortschatz_seiten_waehlen),
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
                            .clickable(enabled = !laeuft) { fotoAufnehmen() }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text("📷", fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.wortschatz_foto),
                            color = TextSecondary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (seiten.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seiten, key = { it.toString() }) { uri ->
                            val pos = seiten.indexOf(uri) + 1
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
                                    pos.toString(),
                                    color = OnAccentFill,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(AccentFill.copy(alpha = 0.9f))
                                        .padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                                if (!laeuft) {
                                    IconButton(
                                        onClick = { seiten = seiten - uri; importId = null },
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
                    FilterMarke(
                        text = stringResource(R.string.wortschatz_grundwortschatz),
                        aktiv = typ == WortschatzTyp.REGISTER,
                        onClick = { typ = WortschatzTyp.REGISTER }
                    )
                    FilterMarke(
                        text = stringResource(R.string.wortschatz_lernwortschatz),
                        aktiv = typ == WortschatzTyp.LERNWORTSCHATZ,
                        onClick = { typ = WortschatzTyp.LERNWORTSCHATZ }
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    enabled = !laeuft,
                    label = { Text(stringResource(R.string.wortschatz_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        stringResource(R.string.wortschatz_seiten_anzahl, seiten.size),
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (seiten.isEmpty() || laeuft) BgSurface else AccentFill)
                            .clickable(enabled = seiten.isNotEmpty() && !laeuft) { starteImport() }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            stringResource(R.string.wortschatz_starten),
                            color = if (seiten.isEmpty() || laeuft) TextTertiary else OnAccentFill,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (laeuft) {
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
                            stringResource(R.string.wortschatz_fortschritt, aktuell, seiten.size),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (laeuft) {
                item {
                    Text(
                        stringResource(R.string.wortschatz_abbrechen),
                        color = ErrorSoft,
                        fontSize = 13.sp,
                        modifier = Modifier.clickable {
                            job?.cancel()
                            laeuft = false
                        }
                    )
                }
            }

            fehler?.let { meldung ->
                item {
                    Text(meldung, color = ErrorSoft, fontSize = 13.sp)
                }
            }

            fertig?.let { wortschatz ->
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
                            stringResource(R.string.wortschatz_fertig),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.wortschatz_fertig_text, wortschatz.eintraege.size),
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        if (wortschatz.auffaelligkeiten.isNotEmpty()) {
                            Text(
                                stringResource(R.string.wortschatz_auffaelligkeiten),
                                color = TextSecondary,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            wortschatz.auffaelligkeiten.forEach { hinweis ->
                                Text(hinweis, color = TextTertiary, fontSize = 12.sp)
                            }
                        }
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
                                    .clickable { onFertig(wortschatz) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Text(
                                    stringResource(R.string.wortschatz_oeffnen),
                                    color = OnAccentFill,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            IconButton(onClick = { fehler = null; fertig = null; seiten = emptyList(); importId = null }) {
                                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.loschen), tint = TextTertiary)
                            }
                        }
                    }
                }
            }
        }
    }
}
