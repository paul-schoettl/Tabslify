package com.tabslify.quiethoursnotificationhelper

import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Context.CAMERA_SERVICE
import android.content.Context.MODE_PRIVATE
import android.content.Context.WINDOW_SERVICE
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.camera2.CameraManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import com.tabslify.core.logging.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.OnBackPressedDispatcherOwner
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.tabslify.core.activities.Tabslify.Companion.appScope
import com.tabslify.core.activities.Tabslify.Companion.serviceScope
import com.tabslify.R
import com.tabslify.core.functions.showSimpleNotificationExtern
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.Config.SHOWCOMMANDS
import com.tabslify.core.objects.Config.client
import com.tabslify.core.objects.Config.fetchBWMP
import com.tabslify.core.objects.prvt
import com.tabslify.core.ui.showBatteryInfo
import com.tabslify.services.MediaPlayerService
import com.tabslify.services.MusicPlayerServiceCompat
import com.tabslify.services.OverlayLifecycleOwner
import com.tabslify.services.PodcastPlayerServiceCompat
import com.tabslify.services.QuietHoursNotificationService
import com.tabslify.services.QuietHoursNotificationService.Companion.CHANNEL_ID
import com.tabslify.services.QuietHoursNotificationService.Companion.commandHistory
import com.tabslify.services.QuietHoursNotificationService.Companion.showtestOverlay
import com.tabslify.tabs.OtherBucketViewer
import com.tabslify.tabs.audiorecordertab.AudioForegroundService
import com.tabslify.tabs.fetchWeatherForecast
import com.tabslify.tabs.getLastKnownLocation
import com.tabslify.tabs.mediaplayer.AlgorithmicPlaylistRegistry
import com.tabslify.tabs.mediaplayer.MediaAnalyticsManager
import com.tabslify.tabs.mediaplayer.MediaAnalyticsManager.rebuildSessions
import com.tabslify.tabs.mediaplayer.PodcastShowManager
import com.tabslify.tabs.weathernot
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class Command(
    val name: String,
    val aliases: List<String> = emptyList(),
    val description: String,
    val action: () -> Unit
)

private var testOverlayView: ComposeView? = null
private var testOverlayLifecycle: OverlayLifecycleOwner? = null


@SuppressLint("MissingPermission")
fun getHomeWifiStatus(
    context: Context,
    homeSsid: String,
    onResult: (isHome: Boolean) -> Unit
) {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val activeNetwork = cm.activeNetwork
    val caps = cm.getNetworkCapabilities(activeNetwork)
    val hasWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    if (!hasWifi) {
        onResult(false)
        return
    }

    val request = NetworkRequest.Builder()
        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .build()

    val callback = object : ConnectivityManager.NetworkCallback(
        FLAG_INCLUDE_LOCATION_INFO
    ) {
        private var hasFired = false

        override fun onCapabilitiesChanged(
            network: Network,
            caps: NetworkCapabilities
        ) {
            if (hasFired) return

            val wifiInfo = caps.transportInfo as? WifiInfo
            val ssid = wifiInfo?.ssid
                ?.removePrefix("\"")
                ?.removeSuffix("\"")
                ?.takeIf { it != "<unknown ssid>" }

            hasFired = true
            onResult(ssid != null && ssid.equals(homeSsid, ignoreCase = true))
            cm.unregisterNetworkCallback(this)
        }

        override fun onUnavailable() {
            if (hasFired) return
            hasFired = true
            onResult(false)
            cm.unregisterNetworkCallback(this)
        }
    }

    cm.registerNetworkCallback(request, callback)
}

private fun getAvailableCommands(context: Context): List<Command> {
    return listOf(
        Command(
            name = "flo",
            description = context.getString(R.string.qh_cmd_desc_flo)
        ) {
            val env = Environment.getExternalStorageDirectory()

            listOf(
                File(env, "Download/Cloud"),
                File(env, "Podcasts/Cloud"),
                File(env, "Music/Cloud")
            ).forEach { it.deleteRecursively() }

            listOf(
                File(env, "Podcasts/Tabslify"),
                File(env, "Music/Tabslify")
            ).forEach { it.mkdirs() }
        },
        Command(
            name = "whatsapp",
            aliases = listOf("wh", "wa", "messages", "msg", "nachrichten"),
            description = context.getString(R.string.qh_cmd_desc_whatsapp)
        ) {
            showUnreadMessages(context)
        },
        // Ausgeblendet in public version:
        Command(
            name = "t",
            aliases = listOf(),
            description = context.getString(R.string.qh_cmd_desc_whatsapp)
        ) {


//            val intent = Intent(context, QuietHoursNotificationService::class.java).apply {
//                action = ACTION_SCHOOL_DAY_SUMMARY
//            }
//            val pending = PendingIntent.getService(
//                context, 9001, intent,
//                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
//            )
//
//            pending.send()
        },
        Command(
            name = "ai",
            aliases = listOf("aichat", "aiChat", "geminiChat", "gm", "newai", "newaichat", "na", "gemini"),
            description = context.getString(R.string.qh_cmd_desc_ai)
        ) {},
        Command(
            name = "music",
            aliases = listOf("m", "play", "player", "musik"),
            description = context.getString(R.string.qh_cmd_desc_music)
        ) {
            MediaPlayerService.startAndPlayMusic(context, null)
        },
        Command(
            name = "podcast",
            aliases = listOf("pd", "pc", "Podcast", "py"),
            description = context.getString(R.string.qh_cmd_desc_podcast)
        ) {
            PodcastPlayerServiceCompat.startService(context)
            PodcastPlayerServiceCompat.sendPlayAction(context)
        },
        Command(
            name = "managepodcast",
            aliases = listOf("mpd", "ManagePodcast"),
            description = context.getString(R.string.qh_cmd_desc_managepodcast)
        ) {
            PodcastPlayerServiceCompat.managePodcast(context)
        },
        Command(
            name = "queue",
            aliases = listOf("q", "warteschlange", "playlist"),
            description = context.getString(R.string.qh_cmd_desc_queue)
        ) {
            showPodcastQueue(context)
        },
        Command(
            name = "qadd",
            aliases = listOf("qa", "queueadd", "addqueue"),
            description = context.getString(R.string.qh_cmd_desc_qadd)
        ) {
            showSimpleNotificationExtern(
                "ℹ️ Queue Add",
                context.getString(R.string.qh_cmd_help_qadd),
                context = context
            )
        },
        Command(
            name = "qremove",
            aliases = listOf("qr", "queueremove", "removequeue"),
            description = context.getString(R.string.qh_cmd_desc_qremove)
        ) {
            showSimpleNotificationExtern(
                "ℹ️ Queue Remove",
                "Syntax: qremove [position in queue 1-X]",
                context = context
            )
        },
        Command(
            name = "qclear",
            aliases = listOf("qc", "queueclear", "clearqueue"),
            description = context.getString(R.string.qh_cmd_desc_qclear)
        ) {
            clearPodcastQueue(context)
        },
        Command(
            name = "voice",
            aliases = listOf("v", "voicenote", "sprachnachricht", "audio"),
            description = context.getString(R.string.qh_cmd_desc_voice)
        ) {
            if (prvt()) playLatestVoiceNote("Manual Command", context)
        },
        Command(
            name = "record",
            aliases = listOf("rec", "aufnahme", "audiorec", "recording"),
            description = context.getString(R.string.qh_cmd_desc_record)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_recording),
                context.getString(R.string.qh_cmd_help_record),
                20.seconds,
                context
            )
        },
        Command(
            name = "help",
            aliases = listOf("h", "?", "commands", "befehle"),
            description = context.getString(R.string.qh_cmd_desc_help)
        ) {
            showAvailableCommands(context)
        },
        Command(
            name = "flashlevel",
            aliases = listOf("flashl", "lightlevel", "torchlevel", "helligkeit", "flash", "f"),
            description = context.getString(R.string.qh_cmd_desc_flashlevel)
        ) {
            val cameraManager = context.getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull()
            if (cameraId != null) {
                val clampedLevel = 1
                cameraManager.turnOnTorchWithStrengthLevel(cameraId, clampedLevel)
            }
        },
        Command(
            name = "message",
            aliases = listOf("send", "senden", "write", "schreiben"),
            description = context.getString(R.string.qh_cmd_desc_message)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_usage),
                context.getString(R.string.qh_cmd_help_message),
                context = context
            )
        },
        Command(
            name = "stopmediaplayer",
            aliases = listOf("stopm", "mediaplayerstop", "sm"),
            description = context.getString(R.string.qh_cmd_desc_stopmediaplayer)
        ) {
            try {
                MediaPlayerService.stopService(context)
            } catch (e: Exception) {
                showSimpleNotificationExtern(
                    context.getString(R.string.fehler_2),
                    context.getString(R.string.qh_cmd_stop_player_failed, e.message),
                    context = context
                )
            }
        },
        Command(
            name = "weather",
            aliases = listOf("w", "wetter", "forecast"),
            description = context.getString(R.string.qh_cmd_desc_weather)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_weather),
                context.getString(R.string.qh_cmd_help_weather),
                context = context
            )
        },
        Command(
            name = "extract",
            aliases = listOf("e", "ex", "extrahieren"),
            description = context.getString(R.string.qh_cmd_desc_extract)
        ) {
            extractLastMessage(context)
        },
        Command(
            name = "gallerie",
            aliases = listOf("gal", "g", "gallery"),
            description = context.getString(R.string.qh_cmd_desc_gallerie)
        ) {
            loadGalleryImages(0, context)
        },
        Command(
            name = "setdowntime",
            aliases = listOf("set", "dt", "setdr"),
            description = context.getString(R.string.qh_cmd_desc_setdowntime)
        ) {},
        // Ausgeblendet in public version:
        Command(
            name = "friendmessages",
            aliases = listOf("fm", "friendmsgs", "lastmsgs", "friend"),
            description = context.getString(R.string.qh_cmd_desc_friendmessages)
        ) {
            showLastFriendMessages(context)
        },
        Command(
            name = "sound",
            aliases = listOf("vibrate", "vib", "ton", "sound", "silent", "s"),
            description = context.getString(R.string.qh_cmd_desc_sound)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_sound),
                context.getString(R.string.qh_cmd_help_sound),
                context = context
            )
        },
        Command(
            name = "clearpodcasts",
            aliases = listOf("cp", "clearpod", "removepod"),
            description = context.getString(R.string.qh_cmd_desc_clearpodcasts)
        ) {
            clearPodcastSelectionNotifications(context)
        },
        Command(
            name = "tb",
            aliases = listOf("tagesbericht", "upload", "uploadimage"),
            description = context.getString(R.string.qh_cmd_desc_tb)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_daily_report_upload),
                context.getString(R.string.qh_cmd_help_tb_short),
                context = context
            )
        },
        Command(
            name = "bitwarden",
            aliases = listOf("bw", "btw", "b"),
            description = context.getString(R.string.qh_cmd_desc_bitwarden)
        ) {
            val appContext = context.applicationContext
            val clipboard =
                appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            serviceScope.launch {
                val clip = ClipData.newPlainText("BWMP", fetchBWMP(appContext)).apply {
                    description.extras = PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                }
                clipboard.setPrimaryClip(clip)
                delay(30_000.milliseconds)
                clipboard.clearPrimaryClip()
            }
        },
        Command(
            name = "battery",
            aliases = listOf("bat", "btt"),
            description = context.getString(R.string.qh_cmd_desc_battery)
        ) {
            showBatteryInfo(context)
        },
        Command(
            name = "favorite",
            aliases = listOf("fav", "f", "star", "⭐"),
            description = context.getString(R.string.qh_cmd_desc_favorite)
        ) {
            MusicPlayerServiceCompat.toggleFavorite(context)
        },
        /*Command(
            name = "sui",
            aliases = listOf("fav", "f", "star", "⭐"),
            description = "Markiert aktuellen Song als Favorit"
        ) {
            @SuppressLint("MissingPermission")
            fun showCustomNotification(context: Context) {
                val views = RemoteViews(context.packageName, R.layout.notification_custom).apply {
                    setTextViewText(R.id.notif_title, "Custom Notification")
                    setTextViewText(R.id.notif_text, "Das ist ein eigenes Layout!")

                    val actionIntent = PendingIntent.getBroadcast(
                        context, 0,
                        Intent("com.example.NOTIF_ACTION"),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    val dismissIntent = PendingIntent.getBroadcast(
                        context, 1,
                        Intent("com.example.NOTIF_DISMISS"),
                        PendingIntent.FLAG_IMMUTABLE
                    )

                    setOnClickPendingIntent(R.id.notif_btn_action, actionIntent)
                    setOnClickPendingIntent(R.id.notif_btn_dismiss, dismissIntent)
                }

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.app_icon)
                    .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                    .setCustomBigContentView(views)
                    .build()

                NotificationManagerCompat.from(context).notify(42, notification)
            }
            showCustomNotification(context)
        },*/
        Command(
            name = "favmode",
            aliases = listOf("onlyfavs", "favsonly", "favoritesmode"),
            description = context.getString(R.string.qh_cmd_desc_favmode)
        ) {
            MusicPlayerServiceCompat.toggleFavoritesMode(context)
        },
        Command(
            name = "algplay",
            aliases = listOf("ap", "algp", "smartplay"),
            description = context.getString(R.string.qh_cmd_desc_algplay)
        ) {
            val playlists = AlgorithmicPlaylistRegistry.all
            val text = playlists.joinToString("\n") { "${it.icon} ${it.id} – ${context.getString(it.nameRes)}" }
            showSimpleNotificationExtern("🎵 Smart Playlists", text, context = context)
        },
        Command(
            name = "speed",
            aliases = listOf("spd", "tempo", "geschwindigkeit"),
            description = context.getString(R.string.qh_cmd_desc_speed)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_speed),
                context.getString(R.string.qh_cmd_help_speed_short),
                context = context
            )
        },
        Command(
            name = "*",
            aliases = listOf("todo", "task", "aufgabe"),
            description = context.getString(R.string.qh_cmd_desc_todo)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_todo),
                context.getString(R.string.qh_cmd_help_todo),
                context = context
            )
        },
        Command(
            name = ".",
            aliases = listOf(),
            description = context.getString(R.string.qh_cmd_desc_ai_command)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_ai_command),
                context.getString(R.string.qh_cmd_help_ai_command_short),
                context = context
            )
        },
        Command(
            name = "todos",
            aliases = listOf("todoliste", "tasks", "aufgaben"),
            description = context.getString(R.string.qh_cmd_desc_todos)
        ) {
            showAllTodos(context)
        },
        Command(
            name = "todone",
            aliases = listOf("done", "erledigt", "check"),
            description = context.getString(R.string.qh_cmd_desc_todone)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_delete_todo),
                context.getString(R.string.qh_cmd_help_todone),
                context = context
            )
        },
        Command(
            name = "todorm",
            aliases = listOf("removetodo", "deletetodo"),
            description = context.getString(R.string.qh_cmd_desc_todorm)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_delete_todo),
                context.getString(R.string.qh_cmd_help_todorm),
                context = context
            )
        },
        Command(
            name = "bahn",
            aliases = listOf("zug", "train", "db"),
            description = context.getString(R.string.qh_cmd_desc_bahn)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_bahn),
                context.getString(R.string.qh_cmd_help_bahn_info),
                context = context
            )
        },
        Command(
            name = "plcreate",
            aliases = listOf("plc", "createplaylist", "newplaylist"),
            description = context.getString(R.string.qh_cmd_desc_plcreate)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_create_playlist),
                context.getString(R.string.qh_cmd_help_plcreate_long),
                context = context
            )
        },

        Command(
            name = "playlists",
            aliases = listOf("pl", "pls", "showplaylists"),
            description = context.getString(R.string.qh_cmd_desc_playlists)
        ) {
            MediaPlayerService.showPlaylists(context)
        },

        Command(
            name = "plmusic",
            aliases = listOf("plm", "musicplaylists"),
            description = context.getString(R.string.qh_cmd_desc_plmusic)
        ) {
            MediaPlayerService.showPlaylists(context, MediaPlayerService.PlaylistType.MUSIC)
        },

        Command(
            name = "pladd",
            aliases = listOf("pla", "addtoplaylist"),
            description = context.getString(R.string.qh_cmd_desc_pladd)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_add_playlist),
                context.getString(R.string.qh_cmd_help_pladd),
                context = context
            )
        },

        Command(
            name = "plplay",
            aliases = listOf("plp", "playplaylist"),
            description = context.getString(R.string.qh_cmd_desc_plplay)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_play_playlist),
                context.getString(R.string.qh_cmd_help_plplay),
                context = context
            )
        },

        Command(
            name = "plstop",
            aliases = listOf("pls", "stopplaylist", "deactivateplaylist"),
            description = context.getString(R.string.qh_cmd_desc_plstop)
        ) {
            MediaPlayerService.deactivatePlaylist(context)
        },

        Command(
            name = "pldelete",
            aliases = listOf("pld", "deleteplaylist", "removeplaylist"),
            description = context.getString(R.string.qh_cmd_desc_pldelete)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_delete_playlist),
                context.getString(R.string.qh_cmd_help_pldelete),
                context = context
            )
        },
        Command(
            name = "showassign",
            aliases = listOf("sa"),
            description = context.getString(R.string.qh_cmd_desc_showassign)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_showassign),
                context.getString(R.string.qh_cmd_help_showassign_short),
                context = context
            )
        },
        Command(
            name = "showcreate",
            aliases = listOf("sc"),
            description = context.getString(R.string.qh_cmd_desc_showcreate)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_showcreate),
                context.getString(R.string.qh_cmd_help_showcreate_short),
                context = context
            )
        },
        Command(
            name = "showlist",
            aliases = listOf("sl", "shows"),
            description = context.getString(R.string.qh_cmd_desc_showlist)
        ) {
            val shows = PodcastShowManager.getShows()
            val text =
                if (shows.isEmpty()) context.getString(R.string.qh_cmd_no_shows_short) else shows.joinToString("\n") { "• ${it.name} (${it.id})" }
            showSimpleNotificationExtern(context.getString(R.string.qh_cmd_title_podcast_shows_plain), text, context = context)
        },
        Command(
            name = "showrename",
            aliases = listOf("sr"),
            description = context.getString(R.string.qh_cmd_desc_showrename)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_showrename),
                context.getString(R.string.qh_cmd_help_showrename_short),
                context = context
            )
        },
        Command(
            name = "stats",
            aliases = listOf("stat", "statistik"),
            description = context.getString(R.string.qh_cmd_desc_stats)
        ) {
            showSimpleNotificationExtern(
                "ℹ️ stats",
                context.getString(R.string.qh_cmd_help_stats_short),
                context = context
            )
        },
        Command(
            name = "statsreset",
            aliases = listOf("resetstats"),
            description = context.getString(R.string.qh_cmd_desc_statsreset)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_statsreset),
                context.getString(R.string.qh_cmd_help_statsreset_short),
                context = context
            )
        },
        Command(
            name = "plshow",
            aliases = listOf("pldetails"),
            description = context.getString(R.string.qh_cmd_desc_plshow)
        ) {
            MediaPlayerService.showPlaylists(context)
        },
        Command(
            name = "Overlay",
            aliases = listOf("o", "ov"),
            description = context.getString(R.string.qh_cmd_desc_overlay_youtube)
        ) {
            showtestOverlay(context)
        },
        Command(
            name = "Other",
            aliases = listOf("Othertab", "ot"),
            description = context.getString(R.string.qh_cmd_desc_overlay)
        ) {
            if (!Settings.canDrawOverlays(context)) {
                showSimpleNotificationExtern(
                    context.getString(R.string.fehler),
                    context.getString(R.string.overlay_berechtigung_fehlt),
                    context = context
                )
                return@Command
            }

            val windowManager = context.getSystemService(WINDOW_SERVICE) as WindowManager

            testOverlayView?.let { runCatching { windowManager.removeView(it) } }
            testOverlayLifecycle?.onDestroy()
            testOverlayView = null
            testOverlayLifecycle = null

            testOverlayLifecycle = OverlayLifecycleOwner().also { it.onCreate(); it.onResume() }

            testOverlayView = ComposeView(context).apply {
                setViewTreeLifecycleOwner(testOverlayLifecycle)
                setViewTreeSavedStateRegistryOwner(testOverlayLifecycle)
                setViewTreeViewModelStoreOwner(testOverlayLifecycle)
                setContent {
                    val backDispatcher = remember { OnBackPressedDispatcher(null) }
                    val backDispatcherOwner = remember {
                        object : OnBackPressedDispatcherOwner {
                            override val lifecycle get() = testOverlayLifecycle!!.lifecycle
                            override val onBackPressedDispatcher get() = backDispatcher
                        }
                    }
                    val activityResultRegistry = remember {
                        object : androidx.activity.result.ActivityResultRegistry() {
                            override fun <I, O> onLaunch(
                                requestCode: Int,
                                contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                                input: I,
                                options: androidx.core.app.ActivityOptionsCompat?
                            ) {
                            }
                        }
                    }
                    val activityResultRegistryOwner = remember {
                        object : androidx.activity.result.ActivityResultRegistryOwner {
                            override val activityResultRegistry = activityResultRegistry
                        }
                    }

                    CompositionLocalProvider(
                        LocalOnBackPressedDispatcherOwner provides backDispatcherOwner,
                        LocalActivityResultRegistryOwner provides activityResultRegistryOwner
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            OtherBucketViewer(onBackPressed = {
                                testOverlayView?.let { windowManager.removeView(it) }
                                testOverlayLifecycle?.onDestroy()
                                testOverlayView = null
                                testOverlayLifecycle = null
                            })
                            IconButton(
                                onClick = {
                                    testOverlayView?.let { windowManager.removeView(it) }
                                    testOverlayLifecycle?.onDestroy()
                                    testOverlayView = null
                                    testOverlayLifecycle = null
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(8.dp)
                                    .size(40.dp)
                                    .background(
                                        Color.Black.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(50)
                                    )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.schliesen),
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }

            windowManager.addView(testOverlayView, params)
        },
        Command(
            name = "spotify",
            aliases = listOf("sp", "spot"),
            description = context.getString(R.string.qh_cmd_desc_spotify)
        ) {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_spotify),
                context.getString(R.string.qh_cmd_help_spotify),
                context = context
            )
        },
    )
}

fun executeCommand(commandText: String, context: Context) {
    if (!QuietHoursNotificationService.isEnabled(context)) {
        showSimpleNotificationExtern(context.getString(R.string.qh_cmd_background_disabled_title), context.getString(R.string.qh_cmd_background_disabled_text), context = context)
        return
    }
    if (commandText != "^" && commandText != "^^") {
        commandHistory.add(commandText)
        if (commandHistory.size > 10) {
            commandHistory.removeAt(0)
        }
    }

    val parts = commandText.split(" ")

    val commandInput = parts[0].lowercase()
    val argument = if (parts.size > 1) parts[1] else null

    when (commandInput) {
        "geminichat", "ai", "aichat", "gm", "newai", "newaichat", "na", "gemini" -> {
            val name = if (parts.size > 1) {
                commandText.substringAfter(" ", "").trim().ifEmpty { null }
            } else {
                null
            }
            val isNew = commandInput in listOf("newai", "newaichat", "na")
            createGeminiChat(name, context, isNew)
            return
        }

        "record", "rec", "aufnahme", "audiorec", "recording" -> {
            if (argument != null) {
                when (argument.lowercase()) {
                    "start" -> {
                        val file = File(
                            context.getExternalFilesDir(null),
                            "audio_${
                                SimpleDateFormat(
                                    "yyyyMMdd_HHmmss",
                                    Locale.getDefault()
                                ).format(Date())
                            }.m4a"
                        )
                        val intent = Intent(context, AudioForegroundService::class.java).apply {
                            putExtra("filePath", file.absolutePath)
                        }
                        ContextCompat.startForegroundService(context, intent)
                    }

                    "stop" -> {
                        context.stopService(Intent(context, AudioForegroundService::class.java))
                    }

                    else -> showSimpleNotificationExtern(
                        context.getString(R.string.fehler_2),
                        context.getString(R.string.qh_cmd_help_record_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_recording),
                    context.getString(R.string.qh_cmd_help_record),
                    20.seconds,
                    context
                )
            }
            return
        }

        "weather", "w", "wetter", "forecast" -> {
            if (argument != null) {
                val parts = commandText.split(" ")
                if (parts.size == 3) {
                    val dayNum = parts[1].toIntOrNull()
                    val hour = parts[2]

                    val day = when (dayNum) {
                        0 -> context.getString(R.string.qh_cmd_day_today)
                        1 -> context.getString(R.string.qh_cmd_day_tomorrow)
                        2 -> context.getString(R.string.qh_cmd_day_day_after_tomorrow)
                        else -> null
                    }

                    if (day != null && dayNum in 0..2) {
                        val appContext = context.applicationContext
                        Handler(Looper.getMainLooper()).post {
                            serviceScope.launch {
                                try {
                                    val loc =
                                        getLastKnownLocation(appContext)
                                    if (loc == null) {
                                        showSimpleNotificationExtern(
                                            appContext.getString(R.string.qh_cmd_title_location_error),
                                            appContext.getString(R.string.qh_cmd_location_unavailable),
                                            20.seconds,
                                            appContext
                                        )
                                        return@launch
                                    }

                                    val weatherData =
                                        fetchWeatherForecast(
                                            loc.latitude,
                                            loc.longitude,
                                            days = 14,
                                            apiKey = Config.userApiKey(appContext, "weatherapi")
                                        )

                                    weathernot(
                                        appContext,
                                        day,
                                        hour,
                                        weatherData
                                    )

                                } catch (e: Exception) {
                                    showSimpleNotificationExtern(
                                        appContext.getString(R.string.qh_cmd_title_weather_error),
                                        appContext.getString(R.string.qh_cmd_weather_fetch_failed, e.message),
                                        20.seconds,
                                        appContext
                                    )
                                }
                            }
                        }
                    } else {
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_cmd_title_invalid_day),
                            context.getString(R.string.qh_cmd_invalid_day_hint),
                            20.seconds,
                            context,
                            silent = false
                        )
                    }
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.fehler_2),
                        context.getString(R.string.qh_cmd_help_weather),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_weather),
                    context.getString(R.string.qh_cmd_help_weather),
                    20.seconds,
                    context
                )
            }
            return
        }

        "flashlevel", "flashl", "lightlevel", "torchlevel", "helligkeit", "flash", "f" -> {
            if (argument != null) {
                val level = argument.toIntOrNull()
                if (level != null && level >= 1) {
                    val cameraManager = context.getSystemService(CAMERA_SERVICE) as CameraManager
                    try {
                        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return

                        val clampedLevel = level.coerceIn(1, 5)
                        cameraManager.turnOnTorchWithStrengthLevel(cameraId, clampedLevel)
                    } catch (e: Exception) {
                        showSimpleNotificationExtern(
                            context.getString(R.string.taschenlampe),
                            context.getString(R.string.helligkeit_konnte_nicht_gesetzt_werden, e.message),
                            20.seconds,
                            context,
                            silent = false
                        )
                    }
                } else if (level != null && level == 0) {
                    val cameraManager =
                        context.getSystemService(CAMERA_SERVICE) as CameraManager
                    try {
                        val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return
                        cameraManager.setTorchMode(cameraId, false)
                    } catch (_: Exception) {
                        showSimpleNotificationExtern(
                            context.getString(R.string.taschenlampe),
                            context.getString(R.string.qh_cmd_torch_toggle_failed),
                            context = context,
                            silent = false
                        )
                    }
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_invalid_value),
                        context.getString(R.string.qh_cmd_enter_number),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                val cameraManager = context.getSystemService(CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull()
                if (cameraId != null) {
                    val clampedLevel = 1
                    cameraManager.turnOnTorchWithStrengthLevel(cameraId, clampedLevel)
                }
            }
            return
        }

        "setdowntime", "set", "dt", "setdt" -> {
            if (argument !== null) {
                context.getSharedPreferences("quiet_hours_prefs", MODE_PRIVATE)
                    .edit(commit = true) { putString("quiet_hours_start", argument) }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_setdowntime),
                    context.getString(R.string.qh_cmd_help_setdowntime),
                    context = context
                )
            }
        }

        "sound", "vibrate", "vib", "ton", "silent" -> {
            if (argument != null) {
                setSoundMode(argument, context)
            } else {
                setSoundMode("help", context)
            }
            return
        }

        "qadd", "qa", "queueadd", "addqueue" -> {
            if (argument != null) {
                val index = argument.toIntOrNull()
                if (index != null && index > 0) {
                    addPodcastToQueue(index - 1, context)
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_invalid_number),
                        context.getString(R.string.qh_cmd_help_qadd_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_usage),
                    context.getString(R.string.qh_cmd_help_qadd),
                    20.seconds,
                    context
                )
            }
            return
        }

        "qremove", "qr", "queueremove", "removequeue" -> {
            if (argument != null) {
                val position = argument.toIntOrNull()
                if (position != null && position > 0) {
                    removePodcastFromQueue(position - 1, context)
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.ungultige_position),
                        context.getString(R.string.qh_cmd_help_qremove_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_usage),
                    context.getString(R.string.qh_cmd_help_qremove),
                    20.seconds,
                    context
                )
            }
            return
        }

        "qclear", "qc", "queueclear", "clearqueue" -> {
            clearPodcastQueue(context)
            return
        }

        "clearoldprefs", "cop", "cleanoldprefs", "delsecrets", "pcsecretsclear" -> {
            if (!prvt()) {
                Toast.makeText(context, "Forbidden", Toast.LENGTH_SHORT).show()
                return
            }
            val removed = context.deleteSharedPreferences("pc_secrets")
            showSimpleNotificationExtern(
                if (removed) context.getString(R.string.qh_cmd_prefs_cleared_title) else context.getString(R.string.qh_cmd_nothing_to_delete_title),
                if (removed) context.getString(R.string.qh_cmd_prefs_cleared_text) else context.getString(R.string.qh_cmd_prefs_absent_text),
                20.seconds,
                context
            )
            return
        }

        "tb", "tagesbericht", "upload", "uploadimage" -> {
            if (!prvt()) {
                Toast.makeText(context, "Forbidden", Toast.LENGTH_SHORT).show()
                return
            }
            if (argument != null) {
                val parts = commandText.split(" ", limit = 2)
                if (parts.size >= 2) {
                    val restOfCommand = parts[1]

                    val dateAndName = parseCommandWithQuotes(restOfCommand)

                    if (dateAndName.isNotEmpty()) {
                        val date = dateAndName[0]
                        val name = if (dateAndName.size > 1) dateAndName[1] else null
                        uploadCurrentGalleryImageToSupabase(date, name, context)
                    } else {
                        showSimpleNotificationExtern(
                            context.getString(R.string.fehler_2),
                            context.getString(R.string.qh_cmd_help_tb_syntax),
                            20.seconds,
                            context,
                            silent = false
                        )
                    }
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.fehler_2),
                        context.getString(R.string.qh_cmd_help_tb_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_upload),
                    context.getString(R.string.qh_cmd_help_tb),
                    20.seconds,
                    context
                )
            }
            return
        }

        "gallerie", "gal", "g", "gallery" -> {
            loadGalleryImages(argument?.toIntOrNull() ?: 0, context)
            return
        }

        "music", "m", "play", "player", "musik" -> {
            val songNumber = argument?.toIntOrNull()
            MediaPlayerService.startAndPlayMusic(context, songNumber)
            return
        }

        "pd", "pc", "Podcast", "podcast", "py" -> {
            if (argument != null) {
                PodcastPlayerServiceCompat.sendForwardAction(context, (argument.toIntOrNull() ?: 0) * 1000)
            } else {
                PodcastPlayerServiceCompat.startService(context)
                PodcastPlayerServiceCompat.sendPlayAction(context)
            }
            return
        }

        "speed", "spd", "tempo", "geschwindigkeit" -> {
            if (argument != null) {
                val speed = argument.toFloatOrNull()
                if (speed != null && speed in 0.5f..3.0f) {
                    PodcastPlayerServiceCompat.setPlaybackSpeed(context, speed)
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_invalid_speed),
                        context.getString(R.string.qh_cmd_invalid_speed_hint),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_speed),
                    context.getString(R.string.qh_cmd_help_speed),
                    20.seconds,
                    context
                )
            }
            return
        }

        "*", "todo", "task", "aufgabe" -> {
            if (argument != null) {
                val todoText = parts.drop(1).joinToString(" ")
                if (todoText.isNotEmpty()) {
                    addTodo(todoText, context)
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.fehler_2),
                        context.getString(R.string.qh_cmd_help_todo_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_todo),
                    context.getString(R.string.qh_cmd_help_todo),
                    context = context
                )
            }
            return
        }

        "." -> {
            if (!prvt()) {
                Toast.makeText(context, "Forbidden", Toast.LENGTH_SHORT).show()
                return
            }
            if (argument != null) {
                val userInput = parts.drop(1).joinToString(" ")
                if (userInput.isNotEmpty()) {
                    sendAiExecuteCommand(context, userInput)
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_ai_command),
                        context.getString(R.string.qh_cmd_help_ai_command),
                        context = context
                    )
                }
            }
            return
        }

        "todone", "done", "erledigt", "check" -> {
            if (argument != null) {
                val index = argument.toIntOrNull()
                if (index != null && index > 0) {
                    removeTodo(index - 1, context)
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_invalid_number),
                        context.getString(R.string.qh_cmd_help_todone_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showAllTodos(context)
            }
            return
        }

        "todorm", "removetodo", "deletetodo" -> {
            if (argument != null) {
                val index = argument.toIntOrNull()
                if (index != null && index > 0) {
                    removeTodo(index - 1, context)
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_invalid_number),
                        context.getString(R.string.qh_cmd_help_todorm_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_delete_todo),
                    context.getString(R.string.qh_cmd_help_todorm_syntax),
                    context = context
                )
            }
            return
        }

        "bahn", "zug", "train", "db" -> {
            if (!prvt()) {
                Toast.makeText(context, "Forbidden", Toast.LENGTH_SHORT).show()
                return
            }
            val daysAhead = argument?.toIntOrNull() ?: 1
            if (daysAhead !in 1..7) {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_invalid_input),
                    context.getString(R.string.qh_cmd_help_bahn),
                    20.seconds,
                    context,
                    silent = false
                )
            } else {
                val appContext = context.applicationContext
                Handler(Looper.getMainLooper()).post {
                    serviceScope.launch {
                        checkBahnZuege(appContext, daysAhead)
                    }
                }
            }
            return
        }

        "plcreate", "plc", "createplaylist", "newplaylist" -> {
            if (argument != null) {
                val parts = commandText.split(" ", limit = 2)
                if (parts.size >= 2) {
                    val typeAndName = parseCommandWithQuotes(parts[1])
                    if (typeAndName.size >= 2) {
                        val typeName = typeAndName[0].lowercase()
                        val playlistName = typeAndName[1]

                        val type = when (typeName) {
                            "music", "musik", "m" -> MediaPlayerService.PlaylistType.MUSIC
                            else -> null
                        }

                        if (type != null) {
                            MediaPlayerService.createPlaylist(context, playlistName, type)
                        } else {
                            showSimpleNotificationExtern(
                                context.getString(R.string.qh_cmd_title_invalid_type),
                                context.getString(R.string.qh_cmd_invalid_type_hint),
                                20.seconds,
                                context,
                                silent = false
                            )
                        }
                    } else {
                        showSimpleNotificationExtern(
                            context.getString(R.string.fehler_2),
                            context.getString(R.string.qh_cmd_help_plcreate_syntax),
                            20.seconds,
                            context,
                            silent = false
                        )
                    }
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_create_playlist),
                    context.getString(R.string.qh_cmd_help_plcreate),
                    20.seconds,
                    context
                )
            }
            return
        }

        "playlists", "pl", "pls", "showplaylists" -> {
            MediaPlayerService.showPlaylists(context)
            return
        }

        "plmusic", "plm", "musicplaylists" -> {
            MediaPlayerService.showPlaylists(context, MediaPlayerService.PlaylistType.MUSIC)
            return
        }

        "pladd", "pla", "addtoplaylist" -> {
            if (argument != null) {
                MediaPlayerService.addCurrentToPlaylist(context, argument)
            } else {
                MediaPlayerService.showPlaylists(context)
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_add_playlist),
                    context.getString(R.string.qh_cmd_help_pladd_syntax),
                    20.seconds,
                    context
                )
            }
            return
        }

        "plstop", "stopplaylist", "deactivateplaylist" -> {
            MediaPlayerService.deactivatePlaylist(context)
            return
        }

        "pldelete", "pld", "deleteplaylist", "removeplaylist" -> {
            if (argument != null) {
                MediaPlayerService.deletePlaylist(context, argument)
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_delete_playlist),
                    context.getString(R.string.qh_cmd_help_pldelete),
                    20.seconds,
                    context
                )
            }
            return
        }

        "^" -> {
            if (commandHistory.isEmpty()) {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_no_history),
                    context.getString(R.string.qh_cmd_no_previous_command),
                    20.seconds,
                    context
                )
                return
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("BWMP", commandHistory.last())
            clipboard.setPrimaryClip(clip)
            return
        }

        "^^" -> {
            if (commandHistory.size < 2) {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_no_history),
                    context.getString(R.string.qh_cmd_no_previous_previous_command),
                    20.seconds,
                    context
                )
                return
            }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("BWMP", commandHistory[commandHistory.size - 2])
            clipboard.setPrimaryClip(clip)
            return
        }

        "showassign", "sa" -> {
            val parts = commandText.split(" ", limit = 2)
            if (parts.size >= 2) {
                val args = parseCommandWithQuotes(parts[1])
                if (args.size >= 2) {
                    val pattern = args[0]
                    val showName = args[1]
                    PodcastShowManager.assignPattern(pattern, showName)
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_pattern_assigned),
                        context.getString(R.string.qh_cmd_pattern_assigned_text, pattern, showName),
                        context = context,
                        silent = false
                    )
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_showassign),
                        context.getString(R.string.qh_cmd_help_showassign),
                        20.seconds, context
                    )
                }
            }
            return
        }

        "showcreate", "sc" -> {
            val parts = commandText.split(" ", limit = 2)
            if (parts.size >= 2) {
                val args = parseCommandWithQuotes(parts[1])
                val showName = args.firstOrNull()
                if (showName != null) {
                    val show = PodcastShowManager.createShow(showName)
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_show_created),
                        context.getString(R.string.qh_cmd_show_created_text, show.name, show.id),
                        10.seconds, context
                    )
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.fehler_2),
                        context.getString(R.string.qh_cmd_help_showcreate_syntax),
                        20.seconds,
                        context,
                        silent = false
                    )
                }
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_showcreate),
                    context.getString(R.string.qh_cmd_help_showcreate),
                    20.seconds,
                    context
                )
            }
            return
        }

        "showlist", "sl" -> {
            val shows = PodcastShowManager.getShows()
            if (shows.isEmpty()) {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_shows),
                    context.getString(R.string.qh_cmd_no_shows_text),
                    context = context
                )
            } else {
                val text = shows.joinToString("\n") { show ->
                    val patterns =
                        if (show.matchPatterns.isNotEmpty()) " [${show.matchPatterns.joinToString(", ")}]" else ""
                    "• ${show.name} (${show.id})$patterns"
                }
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_podcast_shows, shows.size),
                    text,
                    context = context
                )
            }
            return
        }

        "showrename", "sr" -> {
            val parts = commandText.split(" ", limit = 2)
            if (parts.size >= 2) {
                val args = parseCommandWithQuotes(parts[1])
                if (args.size >= 2) {
                    val oldName = args[0]
                    val newName = args[1]
                    val success = PodcastShowManager.renameShow(oldName, newName)
                    showSimpleNotificationExtern(
                        if (success) context.getString(R.string.qh_cmd_title_renamed) else context.getString(R.string.qh_cmd_title_show_not_found),
                        if (success) context.getString(R.string.qh_cmd_renamed_text, oldName, newName) else context.getString(R.string.qh_cmd_no_show_named, oldName),
                        context = context,
                        silent = false
                    )
                } else {
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_showrename),
                        context.getString(R.string.qh_cmd_help_showrename),
                        20.seconds,
                        context
                    )
                }
            }
            return
        }

        "stats" -> {
            val parts = commandText.split(" ", limit = 3)
            val type = parts.getOrNull(1)?.lowercase()
            val query = parts.getOrNull(2)?.lowercase()

            if (type == null || query == null) {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_stats),
                    context.getString(R.string.qh_cmd_help_stats),
                    20.seconds, context
                )
                return
            }

            when (type) {
                "song", "music", "m" -> {
                    val sessions = MediaAnalyticsManager.getSessionsForLabel(
                        MediaAnalyticsManager.getSessions()
                            .filter { it.type == "music" && it.label.lowercase().contains(query) }
                            .groupBy { it.label }
                            .maxByOrNull { (_, s) -> s.size }
                            ?.key ?: ""
                    )
                    if (sessions.isEmpty()) {
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_cmd_title_not_found),
                            context.getString(R.string.qh_cmd_no_song_stats, query),
                            context = context,
                            silent = false
                        )
                    } else {
                        val matchedLabels = MediaAnalyticsManager.getSessions()
                            .filter { it.type == "music" && it.label.lowercase().contains(query) }
                            .groupBy { it.label }

                        matchedLabels.entries.take(3).forEach { (label, labelSessions) ->
                            val totalMs = labelSessions.sumOf { it.listenedMs }
                            val sessionCount = labelSessions.size
                            val lastPlayedAt = labelSessions.maxOf { it.startedAt }
                            val lastPlayed =
                                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
                                    .format(Date(lastPlayedAt))
                            val maxRepeat = labelSessions.maxOf { it.repeatCount }
                            val repeatInfo =
                                if (maxRepeat > 1) context.getString(R.string.qh_cmd_repeat_info, maxRepeat) else ""
                            showSimpleNotificationExtern(
                                "📊 $label",
                                context.resources.getQuantityString(R.plurals.qh_cmd_stats_song_text, sessionCount, sessionCount, formatMs(totalMs), lastPlayed, repeatInfo),
                                context = context
                            )
                        }
                    }
                }

                "podcast", "pod", "pd" -> {
                    val matchedShows = MediaAnalyticsManager.getSessions()
                        .filter { it.type == "podcast" && it.label.lowercase().contains(query) }
                        .groupBy { it.label }

                    if (matchedShows.isEmpty()) {
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_cmd_title_not_found),
                            context.getString(R.string.qh_cmd_no_podcast_stats, query),
                            context = context,
                            silent = false
                        )
                    } else {
                        matchedShows.entries.take(3).forEach { (showName, showSessions) ->
                            val totalMs = showSessions.sumOf { it.listenedMs }
                            val sessionCount = showSessions.size
                            val lastPlayedAt = showSessions.maxOf { it.startedAt }
                            val lastPlayed =
                                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)
                                    .format(Date(lastPlayedAt))
                            showSimpleNotificationExtern(
                                "📊 $showName",
                                context.resources.getQuantityString(R.plurals.qh_cmd_stats_podcast_text, sessionCount, sessionCount, formatMs(totalMs), lastPlayed),
                                context = context
                            )
                        }
                    }
                }

                else -> showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_invalid_type),
                    context.getString(R.string.qh_cmd_use_song_or_podcast),
                    context = context,
                    silent = false
                )
            }
            return
        }

        "statsreset", "resetstats" -> {
            val parts = commandText.split(" ", limit = 3)
            val type = parts.getOrNull(1)?.lowercase()
            val query = parts.getOrNull(2)?.lowercase()

            if (type == null || query == null) {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_statsreset),
                    context.getString(R.string.qh_cmd_help_statsreset),
                    20.seconds, context
                )
                return
            }

            when (type) {
                "all" -> {
                    MediaAnalyticsManager.clearAll()
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_reset),
                        context.getString(R.string.qh_cmd_all_analytics_deleted),
                        context = context
                    )
                }

                "song", "music", "m" -> {
                    val allSessions = MediaAnalyticsManager.getSessions()
                    val matchedLabels = allSessions
                        .filter { it.type == "music" && it.label.lowercase().contains(query) }
                        .map { it.label }
                        .toSet()

                    if (matchedLabels.isEmpty()) {
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_cmd_title_not_found),
                            context.getString(R.string.qh_cmd_no_song_stats, query),
                            context = context,
                            silent = false
                        )
                    } else {
                        val remaining = allSessions.filter {
                            !(it.type == "music" && matchedLabels.contains(it.label))
                        }

                        rebuildSessions(remaining)
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_cmd_title_reset),
                            context.resources.getQuantityString(R.plurals.qh_cmd_songs_removed, matchedLabels.size, matchedLabels.size, matchedLabels.joinToString(", ")),
                            context = context
                        )
                    }
                }

                "podcast", "pod", "pd" -> {
                    val allSessions = MediaAnalyticsManager.getSessions()
                    val matchedLabels = allSessions
                        .filter { it.type == "podcast" && it.label.lowercase().contains(query) }
                        .map { it.label }
                        .toSet()

                    if (matchedLabels.isEmpty()) {
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_cmd_title_not_found),
                            context.getString(R.string.qh_cmd_no_podcast_stats, query),
                            context = context,
                            silent = false
                        )
                    } else {
                        val remaining = allSessions.filter {
                            !(it.type == "podcast" && matchedLabels.contains(it.label))
                        }
                        rebuildSessions(remaining)
                        showSimpleNotificationExtern(
                            context.getString(R.string.qh_cmd_title_reset),
                            context.resources.getQuantityString(R.plurals.qh_cmd_shows_removed, matchedLabels.size, matchedLabels.size, matchedLabels.joinToString(", ")),
                            context = context
                        )
                    }
                }

                else -> showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_type),
                    context.getString(R.string.qh_cmd_use_song_podcast_all),
                    context = context,
                    silent = false
                )
            }
            return
        }

        "plshow", "pldetails" -> {
            if (argument != null) {
                MediaPlayerService.showPlaylists(context, MediaPlayerService.PlaylistType.MUSIC)
            } else {
                MediaPlayerService.showPlaylists(context)
            }
            return
        }

        "plplay", "plp", "playplaylist" -> {
            if (argument != null) {
                MediaPlayerService.activatePlaylist(context, argument)
            } else {
                showSimpleNotificationExtern(
                    context.getString(R.string.qh_cmd_title_plp),
                    context.getString(R.string.qh_cmd_help_plp),
                    20.seconds,
                    context
                )
            }
            return
        }

        "algplay", "ap", "algp", "smartplay" -> {
            if (argument != null) {
                val query = commandText.substringAfter(" ").trim().lowercase()
                val source = AlgorithmicPlaylistRegistry.all.find {
                    it.id.equals(query, ignoreCase = true) ||
                            context.getString(it.nameRes).lowercase().contains(query) ||
                            it.id.lowercase().contains(query)
                }
                if (source != null) {
                    MediaPlayerService.activateAlgorithmicPlaylist(context, source.id, 0)
                } else {
                    val list =
                        AlgorithmicPlaylistRegistry.all.joinToString("\n") { "${it.icon} ${it.id} – ${context.getString(it.nameRes)}" }
                    showSimpleNotificationExtern(
                        context.getString(R.string.qh_cmd_title_not_found),
                        context.getString(R.string.qh_cmd_available_playlists, list),
                        20.seconds, context,
                        silent = false
                    )
                }
            } else {
                val playlists = AlgorithmicPlaylistRegistry.all
                val text = playlists.joinToString(",") { it.id }
                showSimpleNotificationExtern("🎵 Smart Playlists", text, context = context)
            }
            return
        }
    }

    val commands = getAvailableCommands(context).filter { cmd ->
        prvt() || (
                cmd.name != "t" && cmd.name != "friendmessages" && cmd.name != "whatsapp" &&
                        cmd.name != "tb" && cmd.name != "bitwarden" && cmd.name != "." &&
                        cmd.name != "errors" && cmd.name != "bahn" && cmd.name != "Other" &&
                        cmd.name != "spotify" && cmd.name != "exportprefs"
                )
    }

    val matchedCommand = commands.find { cmd ->
        cmd.name.equals(commandInput, ignoreCase = true) ||
                cmd.aliases.any { it.equals(commandInput, ignoreCase = true) }
    }

    if (matchedCommand != null) {
        try {
            matchedCommand.action()
        } catch (_: Exception) {
            showSimpleNotificationExtern(
                context.getString(R.string.fehler_2),
                context.getString(R.string.qh_cmd_command_failed, matchedCommand.name),
                20.seconds,
                context,
                silent = false
            )
        }
    } else {
        val suggestions = commands.filter { cmd ->
            cmd.name.contains(commandInput, ignoreCase = true) ||
                    commandInput.contains(cmd.name, ignoreCase = true) ||
                    cmd.aliases.any { alias ->
                        alias.contains(commandInput, ignoreCase = true) ||
                                commandInput.contains(alias, ignoreCase = true)
                    }
        }

        if (suggestions.isNotEmpty()) {
            val suggestionText = suggestions.joinToString(", ") { cmd ->
                if (cmd.aliases.isNotEmpty()) {
                    "${cmd.name} (${cmd.aliases.take(2).joinToString(", ")})"
                } else {
                    cmd.name
                }
            }
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_unknown_command_question),
                context.getString(R.string.qh_cmd_did_you_mean, suggestionText),
                20.seconds,
                context,
                silent = false
            )
        } else {
            showSimpleNotificationExtern(
                context.getString(R.string.qh_cmd_title_unknown_command),
                context.getString(R.string.qh_cmd_command_not_found, commandInput),
                20.seconds,
                context,
                silent = false
            )
        }
    }
}

private fun showAvailableCommands(context: Context) {
    val commands = getAvailableCommands(context).filter { cmd ->
        prvt() || (
                cmd.name != "t" && cmd.name != "friendmessages" && cmd.name != "whatsapp" &&
                        cmd.name != "tb" && cmd.name != "bitwarden" && cmd.name != "." &&
                        cmd.name != "errors" && cmd.name != "bahn" && cmd.name != "Other" &&
                        cmd.name != "spotify" && cmd.name != "exportprefs"
                )
    }
    val notificationManager = context.getSystemService(NotificationManager::class.java)

    val chunked = commands.chunked(5)

    chunked.forEachIndexed { index, chunk ->
        Log.d("DEBUGGI", "$index")
        val commandList = chunk
            .filter { it.name != "help" }
            .joinToString("\n") { cmd ->
                if (cmd.aliases.isNotEmpty()) {
                    "• ${cmd.name} (${
                        cmd.aliases.take(3).joinToString(", ")
                    }) - ${cmd.description}"
                } else {
                    "• ${cmd.name} - ${cmd.description}"
                }
            }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_help)
            .setContentTitle(context.getString(R.string.qh_cmd_available_commands_title, index + 1, chunked.size))
            .setContentText(context.resources.getQuantityString(R.plurals.qh_cmd_commands_count, chunk.size, chunk.size))
            .setStyle(NotificationCompat.BigTextStyle().bigText(commandList))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setGroup("commands")
            .build()

        notificationManager.notify(SHOWCOMMANDS + index, notification)
    }

    val summary = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_info_details)
        .setContentTitle(context.getString(R.string.qh_cmd_all_commands))
        .setContentText(context.resources.getQuantityString(R.plurals.qh_cmd_commands_count_summary, chunked.size, chunked.size))
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setGroup("commands")
        .setGroupSummary(true)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(SHOWCOMMANDS + 50, summary)
}

private fun parseCommandWithQuotes(input: String): List<String> {
    val result = mutableListOf<String>()
    var current = StringBuilder()
    var inQuotes = false
    var i = 0

    while (i < input.length) {
        when (val char = input[i]) {
            '"' -> {
                inQuotes = !inQuotes
            }

            ' ' if !inQuotes -> {
                if (current.isNotEmpty()) {
                    result.add(current.toString())
                    current = StringBuilder()
                }
            }

            else -> {
                current.append(char)
            }
        }
        i++
    }

    if (current.isNotEmpty()) {
        result.add(current.toString())
    }

    return result
}

private fun checkBahnZuege(context: Context, daysAhead: Int = 1) {
    val appContext = context.applicationContext
    appScope.launch {
        try {
            val stationName = "Geltendorf"
            val evaNo = "8000119"
            val targetDepartureTime = "07:05"
            val targetDestination = "Buchloe"

            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_MONTH, daysAhead)
            cal.set(Calendar.HOUR_OF_DAY, 7)
            cal.set(Calendar.MINUTE, 5)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)

            val year = (cal.get(Calendar.YEAR) % 100).toString().padStart(2, '0')
            val month = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
            val day = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
            val hour = cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
            val date = "$year$month$day"

            val dayLabel = when (daysAhead) {
                1 -> appContext.getString(R.string.qh_cmd_day_tomorrow)
                2 -> appContext.getString(R.string.qh_cmd_day_day_after_tomorrow)
                else -> appContext.resources.getQuantityString(R.plurals.qh_cmd_in_days, daysAhead, daysAhead)
            }

            val response = withContext(Dispatchers.IO) {
                client.functions.invoke(
                    function = "bahn-api",
                    body = buildJsonObject {
                        put("evaNo", evaNo)
                        put("date", date)
                        put("hour", hour)
                        put("targetDepartureTime", targetDepartureTime)
                        put("targetDestination", targetDestination)
                        put("clientId", Config.userApiKey(appContext, "db_client_id"))
                        put("apiKey", Config.userApiKey(appContext, "db_api_key"))
                    }
                )
            }

            val json = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            Log.d("Commands", json.toString())
            val found = json["found"]?.jsonPrimitive?.content == "true"

            if (!found) {
                val error = json["error"]?.jsonPrimitive?.content
                if (error != null) {
                    showSimpleNotificationExtern(
                        appContext.getString(R.string.qh_cmd_title_bahn_api_error),
                        error,
                        20.seconds,
                        appContext,
                        silent = false
                    )
                } else {
                    showSimpleNotificationExtern(
                        appContext.getString(R.string.qh_cmd_title_no_train),
                        appContext.getString(R.string.qh_cmd_no_train_text, targetDestination, stationName, dayLabel),
                        20.seconds,
                        appContext,
                        silent = false
                    )
                }
                return@launch
            }

            val trainType = json["trainType"]?.jsonPrimitive?.content ?: appContext.getString(R.string.qh_cmd_train_default)
            val trainNumber = json["trainNumber"]?.jsonPrimitive?.content ?: ""
            val lineLabel = json["lineLabel"]?.jsonPrimitive?.content ?: ""
            val trainDisplay = "$trainType $trainNumber ($lineLabel)".trim()
            val platform = json["platform"]?.jsonPrimitive?.content ?: "?"
            val destination = json["destination"]?.jsonPrimitive?.content ?: targetDestination
            val departureTime = json["departureTime"]?.jsonPrimitive?.content ?: targetDepartureTime
            val statusText = json["statusText"]?.jsonPrimitive?.content ?: appContext.getString(R.string.qh_cmd_status_on_time)

            showSimpleNotificationExtern(
                appContext.getString(R.string.qh_cmd_train_title, trainDisplay, dayLabel),
                appContext.getString(R.string.qh_cmd_train_text, stationName, destination, departureTime, targetDepartureTime, platform, statusText),
                context = appContext,
                silent = false
            )
        } catch (e: Exception) {
            showSimpleNotificationExtern(
                appContext.getString(R.string.qh_cmd_title_bahn_error),
                appContext.getString(R.string.qh_cmd_connection_error, e.message),
                20.seconds,
                appContext,
                silent = false
            )
            Log.e("Commands", context.getString(R.string.fehler), e)
        }
    }
}

fun formatMs(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    return if (h > 0) "${h}h ${m}min" else "${m}min"
}