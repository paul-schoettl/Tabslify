package com.tabslify.core.functions

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tabslify.core.activities.MainActivity
import com.tabslify.core.objects.Config
import com.tabslify.quiethoursnotificationhelper.laptopIp
import com.tabslify.tabs.audiorecordertab.AudioForegroundService
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

const val EXTRA_VOICE_START = "voice_start"

object VoiceState {
    @Volatile
    var recording: Boolean = false

    @Volatile
    var filePath: String? = null
}

private const val VOICE_CHANNEL = "voice_mode"
private const val VOICE_TAP_ID = 40002

fun voiceFile(context: Context): File =
    File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")

fun tryStartVoiceService(context: Context, path: String): Boolean {
    return try {
        val intent = Intent(context, AudioForegroundService::class.java).apply {
            putExtra("filePath", path)
            putExtra("isMediaRecorderMode", false)
        }
        ContextCompat.startForegroundService(context, intent)
        true
    } catch (_: Exception) {
        false
    }
}

fun startVoiceRecordingNow(context: Context): Boolean {
    if (VoiceState.recording) return true
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
        PackageManager.PERMISSION_GRANTED
    ) return false
    val file = voiceFile(context)
    VoiceState.filePath = file.absolutePath
    if (!tryStartVoiceService(context, file.absolutePath)) {
        VoiceState.filePath = null
        return false
    }
    return true
}

fun postVoiceTapNotification(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && try {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(VOICE_CHANNEL, "Voice Mode", NotificationManager.IMPORTANCE_HIGH)
        )
        val tap = PendingIntent.getActivity(
            context,
            VOICE_TAP_ID,
            Intent(context, MainActivity::class.java).apply {
                putExtra(EXTRA_VOICE_START, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, VOICE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("🎤 Voice Mode")
            .setContentText("Tippen zum Aufnehmen")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        manager.notify(VOICE_TAP_ID, notification)
        true
    } catch (_: Exception) {
        false
    }
}

fun replyVoice(obj: JSONObject) {
    if (laptopIp.isEmpty()) return
    val bytes = obj.toString().toByteArray(Charsets.UTF_8)
    val prefixed = ByteBuffer.allocate(4).putInt(bytes.size).array() + bytes
    try {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(laptopIp, Config.EXECUTE_RESPONSE_PORT), 3000)
            sock.getOutputStream().use { out ->
                out.write(prefixed)
                out.flush()
            }
        }
    } catch (_: Exception) {
    }
}

fun uploadVoiceFile(file: File): Boolean {
    return !(laptopIp.isEmpty() || !file.exists()) && try {
        Socket().use { sock ->
            sock.connect(InetSocketAddress(laptopIp, Config.VOICE_PORT), 3000)
            sock.getOutputStream().use { out ->
                out.write("${file.name}|audio/mp4\n".toByteArray(Charsets.UTF_8))
                file.inputStream().use { it.copyTo(out) }
                out.flush()
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
