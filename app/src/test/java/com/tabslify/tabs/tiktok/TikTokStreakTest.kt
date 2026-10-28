package com.tabslify.tabs.tiktok

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TikTokStreakTest {

    @Test
    fun sceneEventsSetStateWithoutRenderingSystemMessages() {
        val active = TikTokProto.parse(initResponse(
            sceneMessage("streak_new_recover_with_pet", 20)
        ))
        assertTrue(active.streaks.getValue(CONVERSATION).active)
        assertTrue(active.messages.isEmpty())

        val restorable = TikTokProto.parse(initResponse(sceneMessage("streak_end_with_restore_with_pet", 30)))
        assertTrue(restorable.streaks.getValue(CONVERSATION).restorable)

        val ended = TikTokProto.parse(initResponse(sceneMessage("streak_end_no_restore", 40)))
        assertFalse(ended.streaks.getValue(CONVERSATION).active)
        assertFalse(ended.streaks.getValue(CONVERSATION).restorable)
        assertNull(ended.streaks.getValue(CONVERSATION).value)
    }

    @Test
    fun commandCounterIsKeptUntilAnEndEvent() {
        val update = TikTokProto.parse(initResponse(commandMessage(3, 191, 20)))
        val streak = update.streaks.getValue(CONVERSATION)
        assertTrue(streak.active)
        assertEquals(191, streak.value)

        val ended = TikTokProto.parse(initResponse(commandMessage(3, 191, 20), commandMessage(2, null, 30)))
        assertNull(ended.streaks.getValue(CONVERSATION).value)
        assertFalse(ended.streaks.getValue(CONVERSATION).active)
    }

    @Test
    fun newerStateWinsWhenOlderHistoryIsMerged() {
        val current = TikTokStreak(active = true, value = 9, changedAt = 100)
        val olderEnd = TikTokStreak(changedAt = 20)
        assertEquals(current, TikTokProto.latestStreak(current, olderEnd))
    }

    @Test
    fun frontierUsesDeviceIdForUrlAndUserIdForAuth() {
        assertTrue(TikTokProto.frontierUrl("device-42").contains("device_id=device-42"))
        val auth = String(TikTokProto.buildFrontierAuth("user-99", 123), Charsets.ISO_8859_1)
        assertTrue(auth.contains("user-99"))
    }

    @Test
    fun gzipFrontierPayloadExposesCounterPush() {
        val payload = commandMessage(3, 191, 20)
        val frame = bytesField(8, gzip(payload))
        val push = TikTokProto.parseFrontierStreaks(frame).single()
        assertEquals(CONVERSATION, push.convId)
        assertEquals(3, push.cmdType)
        assertEquals(191, push.counter)
    }

    private fun initResponse(vararg messages: ByteArray): ByteArray {
        val payload = messages.fold(ByteArray(0)) { all, message -> all + bytesField(1, message) }
        return bytesField(6, bytesField(203, payload))
    }

    private fun sceneMessage(scene: String, timestamp: Long): ByteArray = message(timestamp = timestamp, scene = scene)

    private fun commandMessage(command: Int, counter: Int?, timestamp: Long): ByteArray {
        val value = counter?.let { varintField(3, it.toLong()) } ?: ByteArray(0)
        val commandBody = varintField(1, command.toLong()) + bytesField(2, value)
        return message(timestamp = timestamp, contentPb = bytesField(200, commandBody))
    }

    private fun message(
        timestamp: Long,
        scene: String = "",
        contentPb: ByteArray = ByteArray(0)
    ): ByteArray {
        var result = stringField(1, CONVERSATION) + varintField(3, timestamp) +
            varintField(7, 100) + varintField(10, timestamp)
        if (contentPb.isNotEmpty()) result += bytesField(20, contentPb)
        if (scene.isNotEmpty()) result += stringField(21, scene)
        return result
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
        out.toByteArray()
    }

    private fun stringField(number: Int, value: String): ByteArray = bytesField(number, value.toByteArray())

    private fun bytesField(number: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
        writeVarint(out, (number shl 3 or 2).toLong())
        writeVarint(out, value.size.toLong())
        out.write(value)
        out.toByteArray()
    }

    private fun varintField(number: Int, value: Long): ByteArray = ByteArrayOutputStream().use { out ->
        writeVarint(out, (number shl 3).toLong())
        writeVarint(out, value)
        out.toByteArray()
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            out.write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
    }

    private companion object {
        const val CONVERSATION = "0:1:100:200"
    }
}
