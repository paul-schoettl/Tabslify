package com.tabslify.tabs.ridetracking.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tabslify.R
import com.tabslify.core.logging.AppLog
import com.tabslify.tabs.ridetracking.data.RIDE_STATUS_PAUSED
import com.tabslify.tabs.ridetracking.data.RideRecorderState
import com.tabslify.tabs.ridetracking.data.RideRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class RideTrackingService : Service() {

    private var collectorScope: CoroutineScope? = null
    private var lastNotificationAt = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(
                RideCommand.NOTIFICATION_ID,
                buildNotification(RideRecorder.state.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } catch (e: SecurityException) {
            AppLog.e("RideTrackingService", "startForeground verweigert: ${e.message}")
            RideRecorder.onServiceStartDenied(this)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            RideCommand.ACTION_START -> {
                RideRecorder.onServiceStarted(this)
                startCollector()
            }

            RideCommand.ACTION_PAUSE -> {
                RideRecorder.pause(this)
                refreshNotification(force = true)
            }

            RideCommand.ACTION_RESUME -> {
                RideRecorder.resume(this)
                refreshNotification(force = true)
            }

            RideCommand.ACTION_STOP -> RideRecorder.stop(this)
            else -> {
                if (RideRecorder.state.value.active) {
                    startCollector()
                } else {
                    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                        val active = runCatching {
                            RideRepository(applicationContext).activeRide()
                        }.getOrNull()
                        withContext(Dispatchers.Main) {
                            if (active != null) {
                                RideRecorder.onServiceStarted(this@RideTrackingService)
                                startCollector()
                            } else {
                                stopSelf()
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        collectorScope?.cancel()
        collectorScope = null
        RideRecorder.onServiceDestroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCollector() {
        if (collectorScope != null) return
        collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope ->
            scope.launch {
                RideRecorder.state.collectLatest { state ->
                    refreshNotification(state)
                }
            }
        }
    }

    private fun refreshNotification(
        state: RideRecorderState = RideRecorder.state.value,
        force: Boolean = false
    ) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAt < 1_500L) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        lastNotificationAt = now
        val notification = buildNotification(state)
        runCatching {
            NotificationManagerCompat.from(this)
                .notify(RideCommand.NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(state: RideRecorderState): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(packageName)
        }
        val openPending = openIntent?.let {
            PendingIntent.getActivity(
                this,
                8101,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val paused = state.status == RIDE_STATUS_PAUSED
        val toggleIntent = Intent(this, RideTrackingService::class.java).apply {
            action = if (paused) RideCommand.ACTION_RESUME else RideCommand.ACTION_PAUSE
        }
        val togglePending = PendingIntent.getService(
            this,
            8102,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RideTrackingService::class.java).apply {
            action = RideCommand.ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            8103,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val km = state.distanceMeters / 1000.0
        val minutes = (state.movingMs / 60_000L).toInt()
        val seconds = ((state.movingMs / 1000L) % 60L).toInt()
        val speed = state.currentSpeedMps * 3.6
        val text = String.format(
            Locale.getDefault(),
            "%s · %.2f km · %d:%02d · %.1f km/h",
            if (paused) getString(R.string.ride_tracking_notification_paused) else getString(R.string.ride_tracking_notification_recording),
            km,
            minutes,
            seconds,
            speed
        )

        return NotificationCompat.Builder(this, RideCommand.CHANNEL_ID)
            .setContentTitle(getString(R.string.ride_tracking_notification_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(openPending)
            .addAction(
                if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                getString(if (paused) R.string.ride_tracking_resume else R.string.ride_tracking_pause),
                togglePending
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.ride_tracking_stop),
                stopPending
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            RideCommand.CHANNEL_ID,
            getString(R.string.ride_tracking_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
