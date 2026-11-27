package com.tabslify.tabs.tiktok

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tabslify.core.objects.toast
import com.tabslify.core.ui.ErrorRed
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.tabs.tiktok.ui.TikTokTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val RECALL_WINDOW_MS = 60L * 60L * 1000L

private val REACTION_EMOJI = listOf(
    "❤️", "👍", "👎", "😂", "🥰", "🤔", "😮", "😢",
    "😭", "😡", "🙏", "🎉", "🔥", "✌️", "🤗", "🕶️", "🥳", "❣️", "☺️", "🤪"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageActionSheet(
    message: TikTokMessage,
    error: String?,
    onDismiss: () -> Unit,
    onReaction: (String) -> Unit,
    onReply: () -> Unit = {},
    onRecall: () -> Unit,
    onDelete: () -> Unit,
    onOffline: () -> Unit,
    onOfflineLater: () -> Unit
) {
    val isOwn = message.isOwn
    val age = System.currentTimeMillis() - message.timestamp
    val recallable = isOwn && message.timestamp > 0L && age <= RECALL_WINDOW_MS
    val isVideo = message.mediaKind == "video" && message.mediaStream.isNotEmpty()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (error != null) {
                Text(
                    text = error,
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = if (isOwn) "Eigene Nachricht" else "Nachricht",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = ageText(message),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(Modifier.height(14.dp))
            REACTION_EMOJI.chunked(7).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (emoji in row) {
                        val active = message.myReaction == emoji
                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (active) TikTokTokens.AccentRing
                            else MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier
                                .size(42.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { onReaction(emoji) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = emoji,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            Text(
                text = "Eine Reaktion pro Nachricht. Tippen ersetzt sie, nochmal tippen entfernt sie.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                TextButton(onClick = onReply) { Text("Antworten") }
                if (isVideo) {
                    TextButton(onClick = onOfflineLater) { Text("Später im WLAN") }
                    TextButton(onClick = onOffline) { Text("Offline speichern") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                if (recallable) {
                    TextButton(onClick = onRecall) { Text("Zurückrufen") }
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
                TextButton(onClick = onDelete) {
                    Text("Endgültig löschen", color = ErrorRed)
                }
            }
        }
    }
}

private fun ageText(message: TikTokMessage): String {
    val age = System.currentTimeMillis() - message.timestamp
    if (message.timestamp <= 0L || age < 0L) return "Sendezeit unbekannt"
    val minutes = age / 60_000L
    val hours = age / 3_600_000L
    val days = age / 86_400_000L
    return when {
        minutes < 1 -> "Gerade eben gesendet."
        minutes < 60 -> "Vor $minutes Minuten gesendet."
        hours < 24 -> "Vor $hours Stunden gesendet."
        else -> "Vor $days Tagen gesendet."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(
    isConnected: Boolean,
    lastPush: Long,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isRecording by remember { mutableStateOf(TikTokDump.isActive(context)) }
    var count by remember { mutableIntStateOf(0) }
    var sizeBytes by remember { mutableLongStateOf(0L) }
    var bundlePath by remember { mutableStateOf("") }
    var sessionAgeMinutes by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(Unit) {
        count = TikTokDump.count(context)
        sizeBytes = TikTokDump.sizeBytes(context)
        sessionAgeMinutes = TikTokCookieRepository.sessionAgeMinutes(context)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Diagnose", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            Spacer(Modifier.height(10.dp))
            DiagnosticRow(
                label = "Live-Verbindung",
                value = if (isConnected) "verbunden" else "getrennt"
            )
            DiagnosticRow(
                label = "Letzter Push",
                value = if (lastPush <= 0L) "-" else relativeTime(lastPush) + " her"
            )
            DiagnosticRow(
                label = "Session",
                value = when {
                    sessionAgeMinutes < 0L -> "unbekannt"
                    sessionAgeMinutes < 60L -> "$sessionAgeMinutes min"
                    sessionAgeMinutes < 1440L -> "${sessionAgeMinutes / 60L} h"
                    else -> "${sessionAgeMinutes / 1440L} d"
                }
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Rohdaten aufzeichnen", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                Switch(
                    checked = isRecording,
                    onCheckedChange = { value ->
                        isRecording = value
                        TikTokDump.setActive(context, value)
                    }
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "$count Aufzeichnungen, ${sizeBytes / 1024L} KB",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Text(
                text = TikTokDump.folder(context).absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            if (bundlePath.isNotEmpty()) {
                Text(
                    text = "Paket: $bundlePath",
                    style = MaterialTheme.typography.labelSmall,
                    color = TikTokTokens.AccentRing
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Jede Anfrage und Antwort wird als Datei abgelegt, " +
                    "dazu eine Textdatei mit allen Feldern der Antwort.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
            ) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val removed = TikTokDump.clear(context)
                        count = 0
                        sizeBytes = 0L
                        bundlePath = ""
                        toast(context, "$removed Aufzeichnungen gelöscht")
                    }
                }) { Text("Leeren") }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val zip = TikTokDump.zip(context)
                        if (zip == null) {
                            toast(context, "Nichts zu bündeln")
                        } else {
                            bundlePath = zip.absolutePath
                        }
                    }
                }) { Text("Bündeln") }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        count = TikTokDump.count(context)
                        sizeBytes = TikTokDump.sizeBytes(context)
                    }
                }) { Text("Zählen") }
                TextButton(onClick = onClose) { Text("Fertig") }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                val block = buildString {
                    appendLine("Tabslify TikTok — Diagnose")
                    appendLine("connected=$isConnected")
                    appendLine("lastPush=$lastPush")
                    appendLine("sessionAgeMinutes=$sessionAgeMinutes")
                    appendLine("dumps=$count")
                    appendLine("dumpBytes=$sizeBytes")
                    appendLine("self=${TikTokDmClient.currentSelfId()}")
                    appendLine("openConv=${TikTokDmClient.openConversationId()}")
                }
                TikTokDiagnostics.copy(context = context, block = block)
            }) { Text("Status kopieren") }
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
    }
}
