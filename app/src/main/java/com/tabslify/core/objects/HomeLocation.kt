package com.tabslify.core.objects

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object HomeLocation {
    private const val PREFS = "home_location"
    const val KEY_LAT = "home_lat_d"
    const val KEY_LON = "home_lon_d"
    const val KEY_SSID = "home_wifi_ssid"
    const val KEY_HAS = "has_home_location"

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Pair<Double, Double>? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_HAS, false)) return null
        if (!p.contains(KEY_LAT) || !p.contains(KEY_LON)) return null
        return java.lang.Double.longBitsToDouble(p.getLong(KEY_LAT, 0L)) to
                java.lang.Double.longBitsToDouble(p.getLong(KEY_LON, 0L))
    }

    fun save(context: Context, lat: Double, lon: Double, ssid: String) {
        prefs(context).edit {
            putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
            putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(lon))
            putString(KEY_SSID, ssid.trim())
            putBoolean(KEY_HAS, true)
        }
        Config.LAT = lat
        Config.LON = lon
        clearLegacy(context.applicationContext.getSharedPreferences("tabslify_app_prefs", Context.MODE_PRIVATE))
    }

    fun ssid(context: Context): String =
        prefs(context).getString(KEY_SSID, null).orEmpty().trim()

    fun migrateLegacy(context: Context) {
        val legacy = context.applicationContext
            .getSharedPreferences("tabslify_app_prefs", Context.MODE_PRIVATE)
        val target = prefs(context)
        if (!target.contains(KEY_LAT)) {
            val hasDouble = legacy.contains("lat_key_d") && legacy.contains("lon_key_d")
            val hasFloat = legacy.contains("lat_key") && legacy.contains("lon_key")
            if (hasDouble || hasFloat) {
                val lat = if (hasDouble) {
                    java.lang.Double.longBitsToDouble(legacy.getLong("lat_key_d", 0L))
                } else {
                    legacy.getFloat("lat_key", 0f).toDouble()
                }
                val lon = if (hasDouble) {
                    java.lang.Double.longBitsToDouble(legacy.getLong("lon_key_d", 0L))
                } else {
                    legacy.getFloat("lon_key", 0f).toDouble()
                }
                target.edit {
                    putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
                    putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(lon))
                    putBoolean(KEY_HAS, true)
                    legacy.getString("home_wifi_ssid", null)?.let { putString(KEY_SSID, it) }
                }
                Config.LAT = lat
                Config.LON = lon
            }
        }
        clearLegacy(legacy)
    }

    fun clearLegacy(legacy: SharedPreferences) {
        if (!legacy.contains("lat_key_d") && !legacy.contains("lon_key_d") && !legacy.contains("home_wifi_ssid")) return
        legacy.edit {
            remove("lat_key_d")
            remove("lon_key_d")
            remove("lat_key")
            remove("lon_key")
            remove("home_wifi_ssid")
        }
    }
}
