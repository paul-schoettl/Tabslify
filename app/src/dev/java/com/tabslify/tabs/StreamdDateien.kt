package com.tabslify.tabs

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.tabslify.R
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val DATEI_PORT = 48502

private data class DateiEintrag(val name: String, val dir: Boolean, val groesse: Long)

private fun dateiVerbindung(host: String, pfad: String, code: String?): HttpURLConnection {
    val verbindung = URL("http://$host:$DATEI_PORT$pfad").openConnection() as HttpURLConnection
    verbindung.connectTimeout = 4000
    verbindung.readTimeout = 15000
    if (!code.isNullOrBlank()) verbindung.setRequestProperty("X-Tabslify-Totp", code)
    return verbindung
}

private fun dateiListe(host: String, pfad: String, code: String?): List<DateiEintrag> {
    val codiert = URLEncoder.encode(pfad, "UTF-8")
    val verbindung = dateiVerbindung(host, "/list?path=$codiert", code)
    try {
        if (verbindung.responseCode != 200) error("HTTP ${verbindung.responseCode}")
        val text = verbindung.inputStream.bufferedReader().readText()
        val json = JSONObject(text)
        val liste = json.getJSONArray("eintraege")
        val eintraege = ArrayList<DateiEintrag>(liste.length())
        for (i in 0 until liste.length()) {
            val eintrag = liste.getJSONObject(i)
            eintraege.add(
                DateiEintrag(
                    name = eintrag.getString("name"),
                    dir = eintrag.getBoolean("dir"),
                    groesse = eintrag.optLong("groesse", 0)
                )
            )
        }
        return eintraege
    } finally {
        verbindung.disconnect()
    }
}

private fun dateiHerunterladen(
    context: Context,
    host: String,
    pfad: String,
    name: String,
    code: String?
): Boolean {
    val codiert = URLEncoder.encode(pfad, "UTF-8")
    val verbindung = dateiVerbindung(host, "/download?path=$codiert", code)
    return try {
        if (verbindung.responseCode != 200) return false
        val werte = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        }
        val ziel = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, werte)
            ?: return false
        context.contentResolver.openOutputStream(ziel)?.use { ausgabe ->
            verbindung.inputStream.use { eingang -> eingang.copyTo(ausgabe) }
        } ?: return false
        true
    } finally {
        verbindung.disconnect()
    }
}

private fun dateiName(context: Context, uri: Uri): String? {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index)
            }
        }
    return uri.lastPathSegment?.substringAfterLast('/')
}

private fun dateiHochladen(
    context: Context,
    host: String,
    zielPfad: String,
    name: String,
    uri: Uri,
    code: String?
): Boolean {
    val codiert = URLEncoder.encode(zielPfad, "UTF-8")
    val verbindung = dateiVerbindung(host, "/upload?path=$codiert", code)
    return try {
        verbindung.requestMethod = "POST"
        verbindung.doOutput = true
        val laenge = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (laenge >= 0) {
            verbindung.setFixedLengthStreamingMode(laenge)
        } else {
            val daten = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return false
            verbindung.setFixedLengthStreamingMode(daten.size)
            verbindung.outputStream.use { it.write(daten) }
            return verbindung.responseCode == 200
        }
        context.contentResolver.openInputStream(uri)?.use { eingang ->
            verbindung.outputStream.use { ausgabe -> eingang.copyTo(ausgabe) }
        } ?: return false
        verbindung.responseCode == 200
    } finally {
        verbindung.disconnect()
    }
}

@Composable
fun StreamdDateien(host: String, authCode: () -> String?, onSchliessen: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val bereich = rememberCoroutineScope()
    var pfad by remember { mutableStateOf("") }
    var eintraege by remember { mutableStateOf<List<DateiEintrag>>(emptyList()) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var laedt by remember { mutableStateOf(true) }
    var hinweis by remember { mutableStateOf<String?>(null) }

    fun laden(neu: String) {
        laedt = true
        bereich.launch {
            val ergebnis = withContext(Dispatchers.IO) {
                runCatching { dateiListe(host, neu, authCode()) }
            }
            ergebnis.onSuccess {
                eintraege = it
                pfad = neu
                fehler = null
            }.onFailure {
                fehler = it.message
            }
            laedt = false
        }
    }

    LaunchedEffect(Unit) { laden("") }

    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = dateiName(context, uri)
            if (name == null) {
                fehler = resources.getString(R.string.stream_fehler, "kein Dateiname")
            } else {
                val ziel = if (pfad.isBlank()) name else "$pfad/$name"
                bereich.launch {
                    laedt = true
                    val ok = withContext(Dispatchers.IO) {
                        runCatching { dateiHochladen(context, host, ziel, name, uri, authCode()) }
                            .getOrDefault(false)
                    }
                    laedt = false
                    hinweis = if (ok) resources.getString(R.string.stream_upload_fertig, name)
                    else resources.getString(R.string.stream_fehler, "Upload")
                    laden(pfad)
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .zIndex(20f)
    ) {
        Card(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(16.dp)
                .heightIn(max = 620.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = TextPrimary)
                    Text(
                        text = if (pfad.isBlank()) "/" else "/$pfad",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp)
                    )
                    IconButton(onClick = { uploader.launch(arrayOf("*/*")) }, enabled = !laedt) {
                        Icon(
                            Icons.Default.Upload,
                            contentDescription = stringResource(R.string.stream_hochladen),
                            tint = TextPrimary
                        )
                    }
                    IconButton(onClick = onSchliessen) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.abbrechen),
                            tint = TextPrimary
                        )
                    }
                }
                fehler?.let { Text(it, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                hinweis?.let { Text(it, color = TextSecondary, fontSize = 12.sp) }
                if (laedt) {
                    Text(stringResource(R.string.stream_laedt), color = TextSecondary, fontSize = 12.sp)
                }
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    if (pfad.isNotBlank()) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { laden(pfad.substringBeforeLast('/', "")) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("..", color = TextSecondary, modifier = Modifier.padding(end = 12.dp))
                                Text(stringResource(R.string.stream_zurueck), color = TextSecondary)
                            }
                        }
                    }
                    items(eintraege) { eintrag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (eintrag.dir) {
                                        laden(if (pfad.isBlank()) eintrag.name else "$pfad/${eintrag.name}")
                                    } else {
                                        val quelle = if (pfad.isBlank()) eintrag.name else "$pfad/${eintrag.name}"
                                        bereich.launch {
                                            val ok = withContext(Dispatchers.IO) {
                                                runCatching {
                                                    dateiHerunterladen(
                                                        context, host, quelle, eintrag.name, authCode()
                                                    )
                                                }.getOrDefault(false)
                                            }
                                            hinweis = if (ok) {
                                                resources.getString(R.string.stream_datei_gespeichert, eintrag.name)
                                            } else {
                                                resources.getString(R.string.stream_fehler, "Download")
                                            }
                                        }
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (eintrag.dir) Icons.Default.Folder
                                else Icons.AutoMirrored.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = if (eintrag.dir) Color(0xFFFFA500) else TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = eintrag.name,
                                color = TextPrimary,
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 10.dp)
                            )
                            if (!eintrag.dir) {
                                Text(
                                    text = dateiGroesse(eintrag.groesse),
                                    color = TextSecondary,
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Icon(
                                    Icons.Default.Download,
                                    contentDescription = stringResource(R.string.stream_herunterladen),
                                    tint = TextSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun dateiGroesse(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}
