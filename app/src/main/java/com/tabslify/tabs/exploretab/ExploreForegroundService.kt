package com.tabslify.tabs.exploretab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.tabslify.R
import com.tabslify.core.objects.Config
import com.tabslify.core.ui.FeatureToggle
import com.tabslify.core.ui.isFeatureEnabled

class ExploreForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            startForeground(Config.EXPLORE_TRACKING, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: SecurityException) {
            stopSelf()
            ExploreLocationTracker.onServiceStartDenied(this)
            return
        }
        ExploreLocationTracker.onServiceStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isFeatureEnabled(this, FeatureToggle.EXPLORE)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        ExploreLocationTracker.onServiceDestroyed(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            setPackage(packageName)
        }
        val launchPendingIntent = PendingIntent.getActivity(
            this,
            1003,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val settingsPendingIntent = PendingIntent.getActivity(
            this,
            1004,
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, EXPLORE_TRACKING_CHANNEL_ID)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, EXPLORE_TRACKING_CHANNEL_ID)
            .setContentTitle(getString(R.string.explore_tracking_lauft))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(false)
            .setSilent(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(launchPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.explore_tracking_ausblenden),
                settingsPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            EXPLORE_TRACKING_CHANNEL_ID,
            getString(R.string.explore_tracking_kanal),
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
