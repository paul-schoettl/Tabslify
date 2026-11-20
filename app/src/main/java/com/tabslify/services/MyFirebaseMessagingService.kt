package com.tabslify.services

import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.tabslify.core.activities.MainActivity
import com.tabslify.core.activities.Tabslify
import com.tabslify.core.activities.fetchAndRun
import com.tabslify.core.logging.Log
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.emailNotificationId
import com.tabslify.core.objects.prvt
import com.tabslify.core.objects.tNotify
import com.tabslify.services.QuietHoursNotificationService.Companion.AI_NOTIFY_CHANNEL_ID
import com.tabslify.tabs.ainotify.AI_NOTIFY_CHANNEL
import com.tabslify.tabs.ainotify.AI_NOTIFY_KIND_CANCEL
import com.tabslify.tabs.ainotify.AI_NOTIFY_KIND_QUESTIONS
import com.tabslify.tabs.ainotify.AI_NOTIFY_TAG
import com.tabslify.tabs.ainotify.AI_NOTIFY_TOPIC
import com.tabslify.tabs.ainotify.AiNotifyQuestion
import com.tabslify.tabs.ainotify.AiNotifySession
import com.tabslify.tabs.ainotify.AiNotifyStore
import com.tabslify.tabs.tiktok.TIKTOK_PUSH_CHANNEL
import com.tabslify.tabs.tiktok.TIKTOK_PUSH_CHANNEL_ID
import com.tabslify.tabs.tiktok.TIKTOK_PUSH_TAG
import com.tabslify.tabs.tiktok.TIKTOK_PUSH_TARGET
import com.tabslify.tabs.tiktok.TIKTOK_PUSH_TOPIC
import com.tabslify.tabs.tiktok.TikTokDmClient
import com.tabslify.tabs.tiktok.TikTokPushStore
import com.tabslify.tabs.tiktok.tiktokPushNotificationId
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class MyFirebaseMessagingService : FirebaseMessagingService() {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FCM", "Token erneuert")
        FirebaseMessaging.getInstance()
            .subscribeToTopic("all_users")
        if (prvt()) {
            FirebaseMessaging.getInstance()
                .subscribeToTopic(AI_NOTIFY_TOPIC)
            FirebaseMessaging.getInstance()
                .subscribeToTopic("emails")
            FirebaseMessaging.getInstance()
                .subscribeToTopic(TIKTOK_PUSH_TOPIC)
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("FCM", "Message received: $remoteMessage")
        if (remoteMessage.data["channel"] == TIKTOK_PUSH_CHANNEL && prvt()) {
            handleTikTokPush(remoteMessage.data)
            return
        }
        if (remoteMessage.data["channel"] == AI_NOTIFY_CHANNEL && prvt()) {
            handleAiNotify(remoteMessage.data)
            return
        }
        if (remoteMessage.data["target"] == "gmail_cancel" && prvt()) {
            val account = remoteMessage.data["account"]
            val uids = remoteMessage.data["uids"].orEmpty()
            if (account != null && uids.isNotEmpty()) {
                val manager = applicationContext.getSystemService(NotificationManager::class.java)
                for (uid in uids.split(',')) {
                    if (uid.isEmpty()) continue
                    manager?.cancel(Config.EMAIL_NOTIF_TAG, emailNotificationId(account, uid))
                }
            }
            return
        }
        if (remoteMessage.from?.endsWith("emails") == true && prvt()) {
            Log.d("FCM", "Email notification received")
            val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: return
            val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
            Log.d("FCM", "Title: $title, Body: $body")
            val account = remoteMessage.data["account"]
            val uid = remoteMessage.data["uid"]
            val notifId = if (account != null && uid != null) {
                emailNotificationId(account, uid)
            } else {
                System.currentTimeMillis().toInt()
            }

            val intentBase = Intent(applicationContext, MainActivity::class.java)
            intentBase.action = Intent.ACTION_VIEW
            intentBase.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            intentBase.putExtra("target", "gmail")
            if (account != null && uid != null) {
                intentBase.putExtra("email_account", account)
                intentBase.putExtra("email_uid", uid)
            }
            intentBase.setPackage(applicationContext.packageName)
            val pendingIntent = PendingIntent.getActivity(
                applicationContext,
                System.currentTimeMillis().toInt(),
                intentBase,
                PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(applicationContext, "show_simple_not_channel")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setGroup("SSN")
                .setSilent(false)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
            val notifTag = if (account != null && uid != null) Config.EMAIL_NOTIF_TAG else null
            tNotify(applicationContext, notifId, notification, notifTag)
            return
        }
        val scriptName = remoteMessage.data["script_name"] ?: return
        val appContext = applicationContext
        Tabslify.serviceScope.launch {
            try {
                fetchAndRun(scriptName, appContext)
            } catch (_: Exception) {
            }
        }
    }

    private fun handleTikTokPush(data: Map<String, String>) {
        val appContext = applicationContext
        val convId = data["conv_id"].orEmpty()
        if (convId.isEmpty()) return
        if (TikTokPushStore.isOwnReport(appContext, data["origin"].orEmpty())) return
        val pushTitle = data["title"]?.takeIf { it.isNotEmpty() }
            ?: data["name"]?.takeIf { it.isNotEmpty() }
        val pushBody = data["body"]?.takeIf { it.isNotEmpty() }
            ?: data["text"]?.takeIf { it.isNotEmpty() }
        val conversation = TikTokPushStore.conversation(appContext, convId)
        val name = pushTitle
            ?: conversation?.name?.takeIf { it.isNotEmpty() }
            ?: TikTokPushStore.rememberedName(appContext, convId).takeIf { it.isNotEmpty() }
        val body = pushBody
            ?: conversation?.lastMessage?.takeIf { it.isNotEmpty() }
            ?: TikTokPushStore.rememberedText(appContext, convId).takeIf { it.isNotEmpty() }
            ?: "Neue Nachricht"
        if (pushTitle != null || pushBody != null) {
            TikTokPushStore.remember(appContext, convId, pushTitle.orEmpty(), pushBody.orEmpty())
        }
        postTikTokNotification(appContext, convId, tiktokTitle(name), body)
        if (pushBody == null || name == null) {
            Tabslify.serviceScope.launch {
                try {
                    TikTokDmClient.load(appContext)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("FCM", "TikTok-Nachladen fehlgeschlagen: ${e.message}")
                    return@launch
                }
                val resolvedName = TikTokPushStore.conversation(appContext, convId)?.name
                    ?.takeIf { it.isNotEmpty() }
                    ?: TikTokPushStore.rememberedName(appContext, convId).takeIf { it.isNotEmpty() }
                    ?: resolvePeerName(appContext, convId)
                    ?: name
                val resolvedBody = TikTokPushStore.conversation(appContext, convId)?.lastMessage
                    ?.takeIf { it.isNotEmpty() }
                    ?: TikTokPushStore.rememberedText(appContext, convId).takeIf { it.isNotEmpty() }
                    ?: body
                if (resolvedName == name && resolvedBody == body) return@launch
                TikTokPushStore.remember(appContext, convId, resolvedName.orEmpty(), resolvedBody)
                if (resolvedName != null && resolvedName != name) {
                    val peer = convId.split(':')
                        .lastOrNull { it.length >= 5 && it != TikTokDmClient.currentSelfId() }
                        .orEmpty()
                    TikTokPushStore.reportName(appContext, convId, peer, resolvedName)
                }
                postTikTokNotification(appContext, convId, tiktokTitle(resolvedName), resolvedBody)
            }
        }
    }

    private suspend fun resolvePeerName(appContext: android.content.Context, convId: String): String? {
        val self = TikTokDmClient.currentSelfId()
        val peer = convId.split(':').lastOrNull { it.length >= 5 && it != self }.orEmpty()
        if (peer.isEmpty()) return null
        return try {
            TikTokDmClient.resolvePeerName(appContext, peer)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private fun tiktokTitle(name: String?): String =
        if (name.isNullOrBlank()) "TikTok" else "TikTok - $name"

    private fun postTikTokNotification(
        appContext: android.content.Context,
        convId: String,
        title: String,
        body: String
    ) {
        val intentBase = Intent(appContext, MainActivity::class.java)
        intentBase.action = Intent.ACTION_VIEW
        intentBase.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        intentBase.setPackage(appContext.packageName)
        intentBase.putExtra("target", TIKTOK_PUSH_TARGET)
        intentBase.putExtra("tiktok_conv", convId)
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            System.currentTimeMillis().toInt(),
            intentBase,
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(appContext, TIKTOK_PUSH_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setGroup("SSN").setSilent(false)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
        tNotify(appContext, tiktokPushNotificationId(convId), builder.build(), TIKTOK_PUSH_TAG)
    }

    private fun handleAiNotify(data: Map<String, String>) {
        val appContext = applicationContext
        val sessionId = data["session_id"]?.takeIf { it.isNotEmpty() }
            ?: System.currentTimeMillis().toString()
        if (data["kind"] == AI_NOTIFY_KIND_CANCEL) {
            AiNotifyStore.markCancelled(appContext, sessionId)
            AiNotifyStore.removeSession(appContext, sessionId)
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager?.cancel(AI_NOTIFY_TAG, sessionId.hashCode())
            return
        }
        if (AiNotifyStore.isCancelled(appContext, sessionId)) return
        val title = data["title"].orEmpty().ifEmpty { data["source"].orEmpty() }
        val body = data["body"].orEmpty()
        val source = data["source"].orEmpty()
        val intentBase = Intent(appContext, MainActivity::class.java)
        intentBase.action = Intent.ACTION_VIEW
        intentBase.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        intentBase.setPackage(appContext.packageName)
        var priority = NotificationCompat.PRIORITY_DEFAULT
        if (data["kind"] == AI_NOTIFY_KIND_QUESTIONS) {
            val questions = runCatching {
                Json.decodeFromString<List<AiNotifyQuestion>>(data["questions"].orEmpty())
            }.getOrNull().orEmpty()
            if (questions.isNotEmpty()) {
                AiNotifyStore.saveSession(
                    appContext,
                    AiNotifySession(sessionId, title, body, source, questions)
                )
                intentBase.putExtra("target", "ai_questions")
                intentBase.putExtra("ai_session_id", sessionId)
                priority = NotificationCompat.PRIORITY_HIGH
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            sessionId.hashCode(),
            intentBase,
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(appContext, AI_NOTIFY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .setGroup("SSN")
            .setSilent(false)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
        if (source.isNotEmpty()) builder.setSubText(source)
        tNotify(appContext, sessionId.hashCode(), builder.build(), AI_NOTIFY_TAG)
    }
}
