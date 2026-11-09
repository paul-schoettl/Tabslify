package com.tabslify.core.logging

import android.content.Context
import android.util.Log as AndroidLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

internal class AppLogStore(context: Context) {

    companion object {
        private const val TAG = "AppLogStore"
        private const val ACTIVE_NAME = "active.jsonl"
        private const val QUEUE_PREFIX = "queue-"
        private const val SUFFIX = ".jsonl"
        private const val MAX_ACTIVE_BYTES = 2L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 20L * 1024 * 1024
        private const val RETENTION_MS = 14L * 24 * 60 * 60 * 1000
    }

    private val dir = File(context.filesDir, "app_logs").apply { mkdirs() }
    private val lock = Any()
    private var writer: BufferedWriter? = null
    private var counter = 0L
    private var lastMaintenance = 0L

    private val active: File
        get() = File(dir, ACTIVE_NAME)

    fun append(line: String) {
        synchronized(lock) {
            try {
                val out = writer ?: openWriterLocked()
                out.write(line)
                out.newLine()
                out.flush()
                if (active.length() >= MAX_ACTIVE_BYTES) {
                    closeWriterLocked()
                    rotateLocked()
                }
                maybeMaintainLocked()
            } catch (e: Exception) {
                AndroidLog.e(TAG, "append failed", e)
                closeWriterLocked()
            }
        }
    }

    fun pendingFiles(): List<File> {
        synchronized(lock) {
            closeWriterLocked()
            rotateLocked()
            return queueFiles().sortedBy { it.name }
        }
    }

    fun readLines(file: File): List<String> = try {
        if (file.exists()) file.readLines() else emptyList()
    } catch (e: Exception) {
        AndroidLog.e(TAG, "read failed ${file.name}", e)
        emptyList()
    }

    fun rewrite(file: File, lines: List<String>) {
        try {
            val tmp = File(dir, file.name + ".tmp")
            tmp.bufferedWriter().use { out ->
                lines.forEach {
                    out.write(it)
                    out.newLine()
                }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            AndroidLog.e(TAG, "rewrite failed ${file.name}", e)
        }
    }

    fun delete(file: File) {
        file.delete()
    }

    private fun openWriterLocked(): BufferedWriter {
        if (!dir.exists()) dir.mkdirs()
        val out = BufferedWriter(
            OutputStreamWriter(FileOutputStream(active, true), StandardCharsets.UTF_8)
        )
        writer = out
        return out
    }

    private fun closeWriterLocked() {
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        } finally {
            writer = null
        }
    }

    private fun rotateLocked() {
        if (!active.exists() || active.length() == 0L) return
        counter += 1
        val target = File(dir, "$QUEUE_PREFIX${System.currentTimeMillis()}-$counter$SUFFIX")
        if (!active.renameTo(target)) {
            try {
                active.copyTo(target, overwrite = true)
                active.delete()
            } catch (e: Exception) {
                AndroidLog.e(TAG, "rotate failed", e)
            }
        }
    }

    private fun queueFiles(): List<File> =
        dir.listFiles { file ->
            file.isFile && file.name.startsWith(QUEUE_PREFIX) && file.name.endsWith(SUFFIX)
        }?.toList() ?: emptyList()

    private fun maybeMaintainLocked() {
        val now = System.currentTimeMillis()
        if (now - lastMaintenance < 60L * 60 * 1000) return
        lastMaintenance = now
        enforceRetentionLocked()
    }

    private fun enforceRetentionLocked() {
        val cutoff = System.currentTimeMillis() - RETENTION_MS
        val queued = queueFiles()
        queued.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        dir.listFiles { file -> file.isFile && file.name.endsWith(".tmp") }
            ?.filter { it.lastModified() < cutoff }
            ?.forEach { it.delete() }

        var total = (listOf(active) + queueFiles())
            .filter { it.exists() }
            .sumOf { it.length() }
        if (total <= MAX_TOTAL_BYTES) return
        for (file in queueFiles().sortedBy { it.lastModified() }) {
            if (total <= MAX_TOTAL_BYTES) break
            total -= file.length()
            file.delete()
        }
    }
}
