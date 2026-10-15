package com.tabslify.core.objects

import android.content.Context
import com.tabslify.quicksettingsfunctions.BatteryDataRepository

object CloudBackup {
    suspend fun backupNow(context: Context) {
        PrefsBackup.backupNow(context, force = false)
        BatteryDataRepository.syncToSupabase(context)
    }
}
