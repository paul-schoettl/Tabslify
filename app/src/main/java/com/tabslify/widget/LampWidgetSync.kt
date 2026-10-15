package com.tabslify.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.tabslify.core.objects.Config
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject

object LampWidgetSync {

    private const val TAG = "LampWidget"
    private const val CHANNEL_NAME = "lamp_state_widget"
    private const val NETWORK_DEBOUNCE_MS = 3_000L
    private val RECONCILE_DELAYS_MS = listOf(4_000L, 12_000L, 30_000L)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    private var realtimeJob: Job? = null

    @Volatile
    private var lastNetworkRefreshAt = 0L

    fun updateIcon(context: Context) {
        CommandCodeWidgetRenderer.pushCurrent(context.applicationContext)
    }

    fun requestRefresh(context: Context) {
        CommandCodeWidgetRefreshWorker.enqueueUnique(context.applicationContext)
    }

    fun reportOff(context: Context, source: String) {
        val app = context.applicationContext
        scope.launch {
            runCatching { HandyLampClient.reportExplicitState(app, false, source) }
            updateIcon(app)
        }
    }

    fun start(appContext: Context) {
        startNetworkObserver(appContext.applicationContext)
        startRealtime(appContext.applicationContext)
    }

    fun stop(appContext: Context) {
        runCatching {
            networkCallback?.let { callback ->
                val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                manager?.unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        realtimeJob?.cancel()
        realtimeJob = null
    }

    private fun startNetworkObserver(appContext: Context) {
        if (networkCallback != null) return
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onNetworkChanged(appContext)
            }

            override fun onLost(network: Network) {
                onNetworkChanged(appContext)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                onNetworkChanged(appContext)
            }
        }
        runCatching { manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Log.w(TAG, "Netzwerk-Observer startet nicht: ${it.message}") }
    }

    private fun onNetworkChanged(appContext: Context) {
        val now = System.currentTimeMillis()
        if (now - lastNetworkRefreshAt < NETWORK_DEBOUNCE_MS) return
        lastNetworkRefreshAt = now
        requestRefresh(appContext)
    }

    private fun startRealtime(appContext: Context) {
        if (realtimeJob != null) return
        realtimeJob = scope.launch {
            runCatching {
                val channel = Config.client.channel(CHANNEL_NAME)
                channel.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "lamp_state"
                }.onEach { change ->
                    applyRecord(appContext, change.record.toString())
                }.launchIn(this)
                channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "lamp_state"
                }.onEach { change ->
                    applyRecord(appContext, change.record.toString())
                }.launchIn(this)
                channel.subscribe(blockUntilSubscribed = true)
                Log.i(TAG, "Lampen-Realtime aktiv")
            }.onFailure {
                Log.w(TAG, "Lampen-Realtime startet nicht: ${it.message}")
                realtimeJob = null
            }
        }
    }

    private fun applyRecord(appContext: Context, record: String) {
        val on = runCatching {
            val json = JSONObject(record)
            if (json.has("is_on") && !json.isNull("is_on")) json.optBoolean("is_on") else null
        }.getOrNull() ?: return
        val changed = HandyLampClient.applyRemoteState(appContext, on)
        if (changed) updateIcon(appContext)
    }
}
