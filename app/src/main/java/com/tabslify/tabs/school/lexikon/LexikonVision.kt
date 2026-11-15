package com.tabslify.tabs.school.lexikon

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.tabslify.quiethoursnotificationhelper.AiTarget
import com.tabslify.quiethoursnotificationhelper.aiSystemPrompt
import com.tabslify.quiethoursnotificationhelper.callVisionRawJpeg
import com.tabslify.tabs.school.uriToBitmap
import java.io.ByteArrayOutputStream

fun seiteAlsJpeg(context: Context, uri: Uri): ByteArray? {
    val bmp = uriToBitmap(context, uri) ?: return null
    return try {
        ByteArrayOutputStream().use { aus ->
            if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, aus)) null else aus.toByteArray()
        }
    } finally {
        bmp.recycle()
    }
}

suspend fun ocrLexikonSeite(
    context: Context,
    jpeg: ByteArray,
    seite: Int,
    quelleHinweis: String?,
    onProgress: (String) -> Unit
): String {
    val zusatz = if (quelleHinweis.isNullOrBlank()) ""
    else " Bereits erkannte Quelle dieser Seite: $quelleHinweis. Übernimm sie nur, wenn keine neue Nummer im Bild steht."
    val prompt = "Lies diese Verzeichnis-Seite ($seite) ein.$zusatz"
    return callVisionRawJpeg(
        context = context,
        jpeg = jpeg,
        prompt = prompt,
        systemPrompt = aiSystemPrompt(AiTarget.VisionLexikon),
        target = AiTarget.VisionLexikon,
        onProgress = onProgress
    )
}
