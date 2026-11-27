package com.tabslify.tabs.tiktok

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.tabslify.core.objects.toast

object TikTokDiagnostics {

    private const val MAX_BLOCK = 1400

    fun describe(
        message: TikTokMessage,
        conversationName: String = ""
    ): String {
        val lines = mutableListOf<String>()
        lines += "Tabslify TikTok — unbekannter Nachrichtentyp"
        lines += "conv=${message.convId.ifEmpty { "-" }}"
        if (conversationName.isNotBlank()) lines += "chat=$conversationName"
        lines += "message=${message.id.ifEmpty { "-" }}"
        lines += "sender=${message.senderId.ifEmpty { "-" }}"
        lines += "timestamp=${message.timestamp}"
        lines += "text=${message.text.ifEmpty { "-" }}"
        lines += "mediaKind=${message.mediaKind.ifEmpty { "-" }}"
        if (message.unknownType.isNotBlank()) lines += "details=${message.unknownType}"
        return lines.joinToString("\n").take(MAX_BLOCK)
    }

    fun copy(context: Context, block: String, silent: Boolean = false) {
        runCatching {
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("TikTok Diagnose", block))
            if (!silent) toast(context, "Diagnose kopiert")
        }.onFailure {
            if (!silent) toast(context, "Kopieren fehlgeschlagen")
        }
    }
}
