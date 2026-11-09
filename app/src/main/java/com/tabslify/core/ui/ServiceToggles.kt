package com.tabslify.core.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.tabslify.R
import com.tabslify.core.objects.prvt
import com.tabslify.quiethoursnotificationhelper.DailyDigestScheduler
import com.tabslify.quiethoursnotificationhelper.stopAllSyncServices
import com.tabslify.quicksettingsfunctions.BatteryFeature
import com.tabslify.quicksettingsfunctions.ChargingTrackerService
import com.tabslify.quicksettingsfunctions.startBatteryWorker
import com.tabslify.quicksettingsfunctions.stopBatteryWorker
import com.tabslify.services.QuietHoursNotificationService
import com.tabslify.services.WhatsAppNotificationListener
import com.tabslify.tabs.exploretab.ExploreLocationTracker
import com.tabslify.tabs.focusguard.FocusGuardFeature
import com.tabslify.tabs.focusguard.FocusGuardService
import com.tabslify.tabs.focusguard.monitoring.cancelFocusGuardWorkers
import com.tabslify.tabs.focusguard.monitoring.scheduleFocusGuardWorkers
import com.tabslify.tabs.ridetracking.service.RideRecorder

const val APP_PREFS_NAME = "app_prefs"

private const val MEDIA_ANALYTICS_PREFS = "media_analytics"
private const val MEDIA_ANALYTICS_KEY = "analytics_enabled"

enum class ServiceToggle(
    val prefKey: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val defaultEnabled: Boolean,
    val requiresPrvt: Boolean = false,
    val requiresBatteryFeature: Boolean = false,
    val requiresFocusGuardFeature: Boolean = false,
    val gatedByMaster: Boolean = true,
    val isMaster: Boolean = false
) {
    MASTER(
        "services_master",
        R.string.service_master_title,
        R.string.service_master_desc,
        true,
        isMaster = true
    ),
    QHNS(
        "service_qhns",
        R.string.service_qhns_title,
        R.string.alles_rund_um_commands,
        true
    ),
    WHATSAPP(
        "service_wh",
        R.string.service_whatsapp_title,
        R.string.verarbeitet_eingehende_whatsapps,
        false,
        requiresPrvt = true
    ),
    MEDIA_ANALYTICS(
        "analytics_enabled",
        R.string.media_analytics,
        R.string.tracke_horgewohnheiten_fur_statistiken,
        false,
        gatedByMaster = false
    ),
    CHARGING(
        "service_charge",
        R.string.service_charging_title,
        R.string.verfolgt_ladevorgange_fur_ladedauer_schatzungen,
        false,
        requiresBatteryFeature = true
    ),
    BATTERY(
        "service_battery",
        R.string.service_battery_title,
        R.string.hintergrund_aufzeichnung_der_batteriedaten,
        false,
        requiresBatteryFeature = true
    ),
    FOCUSGUARD(
        "service_focusguard",
        R.string.focusguard_tab,
        R.string.focusguard_service_subtitle,
        false,
        requiresFocusGuardFeature = true
    );

    fun isAvailable(): Boolean {
        if (requiresPrvt && !prvt()) return false
        if (requiresBatteryFeature && !BatteryFeature.ENABLED) return false
        if (requiresFocusGuardFeature && !FocusGuardFeature.ENABLED) return false
        return true
    }
}

enum class FeatureGroup { TRACKING, CONNECTION }

enum class FeatureToggle(
    val prefKey: String,
    val titleRes: Int,
    val descriptionRes: Int,
    val defaultEnabled: Boolean,
    val group: FeatureGroup,
    val requiresPrvt: Boolean = false
) {
    EXPLORE(
        "feature_explore",
        R.string.feature_explore_title,
        R.string.feature_explore_desc,
        false,
        FeatureGroup.TRACKING
    ),
    RIDE_TRACKING(
        "feature_ride_tracking",
        R.string.feature_ride_tracking_title,
        R.string.feature_ride_tracking_desc,
        false,
        FeatureGroup.TRACKING,
        requiresPrvt = true
    ),
    HEALTH(
        "feature_health",
        R.string.feature_health_title,
        R.string.feature_health_desc,
        false,
        FeatureGroup.TRACKING,
        requiresPrvt = true
    ),
    USAGE_STATS(
        "feature_usage_stats",
        R.string.feature_usage_stats_title,
        R.string.feature_usage_stats_desc,
        false,
        FeatureGroup.TRACKING,
        requiresPrvt = true
    ),
    CONTACTS(
        "feature_contacts",
        R.string.feature_contacts_title,
        R.string.feature_contacts_desc,
        false,
        FeatureGroup.TRACKING,
        requiresPrvt = true
    ),
    CLOUD_BACKUP(
        "service_cloud_backup",
        R.string.cloud_backup_title,
        R.string.service_cloud_backup_desc,
        false,
        FeatureGroup.CONNECTION,
        requiresPrvt = true
    ),
    LIVE_TRAFFIC(
        "service_live_traffic",
        R.string.service_live_traffic_title,
        R.string.service_live_traffic_desc,
        false,
        FeatureGroup.CONNECTION,
        requiresPrvt = true
    ),
    BLUETOOTH(
        "feature_bluetooth",
        R.string.feature_bluetooth_title,
        R.string.feature_bluetooth_desc,
        false,
        FeatureGroup.CONNECTION,
        requiresPrvt = true
    ),
    LOCAL_NETWORK(
        "feature_local_network",
        R.string.feature_local_network_title,
        R.string.feature_local_network_desc,
        true,
        FeatureGroup.CONNECTION,
        requiresPrvt = true
    );

    fun isAvailable(): Boolean = !requiresPrvt || prvt()
}

fun servicePrefs(context: Context) =
    context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)

fun readServiceToggle(context: Context, toggle: ServiceToggle): Boolean = when (toggle) {
    ServiceToggle.MEDIA_ANALYTICS -> context
        .getSharedPreferences(MEDIA_ANALYTICS_PREFS, Context.MODE_PRIVATE)
        .getBoolean(MEDIA_ANALYTICS_KEY, false)

    else -> servicePrefs(context).getBoolean(toggle.prefKey, toggle.defaultEnabled)
}

fun readServiceToggleStates(context: Context): Map<ServiceToggle, Boolean> =
    ServiceToggle.entries.associateWith { readServiceToggle(context, it) }

fun ensureServiceToggleDefaults(context: Context) {
    val prefs = servicePrefs(context)
    val missing = ServiceToggle.entries.filter {
        it != ServiceToggle.MEDIA_ANALYTICS && !prefs.contains(it.prefKey)
    }
    if (missing.isEmpty()) return
    prefs.edit {
        missing.forEach { putBoolean(it.prefKey, it.defaultEnabled) }
    }
}

fun writeServiceToggleStates(context: Context, states: Map<ServiceToggle, Boolean>) {
    val prefs = servicePrefs(context)
    prefs.edit {
        states.forEach { (toggle, enabled) ->
            if (toggle != ServiceToggle.MEDIA_ANALYTICS) putBoolean(toggle.prefKey, enabled)
        }
    }
    states[ServiceToggle.MEDIA_ANALYTICS]?.let { enabled ->
        context.getSharedPreferences(MEDIA_ANALYTICS_PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(MEDIA_ANALYTICS_KEY, enabled) }
    }
}

fun applyServiceToggleStates(
    context: Context,
    states: Map<ServiceToggle, Boolean>,
    notificationAllowed: Boolean
) {
    val master = states[ServiceToggle.MASTER] ?: true

    applyService(
        context,
        QuietHoursNotificationService::class.java,
        states[ServiceToggle.QHNS] == true && master,
        notificationAllowed
    )
    applyService(
        context,
        ChargingTrackerService::class.java,
        states[ServiceToggle.CHARGING] == true && master && BatteryFeature.ENABLED,
        notificationAllowed
    )
    applyService(
        context,
        FocusGuardService::class.java,
        states[ServiceToggle.FOCUSGUARD] == true && master && FocusGuardFeature.ENABLED,
        notificationAllowed
    )

    if (BatteryFeature.ENABLED && master && states[ServiceToggle.BATTERY] == true) {
        startBatteryWorker(context)
    } else {
        stopBatteryWorker(context)
    }

    if (FocusGuardFeature.ENABLED && master && states[ServiceToggle.FOCUSGUARD] == true) {
        scheduleFocusGuardWorkers(context)
        DailyDigestScheduler.schedule(context)
    } else {
        cancelFocusGuardWorkers(context)
    }
}

fun readFeatureToggle(context: Context, feature: FeatureToggle): Boolean =
    prvt() || servicePrefs(context).getBoolean(feature.prefKey, feature.defaultEnabled)

fun readFeatureToggleStates(context: Context): Map<FeatureToggle, Boolean> =
    FeatureToggle.entries.associateWith { readFeatureToggle(context, it) }

fun isFeatureEnabled(context: Context, feature: FeatureToggle): Boolean =
    feature.isAvailable() && readFeatureToggle(context, feature)

fun ensureFeatureToggleDefaults(context: Context) {
    val prefs = servicePrefs(context)
    val missing = FeatureToggle.entries.filter { !prefs.contains(it.prefKey) }
    if (missing.isEmpty()) return
    val enabled = prvt()
    prefs.edit {
        missing.forEach { putBoolean(it.prefKey, enabled || it.defaultEnabled) }
    }
}

fun writeFeatureToggleStates(context: Context, states: Map<FeatureToggle, Boolean>) {
    val prefs = servicePrefs(context)
    prefs.edit {
        states.forEach { (feature, enabled) -> putBoolean(feature.prefKey, enabled) }
    }
}

fun applyFeatureToggleStates(context: Context, states: Map<FeatureToggle, Boolean>) {
    if (states[FeatureToggle.EXPLORE] != true) {
        runCatching { ExploreLocationTracker.stop(context) }
    }
    if (states[FeatureToggle.RIDE_TRACKING] != true) {
        runCatching { RideRecorder.stop(context) }
    }
    if (states[FeatureToggle.LOCAL_NETWORK] != true) {
        runCatching { stopAllSyncServices(context) }
    }
}

private fun applyService(
    context: Context,
    cls: Class<*>,
    enabled: Boolean,
    notificationAllowed: Boolean
) {
    if (cls == WhatsAppNotificationListener::class.java) return

    val intent = Intent(context, cls)
    val permissionOk = !(cls == QuietHoursNotificationService::class.java ||
            cls == FocusGuardService::class.java) || notificationAllowed

    if (enabled && permissionOk) {
        when (cls) {
            QuietHoursNotificationService::class.java,
            ChargingTrackerService::class.java,
            FocusGuardService::class.java -> ContextCompat.startForegroundService(
                context,
                intent
            )

            else -> context.startService(intent)
        }
    } else {
        context.stopService(intent)
    }
}
