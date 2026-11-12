package com.tabslify.quiethoursnotificationhelper

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.getBroadcast
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Bundle
import com.tabslify.core.logging.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.tabslify.R
import com.tabslify.core.activities.Tabslify.Companion.appScope
import com.tabslify.core.functions.errorInsert
import com.tabslify.core.functions.showSimpleNotificationExtern
import com.tabslify.core.objects.Config.cms
import com.tabslify.core.objects.tNotify
import com.tabslify.services.QuietHoursNotificationService
import com.tabslify.services.QuietHoursNotificationService.Companion.ACTION_MARK_PARTS_READ
import com.tabslify.services.QuietHoursNotificationService.Companion.ACTION_MESSAGE_SENT
import com.tabslify.services.QuietHoursNotificationService.Companion.EXTRA_MESSAGE_ID
import com.tabslify.services.QuietHoursNotificationService.Companion.EXTRA_SENDER
import com.tabslify.services.QuietHoursNotificationService.Companion.MAX_MESSAGES_PER_CONTACT
import com.tabslify.services.QuietHoursNotificationService.Companion.isSupportedMessenger
import com.tabslify.services.QuietHoursNotificationService.Companion.readMessageIds
import com.tabslify.services.WhatsAppNotificationListener
import com.tabslify.tabs.aitab.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

private const val GEMINI_CHAT_PREFIX = "Gemini Chat"

private fun resolveKey(sender: String): String? {
    if (sender.contains("|")) return sender
    if (sender.startsWith(GEMINI_CHAT_PREFIX)) return sender
    return WhatsAppNotificationListener.messagesByContact.keys
        .firstOrNull { it.endsWith("|$sender") }
        ?: WhatsAppNotificationListener.replyActions.keys
            .firstOrNull { it.endsWith("|$sender") }
}

private fun buildReplyAction(
    key: String,
    notificationId: Int,
    context: Context
): NotificationCompat.Action {
    val replyBase = Intent(ACTION_MESSAGE_SENT)
    replyBase.putExtra(EXTRA_SENDER, key)
    replyBase.setPackage(context.packageName)
    val pi = getBroadcast(
        context, notificationId,
        replyBase,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )
    return NotificationCompat.Action.Builder(
        android.R.drawable.ic_menu_send, context.getString(R.string.qh_msg_reply_action), pi
    )
        .addRemoteInput(RemoteInput.Builder("key_text_reply").setLabel(context.getString(R.string.qh_msg_reply_label)).build())
        .setShowsUserInterface(false)
        .setAllowGeneratedReplies(false)
        .build()
}

private fun postChatNotification(key: String, context: Context, sourceLabel: String) {
    try {
        val displayName = if (key.contains("|")) key.substringAfter("|") else key
        val notifId = key.hashCode() and 0x0FFFFFFF
        val summaryId = notifId + 1000000
        val messagingId = notifId + 2000000
        val nm = context.getSystemService(NotificationManager::class.java)

        val mePerson = Person.Builder().setName(context.getString(R.string.qh_msg_me)).setKey("me").build()
        val senderPerson = Person.Builder().setName(displayName).setKey(displayName).build()
        val messages = WhatsAppNotificationListener.messagesByContact[key] ?: emptyList()

        val style = NotificationCompat.MessagingStyle(mePerson)
            .setConversationTitle("$sourceLabel · $displayName")

        var partIndex = 0

        messages.takeLast(5).forEach { msg ->
            val msgId = "${key}_${msg.timestamp}"
            if (readMessageIds.contains(msgId)) return@forEach

            val timeText =
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))
            val text = msg.text

            if (text.length > 200) {
                val parts = text.chunked(200)
                parts.reversed().forEachIndexed { idx, part ->
                    val partId = notifId + partIndex
                    partIndex++
                    val markBase = Intent(context, QuietHoursNotificationService::class.java)
                    markBase.action = ACTION_MARK_PARTS_READ
                    markBase.putExtra(EXTRA_MESSAGE_ID, msgId)
                    markBase.setPackage(context.packageName)
                    val markPi = PendingIntent.getService(
                        context, partId + 500000,
                        markBase,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val partNum = parts.size - idx
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        nm.notify(
                            partId, NotificationCompat.Builder(context, "Nachrichten")
                                .setSmallIcon(android.R.drawable.stat_notify_chat)
                                .setContentTitle(context.getString(R.string.qh_msg_part_title, sourceLabel, displayName, partNum, parts.size))
                                .setContentText(part.take(100) + if (part.length > 100) "..." else "")
                                .setStyle(
                                    NotificationCompat.BigTextStyle()
                                        .bigText(part)
                                        .setBigContentTitle(context.getString(R.string.qh_msg_part_title, sourceLabel, displayName, partNum, parts.size))
                                        .setSummaryText("⏰ $timeText")
                                )
                                .setPriority(NotificationCompat.PRIORITY_HIGH)
                                .setGroup("long_$key")
                                .setAutoCancel(false)
                                .addAction(
                                    android.R.drawable.ic_menu_close_clear_cancel,
                                    context.getString(R.string.qh_msg_mark_read),
                                    markPi
                                )
                                .build()
                        )
                    }
                }
            } else {
                style.addMessage(
                    NotificationCompat.MessagingStyle.Message(
                        "$text • $timeText",
                        msg.timestamp,
                        if (msg.isOwnMessage) mePerson else senderPerson
                    )
                )
            }
        }

        if (partIndex > 0) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                nm.notify(
                    summaryId, NotificationCompat.Builder(context, "Nachrichten")
                        .setSmallIcon(android.R.drawable.stat_notify_chat)
                        .setContentTitle(context.getString(R.string.qh_msg_long_title, sourceLabel, displayName))
                        .setContentText(context.resources.getQuantityString(R.plurals.qh_msg_parts_count, partIndex, partIndex))
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setGroup("long_$key")
                        .setGroupSummary(true)
                        .setAutoCancel(true)
                        .build()
                )
            }
        }

        val notification = NotificationCompat.Builder(context, "Nachrichten")
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setStyle(style)
            .setAutoCancel(false)
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .addAction(buildReplyAction(key, messagingId, context))
            .build()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) {
            nm.notify(messagingId, notification)
        }
    } catch (e: Exception) {
        Log.e("Messages", "postChatNotification failed: ${e.message}")
    }
}

fun updateSingleSenderNotification(sender: String, context: Context) {
    val key = resolveKey(sender) ?: run {
        Log.w("Messages", "No key for sender: $sender")
        return
    }
    if (WhatsAppNotificationListener.messagesByContact[key] == null) {
        Log.w("Messages", "No messages for key: $key")
        return
    }
    postChatNotification(key, context, context.getString(R.string.qh_msg_source_new))
}

fun updateChatNotification(key: String, context: Context) {
    postChatNotification(key, context, context.getString(R.string.qh_msg_source_chat))
}

fun showUnreadMessages(context: Context) {
    val msgs = WhatsAppNotificationListener.messagesByContact
    if (msgs.isEmpty()) {
        showSimpleNotificationExtern(
            context.getString(R.string.qh_msg_no_unanswered),
            context.getString(R.string.qh_msg_all_answered),
            context = context,
            silent = false
        )
        return
    }
    msgs.keys.forEach { key -> postChatNotification(key, context, context.getString(R.string.qh_msg_source_unread)) }
}

fun handleMessageSent(sender: String, messageText: String, context: Context) {
    val key = resolveKey(sender) ?: sender
    val displayName = if (key.contains("|")) key.substringAfter("|") else key

    try {
        if (displayName == GEMINI_CHAT_PREFIX || displayName.startsWith("$GEMINI_CHAT_PREFIX:")) {
            val list =
                WhatsAppNotificationListener.messagesByContact.getOrPut(key) { mutableListOf() }
            val trimmed = messageText.trim()
            if (trimmed.isNotEmpty()) {
                list.add(
                    WhatsAppNotificationListener.Companion.ChatMessage(
                        trimmed,
                        System.currentTimeMillis(),
                        true
                    )
                )
            }
            pruneContactMessages(list)
            updateChatNotification(key, context)

            val appContext = context.applicationContext
            appScope.launch {
                val snapshot = mutableListOf<ChatMessage>()
                list.dropLast(1).forEach { obj ->
                    snapshot.add(
                        ChatMessage(
                            text = obj.text,
                            ts = obj.timestamp,
                            own = obj.isOwnMessage,
                            mode = "AI"
                        )
                    )
                }
                val answer = sendAiRequest(appContext, userMessage = trimmed, history = snapshot, target = AiTarget.NotificationReply, serviceKey = "chat")
                if (!answer.isNullOrBlank()) {
                    list.add(
                        WhatsAppNotificationListener.Companion.ChatMessage(
                            answer,
                            System.currentTimeMillis(),
                            false
                        )
                    )
                    pruneContactMessages(list)
                    withContext(Dispatchers.Main) { updateChatNotification(key, appContext) }
                } else {
                    withContext(Dispatchers.Main) {
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_msg_chat_error_title),
                            context.getString(R.string.qh_msg_answer_failed),
                            context = appContext
                        )
                    }
                }
            }
            return
        }

        val replyData = WhatsAppNotificationListener.replyActions[key]

        if (replyData == null) {
            Log.e(
                "Messages",
                "replyData ist null für key='$key'. replyActions-Keys: ${WhatsAppNotificationListener.replyActions.keys}"
            )
            showSimpleNotificationExtern(
                context.getString(R.string.qh_msg_no_reply_data_title),
                context.getString(R.string.qh_msg_no_reply_data_text, key),
                context = context,
                silent = false
            )
            return
        }

        val creatorPackage = replyData.pendingIntent.creatorPackage
        if (creatorPackage == null) {
            Log.e("Messages", "creatorPackage ist null für key='$key'")
            showSimpleNotificationExtern(
                context.getString(R.string.qh_msg_no_package_title),
                context.getString(R.string.qh_msg_no_package_text),
                context = context,
                silent = false
            )
            return
        }

        val supported =
            replyData.pendingIntent.creatorPackage?.let { isSupportedMessenger(it) } ?: false
        Log.d("Messages", "creatorPackage='$creatorPackage', supported=$supported")

        if (!supported) {
            Log.w("Messages", "Unsupported messenger: ${replyData.pendingIntent.creatorPackage}")
            showSimpleNotificationExtern(
                context.getString(R.string.qh_msg_unsupported_title),
                context.getString(R.string.qh_msg_unsupported_text, replyData.pendingIntent.creatorPackage),
                20.seconds, context,
                silent = false
            )
            return
        }

        try {
            val intent = Intent().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val bundle =
                Bundle().apply { putCharSequence(replyData.originalResultKey, messageText) }
            val ri = RemoteInput.Builder(replyData.originalResultKey)
                .setLabel(replyData.remoteInput.label)
                .setChoices(replyData.remoteInput.choices)
                .setAllowFreeFormInput(replyData.remoteInput.allowFreeFormInput)
                .build()
            RemoteInput.addResultsToIntent(arrayOf(ri), intent, bundle)
            replyData.pendingIntent.send(context, 0, intent)
        } catch (e: Exception) {
            Log.e("Messages", "Send failed: ${e.message}")
            showSimpleNotificationExtern(
                context.getString(R.string.fehler), context.getString(R.string.qh_msg_send_failed), context = context,
                silent = false
            )
            return
        }

        val list = WhatsAppNotificationListener.messagesByContact.getOrPut(key) { mutableListOf() }
        val isDup = list.takeLast(3).any { it.text == messageText && messageText.length > 5 }
        if (!isDup) {
            list.add(
                WhatsAppNotificationListener.Companion.ChatMessage(
                    messageText,
                    System.currentTimeMillis(),
                    true
                )
            )
        }

        updateChatNotification(key, context)

        pruneContactMessages(list)

    } catch (e: Exception) {
        Log.e("Messages", "handleMessageSent error: ${e.message}")
    }
}

fun pruneContactMessages(
    list: MutableList<WhatsAppNotificationListener.Companion.ChatMessage>,
    maxMessages: Int = MAX_MESSAGES_PER_CONTACT
) {
    val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
    list.removeAll { it.timestamp < cutoff }
    if (list.size > maxMessages) {
        list.subList(0, list.size - maxMessages).clear()
    }
}

fun markMessageAsRead(messageId: String, readMessageIds: MutableSet<String>, context: Context) {
    try {
        if (readMessageIds.size > 200) {
            val toRemove = readMessageIds.take(100)
            readMessageIds.removeAll(toRemove.toSet())
        }
        readMessageIds.add(messageId)
        val key = messageId.substringBeforeLast("_")
        val notifId = key.hashCode() and 0x0FFFFFFF
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        for (i in 0 until 100) nm.cancel(notifId + i)
        nm.cancel(notifId + 1000000)
    } catch (e: Exception) {
        errorInsert("markMessageAsRead", "ERROR: ${e.message}", Instant.now().toString(), "ERROR")
    }
}

fun createGeminiChat(name: String?, context: Context, replace: Boolean) {
    if (replace) WhatsAppNotificationListener.messagesByContact.remove(GEMINI_CHAT_PREFIX)
    val title = if (name.isNullOrBlank()) GEMINI_CHAT_PREFIX else "$GEMINI_CHAT_PREFIX: $name"
    val list = WhatsAppNotificationListener.messagesByContact.getOrPut(title) { mutableListOf() }
    if (list.isEmpty()) {
        list.add(
            WhatsAppNotificationListener.Companion.ChatMessage(
                context.getString(R.string.qh_msg_gemini_created, title),
                System.currentTimeMillis(), false
            )
        )
    }
    updateChatNotification(title, context)
}

fun extractLastMessage(context: Context) {
    val msgs = WhatsAppNotificationListener.messagesByContact
    if (msgs.isEmpty()) {
        showSimpleNotificationExtern(
            context.getString(R.string.qh_msg_no_messages_title),
            context.getString(R.string.qh_msg_no_messages_text),
            context = context
        )
        return
    }
    var newestMsg: WhatsAppNotificationListener.Companion.ChatMessage? = null
    var newestKey = ""
    msgs.forEach { (key, list) ->
        val last = list.lastOrNull()
        if (last != null && (newestMsg == null || last.timestamp > newestMsg.timestamp)) {
            newestMsg = last; newestKey = key
        }
    }
    val msg = newestMsg ?: return
    val displayName = if (newestKey.contains("|")) newestKey.substringAfter("|") else newestKey
    val timeText = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(msg.timestamp))
    val builder = NotificationCompat.Builder(context, "Nachrichten")
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle(context.getString(R.string.qh_msg_extracted_title))
        .setContentText(context.getString(R.string.qh_msg_from_at, displayName, timeText))
        .setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.qh_msg_from_big, displayName, timeText, msg.text))
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
    if (msg.imageUri != null) {
        try {
            val source = ImageDecoder.createSource(context.contentResolver, msg.imageUri)
            val bmp = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val targetSize = 1024
                if (info.size.width > targetSize || info.size.height > targetSize) {
                    val scale =
                        (info.size.width.toFloat() / targetSize).coerceAtLeast(info.size.height.toFloat() / targetSize)
                    decoder.setTargetSize(
                        (info.size.width / scale).toInt(),
                        (info.size.height / scale).toInt()
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            builder.setStyle(
                NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as Bitmap?)
            )
            builder.setLargeIcon(bmp)
        } catch (e: Exception) {
            Log.e("Messages", "extractLastMessage image error: ${e.message}")
        }
    }
    tNotify(context, cms(), builder.build())
}