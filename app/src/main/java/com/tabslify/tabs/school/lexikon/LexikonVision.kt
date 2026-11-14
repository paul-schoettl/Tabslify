package com.tabslify.tabs.school.lexikon

import android.content.Context
import android.graphics.Bitmap
import com.tabslify.quiethoursnotificationhelper.AiTarget
import com.tabslify.quiethoursnotificationhelper.aiSystemPrompt
import com.tabslify.quiethoursnotificationhelper.callVisionRaw

const val LEXIKON_MAX_PX = 2048

suspend fun ocrLexikonSeite(
    context: Context,
    bmp: Bitmap,
    seite: Int,
    quelleHinweis: String?,
    onProgress: (String) -> Unit
): String {
    val zusatz = if (quelleHinweis.isNullOrBlank()) ""
    else " Bereits erkannte Quelle dieser Seite: $quelleHinweis. Übernimm sie nur, wenn keine neue Nummer im Bild steht."
    val prompt = "Lies diese Verzeichnis-Seite ($seite) ein.$zusatz"
    return callVisionRaw(
        context = context,
        bmp = bmp,
        prompt = prompt,
        systemPrompt = aiSystemPrompt(AiTarget.VisionLexikon),
        target = AiTarget.VisionLexikon,
        maxPx = LEXIKON_MAX_PX,
        onProgress = onProgress
    )
}
