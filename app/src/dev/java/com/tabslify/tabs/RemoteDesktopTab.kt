package com.tabslify.tabs

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.tabslify.R
import com.tabslify.core.objects.prvt
import com.tabslify.core.ui.AccentViolet
import com.tabslify.core.ui.AppBackground
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteDesktopTabContent() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val verboten = stringResource(R.string.forbidden)
    if (!prvt()) {
        Toast.makeText(context, verboten, Toast.LENGTH_SHORT).show()
        return
    }

    var liste by remember { mutableStateOf(StreamdProfile.laden(context)) }
    var auswahl by remember { mutableIntStateOf(StreamdProfile.auswahl(context)) }
    var profil by remember { mutableStateOf(liste.getOrNull(auswahl) ?: StreamProfil()) }
    var portText by remember { mutableStateOf((liste.getOrNull(auswahl) ?: StreamProfil()).port.toString()) }
    var erweitert by remember { mutableStateOf(false) }
    var streamAktiv by remember { mutableStateOf(false) }
    var hinweis by remember { mutableStateOf<String?>(null) }

    if (streamAktiv) {
        StreamdBildschirm(profil = profil, onZurueck = { streamAktiv = false })
        return
    }

    fun auswahlUebernehmen(index: Int) {
        val gewaehlt = liste.getOrNull(index) ?: return
        auswahl = index
        profil = gewaehlt
        portText = gewaehlt.port.toString()
        hinweis = null
        StreamdProfile.speichern(context, liste, index)
    }

    fun aktuell(): StreamProfil {
        val port = portText.toIntOrNull() ?: 0
        return profil.copy(
            name = profil.name.trim(),
            host = profil.host.trim(),
            port = port,
            pcName = profil.pcName.trim(),
            mac = profil.mac.trim()
        )
    }

    fun speichern() {
        val eintrag = aktuell().let { it.copy(name = it.name.ifBlank { it.host }) }
        if (eintrag.name.isBlank()) return
        val neue = liste.toMutableList()
        val vorhanden = neue.indexOfFirst { it.name == eintrag.name }
        if (vorhanden >= 0) {
            neue[vorhanden] = eintrag
            auswahl = vorhanden
        } else {
            neue.add(eintrag)
            auswahl = neue.lastIndex
        }
        liste = neue
        profil = eintrag
        portText = eintrag.port.toString()
        StreamdProfile.speichern(context, neue, auswahl)
    }

    fun loeschen() {
        if (liste.isEmpty()) return
        val neue = liste.toMutableList()
        neue.removeAt(auswahl.coerceIn(0, neue.lastIndex))
        val rest = if (neue.isEmpty()) listOf(StreamProfil()) else neue
        liste = rest
        auswahl = 0
        profil = rest[0]
        portText = rest[0].port.toString()
        StreamdProfile.speichern(context, rest, 0)
    }

    val portGueltig = portText.toIntOrNull()?.let { it in 1..65535 } == true
    val startBereit = profil.host.isNotBlank() && portGueltig

    AppBackground(Modifier.fillMaxSize()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = BgCard)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, contentDescription = null, tint = AccentViolet)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.remote_desktop), color = TextPrimary)
                }
                Text(stringResource(R.string.stream_hinweis), color = TextSecondary)
                val autoText = stringResource(R.string.stream_auto)

                var profilOffen by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = profilOffen,
                    onExpandedChange = { profilOffen = it }
                ) {
                    OutlinedTextField(
                        value = liste.getOrNull(auswahl)?.anzeige ?: "",
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        label = { Text(stringResource(R.string.stream_profil)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = profilOffen)
                        },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = profilOffen,
                        onDismissRequest = { profilOffen = false }
                    ) {
                        liste.forEachIndexed { index, eintrag ->
                            DropdownMenuItem(
                                text = { Text(eintrag.anzeige) },
                                onClick = {
                                    profilOffen = false
                                    auswahlUebernehmen(index)
                                }
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = profil.name,
                        onValueChange = { profil = profil.copy(name = it) },
                        label = { Text(stringResource(R.string.stream_name)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { speichern() }, enabled = profil.host.isNotBlank()) {
                        Icon(
                            Icons.Default.Save,
                            contentDescription = stringResource(R.string.stream_speichern),
                            tint = TextPrimary
                        )
                    }
                    IconButton(onClick = { loeschen() }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = stringResource(R.string.stream_loeschen),
                            tint = TextPrimary
                        )
                    }
                }

                OutlinedTextField(
                    value = profil.host,
                    onValueChange = { profil = profil.copy(host = it); hinweis = null },
                    label = { Text(stringResource(R.string.stream_host)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { neu ->
                            portText = neu.filter { it.isDigit() }.take(5)
                        },
                        label = { Text(stringResource(R.string.stream_port)) },
                        singleLine = true,
                        isError = portText.isNotEmpty() && !portGueltig,
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    AuswahlFeld(
                        label = stringResource(R.string.stream_qualitaet),
                        wert = qualitaetLabel(profil.hoehe, autoText),
                        optionen = listOf(0 to qualitaetLabel(0, autoText), 720 to "720p", 1080 to "1080p"),
                        modifier = Modifier.weight(1f)
                    ) { hoehe -> profil = profil.copy(hoehe = hoehe) }
                }

                AuswahlFeld(
                    label = stringResource(R.string.stream_codec),
                    wert = codecLabel(profil.codec, autoText),
                    optionen = listOf(0 to codecLabel(0, autoText), 1 to "H.264", 2 to "HEVC"),
                    modifier = Modifier.fillMaxWidth()
                ) { codec -> profil = profil.copy(codec = codec) }

                if (profil.codec == 0) {
                    Text(stringResource(R.string.stream_codec_auto_info), color = TextSecondary)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.stream_sparsam), color = TextPrimary)
                        Text(stringResource(R.string.stream_sparsam_info), color = TextSecondary)
                        if (!profil.sparsam) {
                            Text(stringResource(R.string.stream_sparsam_auto), color = TextSecondary)
                        }
                    }
                    Switch(
                        checked = profil.sparsam,
                        onCheckedChange = { profil = profil.copy(sparsam = it) }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.stream_erweitert), color = TextSecondary)
                    IconButton(onClick = { erweitert = !erweitert }) {
                        Icon(
                            if (erweitert) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = stringResource(R.string.stream_erweitert),
                            tint = TextPrimary
                        )
                    }
                }
                if (erweitert) {
                    OutlinedTextField(
                        value = profil.pcName,
                        onValueChange = { profil = profil.copy(pcName = it) },
                        label = { Text(stringResource(R.string.stream_pc_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = profil.mac,
                        onValueChange = { profil = profil.copy(mac = it) },
                        label = { Text(stringResource(R.string.stream_mac)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                hinweis?.let { Text(it, color = TextSecondary) }

                Button(
                    onClick = {
                        val eintrag = aktuell()
                        if (!eintrag.gueltig) {
                            hinweis = resources.getString(R.string.stream_ungueltig)
                            return@Button
                        }
                        profil = eintrag.copy(name = eintrag.name.ifBlank { eintrag.host })
                        speichern()
                        streamAktiv = true
                    },
                    enabled = startBereit,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentViolet)
                ) {
                    Text(stringResource(R.string.stream_start), color = TextPrimary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuswahlFeld(
    label: String,
    wert: String,
    optionen: List<Pair<Int, String>>,
    modifier: Modifier = Modifier,
    onWaehlen: (Int) -> Unit
) {
    var offen by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = offen,
        onExpandedChange = { offen = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = wert,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = offen) },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(expanded = offen, onDismissRequest = { offen = false }) {
            optionen.forEach { (id, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        offen = false
                        onWaehlen(id)
                    }
                )
            }
        }
    }
}

private fun qualitaetLabel(hoehe: Int, auto: String): String = when (hoehe) {
    720 -> "720p"
    1080 -> "1080p"
    else -> auto
}

private fun codecLabel(codec: Int, auto: String): String = when (codec) {
    1 -> "H.264"
    2 -> "HEVC"
    else -> auto
}
