package com.tabslify.core.functions

import android.util.Log
import com.tabslify.core.activities.Tabslify.Companion.serviceScope
import com.tabslify.core.objects.Config
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object PcCommands {

    const val LAMP_OFF = "lamp_off"
    const val LAMP_ON = "lamp_on"
    const val LAMP_TOGGLE = "lamp_toggle"

    fun queueLampToggle(desired: Boolean, source: String) {
        queue(
            LAMP_TOGGLE,
            source,
            buildJsonObject {
                put("desired", desired)
                put("is_on", desired)
            },
            cooldownMs = 0
        )
    }

    fun queueLampState(on: Boolean, source: String) {
        queue(
            if (on) LAMP_ON else LAMP_OFF,
            source,
            buildJsonObject {
                put("desired", on)
                put("is_on", on)
            },
            cooldownMs = 0
        )
    }

    private const val RESEND_COOLDOWN_MS = 15 * 60_000L

    private val lastQueuedAt = ConcurrentHashMap<String, Long>()

    fun queue(
        command: String,
        source: String,
        payload: JsonObject = buildJsonObject {},
        cooldownMs: Long = RESEND_COOLDOWN_MS,
    ) {
        val now = System.currentTimeMillis()
        val previous = lastQueuedAt[command] ?: 0L
        if (now - previous < cooldownMs) {
            return
        }
        lastQueuedAt[command] = now

        serviceScope.launch {
            var failure: Exception? = null
            for (attempt in 0 until 3) {
                try {
                    Config.client.from("pc_commands").insert(
                        buildJsonObject {
                            put("command", command)
                            put("source", source)
                            put("payload", payload)
                        }
                    )
                    return@launch
                } catch (e: Exception) {
                    failure = e
                    if (!neverReachedServer(e)) break
                    if (attempt < 2) delay((1000L * (attempt + 1)).milliseconds)
                }
            }

            val error = failure ?: return@launch
            lastQueuedAt.remove(command)
            if (isNetworkFailure(error)) {
                Log.w("PcCommands", "$command nicht gesendet: ${error.message}")
            } else {
                errorInsert(
                    "PcCommands",
                    "PC-Befehl $command nicht gesendet: ${error.message}",
                    Instant.now().toString(),
                    "ERROR"
                )
            }
        }
    }
}