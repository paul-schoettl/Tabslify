package com.tabslify.tabs.ridetracking.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object RideRecovery {

    private const val STALE_AFTER_MS = 5 * 60_000L
    private val recovered = AtomicBoolean(false)

    fun recover(context: Context) {
        val appContext = context.applicationContext
        if (!recovered.compareAndSet(false, true)) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                RideRepository(appContext).markStaleRecordings(
                    System.currentTimeMillis() - STALE_AFTER_MS
                )
            }
        }
    }
}
