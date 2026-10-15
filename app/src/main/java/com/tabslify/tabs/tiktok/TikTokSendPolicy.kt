package com.tabslify.tabs.tiktok

object TikTokSendPolicy {

    val SELF_HANDLE_BY_UID = mapOf(
        "7469075901474161686" to "paluss7_2",
        "7469077275818066977" to "programmi"
    )

    val SEND_ALLOWED_UIDS = SELF_HANDLE_BY_UID.keys

    val SEND_RECIPIENT_UIDS = setOf("7469077275818066977")

    val SELF_HANDLES = SELF_HANDLE_BY_UID.values.toSet()

    fun convUidsOf(convId: String): List<String> =
        convId.split(':').filter { it.length >= 5 }.distinct()

    fun blockedForeign(convId: String): List<String> {
        val uids = convUidsOf(convId)
        if (uids.isEmpty()) return listOf("convId ohne UID")
        return uids.filter { it !in SEND_ALLOWED_UIDS }
    }

    fun handleBlockedReason(convId: String): String? {
        val foreign = blockedForeign(convId)
        return if (foreign.isEmpty()) null else foreign.joinToString(",")
    }

    fun recipientAllowed(uniqueId: String): Boolean = uniqueId in SEND_RECIPIENT_UIDS
}
