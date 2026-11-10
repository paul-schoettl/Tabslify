package com.tabslify.core.logging

import android.content.Context
import android.os.Build
import android.util.Log as AndroidLog
import com.tabslify.BuildConfig
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

object AppLog {

    private const val MAX_MESSAGE = 4000
    private const val MAX_STACK = 8000
    private val sessionId = UUID.randomUUID().toString()
    private val secretKeyValue = Regex(
        "(?i)(\"?(?:password|passwort|token|secret|api[_-]?key|apikey|authorization|cookie|session)\"?\\s*[:=]\\s*)(\"[^\"]*\"|[^,\\s}]+)"
    )
    private val bearerToken = Regex("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+")
    private val supabaseKey = Regex("sb_(?:publishable|secret)_[A-Za-z0-9_-]+")

    @Volatile
    internal var store: AppLogStore? = null
        private set

    @Volatile
    private var initialized = false

    @Volatile
    private var minLevel = LogLevel.DEBUG

    fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            store = AppLogStore(context.applicationContext)
            initialized = true
        }
    }

    fun v(tag: String, message: String) = write(LogLevel.DEBUG, tag, message, null)

    fun d(tag: String, message: String) = write(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = write(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String) = write(LogLevel.WARN, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable?) =
        write(LogLevel.WARN, tag, message, throwable)

    fun e(tag: String, message: String) = write(LogLevel.ERROR, tag, message, null)

    fun e(tag: String, message: String, throwable: Throwable?) =
        write(LogLevel.ERROR, tag, message, throwable)

    fun success(tag: String, message: String) =
        write(LogLevel.INFO, tag, "OK: $message", null)

    fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        val safeTag = tag.take(64).ifBlank { "AppLog" }
        val safeMessage = redact(message).take(MAX_MESSAGE)
        val stack = throwable?.stackTraceToString()?.let { redact(it).take(MAX_STACK) }
        val printed = if (stack.isNullOrBlank()) safeMessage else "$safeMessage\n$stack"
        AndroidLog.println(level.androidPriority, safeTag, printed)

        val target = store ?: return
        val line = runCatching {
            buildJsonObject {
                put("client_id", UUID.randomUUID().toString())
                put("ts", Instant.now().toString())
                put("level", level.name)
                put("tag", safeTag)
                put("message", safeMessage)
                if (!stack.isNullOrBlank()) put("stack", stack)
                put("app_version", BuildConfig.VERSION_NAME)
                put("device", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
                put("sdk", Build.VERSION.SDK_INT)
                put("session_id", sessionId)
            }.toString()
        }.getOrNull() ?: return
        target.append(line)
    }

    private fun redact(text: String): String {
        var result = text.replace(secretKeyValue) { match -> match.groupValues[1] + "***" }
        result = result.replace(bearerToken) { match -> match.groupValues[1] + "***" }
        result = result.replace(supabaseKey, "***")
        return result
    }

    private val LogLevel.androidPriority: Int
        get() = when (this) {
            LogLevel.DEBUG -> AndroidLog.DEBUG
            LogLevel.INFO -> AndroidLog.INFO
            LogLevel.WARN -> AndroidLog.WARN
            LogLevel.ERROR -> AndroidLog.ERROR
        }
}
