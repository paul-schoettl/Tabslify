package com.tabslify.tabs.school.lexicon

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tabslify.quiethoursnotificationhelper.AiTarget
import com.tabslify.quiethoursnotificationhelper.aiSystemPrompt
import com.tabslify.quiethoursnotificationhelper.callVisionRawJpeg
import com.tabslify.tabs.school.uriToBitmap
import java.io.ByteArrayOutputStream

fun pageAsJpeg(context: Context, uri: Uri): ByteArray? {
    val bitmap = uriToBitmap(context, uri) ?: return null
    return try {
        ByteArrayOutputStream().use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) null else output.toByteArray()
        }
    } finally {
        bitmap.recycle()
    }
}

suspend fun ocrLexiconPage(
    context: Context,
    jpeg: ByteArray,
    page: Int,
    sourceHint: String?,
    onProgress: (String) -> Unit
): String {
    val extra = if (sourceHint.isNullOrBlank()) ""
    else " Bereits erkannte Quelle dieser Seite: $sourceHint. Übernimm sie nur, wenn keine neue Nummer im Bild steht."
    val prompt = "Lies diese Verzeichnis-Seite ($page) ein.$extra"
    return callVisionRawJpeg(
        context = context,
        jpeg = jpeg,
        prompt = prompt,
        systemPrompt = aiSystemPrompt(AiTarget.VisionLexicon),
        target = AiTarget.VisionLexicon,
        onProgress = onProgress
    )
}
