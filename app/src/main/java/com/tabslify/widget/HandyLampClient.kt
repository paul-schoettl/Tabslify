package com.tabslify.widget

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import com.tabslify.core.logging.Log
import com.tabslify.core.functions.PcCommands
import com.tabslify.core.objects.Config
import com.tabslify.quiethoursnotificationhelper.laptopIp
import com.tabslify.quiethoursnotificationhelper.laptopName
import com.tabslify.quiethoursnotificationhelper.resolveSyncSecret
import com.tabslify.tabs.authenticator.TotpGenerator
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import androidx.core.content.edit

object HandyLampClient {

    private const val TAG = "LampWidget"
    private const val HOST_PREFS = "commandcode_usage"
    private const val KEY_PC_HOST = "pc_host"
    private const val STATE_PREFS = "lamp_widget"
    private const val KEY_ON = "on"
    private const val API_PORT = 8886
    private const val TOGGLE_ENDPOINT = "/api/switch/toggle?device=lampe"
    private const val STATE_ENDPOINT = "/api/switch/state?device=lampe"
    private const val TOGGLE_CONNECT_TIMEOUT_MS = 5_000
    private const val TOGGLE_READ_TIMEOUT_MS = 45_000
    private const val STATE_CONNECT_TIMEOUT_MS = 2_000
    private const val STATE_READ_TIMEOUT_MS = 4_000
    private const val SCAN_CONNECT_TIMEOUT_MS = 500
    private const val SCAN_READ_TIMEOUT_MS = 500
    private const val SCAN_THREADS = 64
    private const val SCAN_BUDGET_MS = 4_000L
    private const val LAN_WAIT_MS = 4_000L
    private const val CELLULAR_WAIT_MS = 1_500L
    private const val LAN_POLL_MS = 150L
    private const val SB_TABLE = "lamp_state"
    private const val SB_ROW_ID = "lampe"
    private const val HOSE_DEVICES = "steckdose%2Csteckdose2"
    private const val HOSE_STATE_ENDPOINT = "/api/switch/state?device=$HOSE_DEVICES"
    private const val HOSE_STATE_PREFS = "hose_widget"
    private const val HOSE_SB_TABLE = "hose_state"
    private const val HOSE_SB_ROW_ID = "steckdose,steckdose2"
    private const val KEY_HOSE_STATE = "state"
    private const val KEY_HOSE_TOGGLE_AT = "toggle_at"
    private const val HOSE_TOGGLE_COOLDOWN_MS = 3_000L

    @Volatile
    private var authSecret: String = ""

    enum class HoseState { UNKNOWN, OFF, ON, P_ONLY, HOSE_ONLY }

    enum class HoseResult { DONE, QUEUED, IGNORED }

    fun cachedOn(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        return if (prefs.contains(KEY_ON)) prefs.getBoolean(KEY_ON, false) else null
    }

    fun storeOn(context: Context, on: Boolean) {
        context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit {putBoolean(KEY_ON, on)}
    }

    fun cachedHoses(context: Context): HoseState {
        val name = context.getSharedPreferences(HOSE_STATE_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_HOSE_STATE, null) ?: return HoseState.UNKNOWN
        return runCatching { HoseState.valueOf(name) }.getOrDefault(HoseState.UNKNOWN)
    }

    private fun storeHoses(context: Context, state: HoseState) {
        if (state == HoseState.UNKNOWN) return
        context.getSharedPreferences(HOSE_STATE_PREFS, Context.MODE_PRIVATE)
            .edit {putString(KEY_HOSE_STATE, state.name)}
    }

    fun applyRemoteState(context: Context, on: Boolean): Boolean {
        val previous = cachedOn(context)
        storeOn(context, on)
        return previous != on
    }

    fun applyRemoteHoseState(context: Context, state: HoseState): Boolean {
        val previous = cachedHoses(context)
        storeHoses(context, state)
        return previous != state
    }

    fun isCellularOnly(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching false
        val active = manager.activeNetwork ?: return@runCatching false
        val capabilities = manager.getNetworkCapabilities(active) ?: return@runCatching false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }.getOrDefault(false)

    suspend fun readSupabaseState(): Boolean? {
        return runCatching {
            val result = Config.safeCall {
                Config.client
                    .from(SB_TABLE)
                    .select(columns = Columns.list("is_on")) {
                        filter { eq("id", SB_ROW_ID) }
                    }.data
            }
            val row = JSONArray(result).optJSONObject(0) ?: return@runCatching null
            if (!row.has("is_on") || row.isNull("is_on")) return@runCatching null
            row.optBoolean("is_on")
        }.getOrNull()
    }

    suspend fun writeSupabaseState(on: Boolean, source: String) {
        runCatching {
            Config.safeCall {
                Config.client.from(SB_TABLE).upsert(
                    buildJsonObject {
                        put("id", SB_ROW_ID)
                        put("is_on", on)
                        put("updated_at", Instant.now().toString())
                        put("updated_by", source)
                    }
                ) { onConflict = "id" }
            }
        }.onFailure {
            Log.w(TAG, "Supabase-Write fehlgeschlagen: ${it.message}")
        }
    }

    suspend fun readHoseSupabaseState(): HoseState? {
        return runCatching {
            val result = Config.safeCall {
                Config.client
                    .from(HOSE_SB_TABLE)
                    .select(columns = Columns.list("state")) {
                        filter { eq("id", HOSE_SB_ROW_ID) }
                    }.data
            }
            val row = JSONArray(result).optJSONObject(0) ?: return@runCatching null
            val name = row.optString("state")
            if (name.isBlank()) return@runCatching null
            HoseState.valueOf(name)
        }.getOrNull()
    }

    suspend fun writeHoseSupabaseState(state: HoseState, source: String) {
        if (state == HoseState.UNKNOWN) return
        runCatching {
            Config.safeCall {
                Config.client.from(HOSE_SB_TABLE).upsert(
                    buildJsonObject {
                        put("id", HOSE_SB_ROW_ID)
                        put("state", state.name)
                        put("updated_at", Instant.now().toString())
                        put("updated_by", source)
                    }
                ) { onConflict = "id" }
            }
        }.onFailure {
            Log.w(TAG, "hose_state-Write fehlgeschlagen: ${it.message}")
        }
    }

    suspend fun refreshBestEffort(context: Context): Boolean? {
        applyAuth(context)
        val network = lanNetwork(context)
        if (network != null || localIpv4() != null) {
            for (host in priorityHosts(context)) {
                val state = getState(host, network)
                if (state != null) {
                    rememberHost(context, host)
                    storeOn(context, state)
                    writeSupabaseState(state, "lan_refresh")
                    return state
                }
            }
            val prefix = localIpv4()?.substringBeforeLast('.')
            if (prefix != null) {
                val hit = scanSubnet(prefix, network)
                if (hit != null) {
                    getState(hit, network)?.let {
                        rememberHost(context, hit)
                        storeOn(context, it)
                        writeSupabaseState(it, "lan_refresh")
                        return it
                    }
                }
            }
        }
        val remote = readSupabaseState()
        if (remote != null) {
            storeOn(context, remote)
            return remote
        }
        return cachedOn(context)
    }

    suspend fun refreshHosesBestEffort(context: Context): HoseState {
        applyAuth(context)
        val network = lanNetwork(context)
        if (network != null || localIpv4() != null) {
            for (host in priorityHosts(context)) {
                val state = getHoseState(host, network)
                if (state != null && state != HoseState.UNKNOWN) {
                    rememberHost(context, host)
                    storeHoses(context, state)
                    writeHoseSupabaseState(state, "lan_refresh")
                    return state
                }
            }
            val prefix = localIpv4()?.substringBeforeLast('.')
            if (prefix != null) {
                val hit = scanSubnet(prefix, network)
                if (hit != null) {
                    val state = getHoseState(hit, network)
                    if (state != null && state != HoseState.UNKNOWN) {
                        rememberHost(context, hit)
                        storeHoses(context, state)
                        writeHoseSupabaseState(state, "lan_refresh")
                        return state
                    }
                }
            }
        }
        val remote = readHoseSupabaseState()
        if (remote != null && remote != HoseState.UNKNOWN) {
            storeHoses(context, remote)
            return remote
        }
        return cachedHoses(context)
    }

    suspend fun toggleHosesBestEffort(context: Context, source: String): HoseResult {
        val cooldownPrefs = context.getSharedPreferences(HOSE_STATE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - cooldownPrefs.getLong(KEY_HOSE_TOGGLE_AT, 0L) < HOSE_TOGGLE_COOLDOWN_MS) {
            return HoseResult.IGNORED
        }
        cooldownPrefs.edit {putLong(KEY_HOSE_TOGGLE_AT, now)}
        applyAuth(context)
        val desiredOn = cachedHoses(context) != HoseState.ON
        val network = lanNetwork(context)
        if (network != null || localIpv4() != null) {
            for (host in priorityHosts(context)) {
                val result = postSwitch(host, desiredOn, network)
                if (result.ok) {
                    rememberHost(context, host)
                    val state = result.state?.let { if (it) HoseState.ON else HoseState.OFF }
                        ?: getHoseState(host, network)
                        ?: if (desiredOn) HoseState.ON else HoseState.OFF
                    storeHoses(context, state)
                    writeHoseSupabaseState(state, source)
                    return HoseResult.DONE
                }
            }
            val prefix = localIpv4()?.substringBeforeLast('.')
            if (prefix != null) {
                val hit = scanSubnet(prefix, network)
                if (hit != null) {
                    val result = postSwitch(hit, desiredOn, network)
                    if (result.ok) {
                        rememberHost(context, hit)
                        val state = result.state?.let { if (it) HoseState.ON else HoseState.OFF }
                            ?: getHoseState(hit, network)
                            ?: if (desiredOn) HoseState.ON else HoseState.OFF
                        storeHoses(context, state)
                        writeHoseSupabaseState(state, source)
                        return HoseResult.DONE
                    }
                }
            }
            Log.w(TAG, "Lichtschlauch-LAN fehlgeschlagen, nutze Supabase-Fallback")
        } else {
            Log.i(TAG, "Lichtschlauch-LAN uebersprungen: kein lokales Netz")
        }
        val guess = if (desiredOn) HoseState.ON else HoseState.OFF
        storeHoses(context, guess)
        writeHoseSupabaseState(guess, source)
        PcCommands.queueSwitchState(desiredOn, source)
        return HoseResult.QUEUED
    }

    suspend fun toggleBestEffort(context: Context, source: String): Boolean {
        applyAuth(context)
        val network = lanNetwork(context)
        if (network != null || localIpv4() != null) {
            val desired = cachedOn(context)?.let { !it }
            for (host in priorityHosts(context)) {
                val result = postToggle(host, network = network)
                if (result.ok) {
                    rememberHost(context, host)
                    val state = result.state ?: getState(host, network) ?: desired ?: true
                    storeOn(context, state)
                    writeSupabaseState(state, source)
                    return true
                }
            }
            val prefix = localIpv4()?.substringBeforeLast('.')
            if (prefix != null) {
                val hit = scanSubnet(prefix, network)
                if (hit != null) {
                    val result = postToggle(hit, network = network)
                    if (result.ok) {
                        rememberHost(context, hit)
                        val state = result.state ?: getState(hit, network) ?: desired ?: true
                        storeOn(context, state)
                        writeSupabaseState(state, source)
                        return true
                    }
                }
            }
            val readBack = readLampState(context, network)
            if (readBack != null && desired != null && readBack == desired) {
                storeOn(context, readBack)
                writeSupabaseState(readBack, source)
                return true
            }
            Log.w(TAG, "Lampen-LAN fehlgeschlagen, nutze Supabase-Fallback")
        } else {
            Log.i(TAG, "Lampen-LAN uebersprungen: kein lokales Netz")
        }
        val fallback = readSupabaseState()?.let { !it } ?: cachedOn(context)?.let { !it } ?: true
        storeOn(context, fallback)
        writeSupabaseState(fallback, source)
        PcCommands.queueLampState(fallback, source)
        return false
    }

    suspend fun reportExplicitState(context: Context, on: Boolean, source: String) {
        storeOn(context, on)
        writeSupabaseState(on, source)
        PcCommands.queueLampState(on, source)
    }

    fun refresh(context: Context) {
        applyAuth(context)
        val network = lanNetwork(context, waitMs = 0L)
        if (network == null && localIpv4() == null) return
        for (host in priorityHosts(context)) {
            val state = getState(host, network)
            if (state != null) {
                rememberHost(context, host)
                storeOn(context, state)
                return
            }
        }
        val prefix = localIpv4()?.substringBeforeLast('.') ?: return
        val hit = scanSubnet(prefix, network) ?: return
        getState(hit, network)?.let {
            rememberHost(context, hit)
            storeOn(context, it)
        }
    }

    private fun readLampState(context: Context, network: Network?): Boolean? {
        for (host in priorityHosts(context)) {
            getState(host, network)?.let { return it }
        }
        return null
    }

    private fun lanNetwork(context: Context, waitMs: Long = LAN_WAIT_MS): Network? {
        return try {
            val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val started = SystemClock.elapsedRealtime()
            var deadline = started + waitMs
            var found: Network? = null
            search@ while (true) {
                val active = manager.activeNetwork
                val activeCaps = active?.let { manager.getNetworkCapabilities(it) }
                if (active != null && activeCaps != null && isLanTransport(activeCaps)) {
                    found = active
                    break@search
                }
                val candidate = manager.allNetworks.firstOrNull { candidate ->
                    val caps = manager.getNetworkCapabilities(candidate) ?: return@firstOrNull false
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                }
                if (candidate != null) {
                    found = candidate
                    break@search
                }
                if (activeCaps != null && activeCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                    deadline = started + CELLULAR_WAIT_MS
                }
                if (SystemClock.elapsedRealtime() >= deadline) break@search
                SystemClock.sleep(LAN_POLL_MS)
            }
            found
        } catch (_: Exception) {
            null
        }
    }

    private fun isLanTransport(capabilities: NetworkCapabilities): Boolean =
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    private fun priorityHosts(context: Context): List<String> {
        val remembered = runCatching {
            context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE).getString(KEY_PC_HOST, "")
        }.getOrNull().orEmpty()
        val runtime = runCatching { laptopIp }.getOrNull().orEmpty()
        if (runtime.isNotBlank() && runtime != remembered) rememberHost(context, runtime)
        return listOf(remembered, runtime)
            .filter { it.isNotBlank() && it.count { char -> char == '.' } == 3 }
            .distinct()
    }

    private fun rememberHost(context: Context, host: String) {
        runCatching {
            val prefs = context.getSharedPreferences(HOST_PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_PC_HOST, "") != host) {
                prefs.edit {putString(KEY_PC_HOST, host)}
            }
        }
    }

    private fun scanSubnet(prefix: String, network: Network? = null): String? {
        val pool = Executors.newFixedThreadPool(SCAN_THREADS) { runnable ->
            Thread(runnable, "lamp-host-scan").apply { isDaemon = true }
        }
        val hit = AtomicReference<String?>(null)
        try {
            val pending = CountDownLatch(254)
            for (last in 1..254) {
                val host = "$prefix.$last"
                pool.execute {
                    try {
                        if (portOpen(host, network)) hit.compareAndSet(null, host)
                    } catch (_: Exception) {
                    } finally {
                        pending.countDown()
                    }
                }
            }
            val deadline = System.currentTimeMillis() + SCAN_BUDGET_MS
            while (System.currentTimeMillis() < deadline && hit.get() == null) {
                TimeUnit.MILLISECONDS.sleep(80)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pool.shutdownNow()
        }
        return hit.get()
    }

    private fun portOpen(host: String, network: Network? = null): Boolean {
        val connection = openConnection("http://$host:$API_PORT/", network)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = SCAN_CONNECT_TIMEOUT_MS
            connection.readTimeout = SCAN_READ_TIMEOUT_MS
            connection.doOutput = true
            connection.outputStream.use { it.write(ByteArray(0)) }
            true
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private data class ToggleResult(val ok: Boolean, val state: Boolean?)

    private fun postToggle(
        host: String,
        endpoint: String = TOGGLE_ENDPOINT,
        network: Network? = null
    ): ToggleResult = post(host, endpoint, network)

    private fun postSwitch(host: String, on: Boolean, network: Network? = null): ToggleResult {
        val action = if (on) "on" else "off"
        return post(host, "/api/switch/$action?device=$HOSE_DEVICES", network)
    }

    private fun post(host: String, endpoint: String, network: Network?): ToggleResult {
        val connection = openConnection("http://$host:$API_PORT$endpoint", network)
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TOGGLE_CONNECT_TIMEOUT_MS
            connection.readTimeout = TOGGLE_READ_TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            applyTotp(connection)
            connection.outputStream.use { it.write(ByteArray(0)) }
            val code = connection.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Toggle an $host abgelehnt: HTTP $code")
                return ToggleResult(false, null)
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            ToggleResult(true, parseToggleState(body))
        } catch (e: Exception) {
            Log.w(TAG, "Toggle an $host fehlgeschlagen: ${e.message}")
            ToggleResult(false, null)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseToggleState(body: String): Boolean? = runCatching {
        val json = JSONObject(body)
        if (json.has("state") && !json.isNull("state")) json.optBoolean("state") else null
    }.getOrNull()

    private fun getState(
        host: String,
        network: Network? = null,
        endpoint: String = STATE_ENDPOINT
    ): Boolean? {
        val connection = openConnection("http://$host:$API_PORT$endpoint", network)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = STATE_CONNECT_TIMEOUT_MS
            connection.readTimeout = STATE_READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            applyTotp(connection)
            if (connection.responseCode !in 200..299) return null
            parseStateBody(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun parseStateBody(body: String): Boolean? = runCatching {
        val devices = JSONObject(body).optJSONArray("devices") ?: return null
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            if (device.has("state") && !device.isNull("state")) return device.optBoolean("state")
        }
        null
    }.getOrNull()

    private fun getHoseState(host: String, network: Network? = null): HoseState? {
        val connection = openConnection("http://$host:$API_PORT$HOSE_STATE_ENDPOINT", network)
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = STATE_CONNECT_TIMEOUT_MS
            connection.readTimeout = STATE_READ_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            applyTotp(connection)
            if (connection.responseCode !in 200..299) return null
            parseHoseStateBody(connection.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, network: Network?): HttpURLConnection {
        val connection = if (network != null) {
            network.openConnection(URL(url))
        } else {
            URL(url).openConnection()
        }
        return connection as HttpURLConnection
    }

    private fun parseHoseStateBody(body: String): HoseState = runCatching {
        val devices = JSONObject(body).optJSONArray("devices") ?: return@runCatching HoseState.UNKNOWN
        var p: Boolean? = null
        var h: Boolean? = null
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            if (!device.has("state") || device.isNull("state")) continue
            when (device.optString("id")) {
                "steckdose" -> p = device.optBoolean("state")
                "steckdose2" -> h = device.optBoolean("state")
            }
        }
        when {
            p == true && h == true -> HoseState.ON
            p == false && h == false -> HoseState.OFF
            p == true && h == false -> HoseState.P_ONLY
            p == false && h == true -> HoseState.HOSE_ONLY
            else -> HoseState.UNKNOWN
        }
    }.getOrDefault(HoseState.UNKNOWN)

    private fun resolveSecret(context: Context): String {
        val secrets = context.getSharedPreferences("pc_secrets", Context.MODE_PRIVATE)
        val registered = context.getSharedPreferences("registered_pcs", Context.MODE_PRIVATE)
        val names = linkedSetOf<String>()
        if (laptopName.isNotBlank()) names.add(laptopName)
        names.addAll(registered.all.keys)
        names.addAll(secrets.all.keys)
        for (name in names) {
            resolveSyncSecret(context, name)?.let { if (it.isNotBlank()) return it }
        }
        return ""
    }

    private fun applyAuth(context: Context) {
        authSecret = resolveSecret(context)
    }

    private fun applyTotp(connection: HttpURLConnection) {
        val secret = authSecret
        if (secret.isNotBlank()) {
            connection.setRequestProperty("X-Tabslify-Totp", TotpGenerator.generateTOTP(secret))
        }
    }

    private fun localIpv4(): String? {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        val candidates = mutableListOf<Pair<String, String>>()
        while (interfaces.hasMoreElements()) {
            val candidate = interfaces.nextElement() ?: continue
            val name = candidate.name.orEmpty()
            val addresses = candidate.inetAddresses ?: continue
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress) {
                    val host = address.hostAddress ?: continue
                    if (isPrivateIpv4(host)) candidates.add(name to host)
                }
            }
        }
        return candidates.firstOrNull { (name, _) ->
            name.startsWith("wlan") || name.startsWith("eth")
        }?.second ?: candidates.firstOrNull()?.second
    }

    private fun isPrivateIpv4(host: String): Boolean {
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        if (!host.startsWith("172.")) return false
        val second = host.split('.').getOrNull(1)?.toIntOrNull() ?: return false
        return second in 16..31
    }
}
