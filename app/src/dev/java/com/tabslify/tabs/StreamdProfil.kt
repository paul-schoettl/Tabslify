package com.tabslify.tabs

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tabslify.quiethoursnotificationhelper.laptopName
import com.tabslify.quiethoursnotificationhelper.resolveSyncSecret
import com.tabslify.tabs.authenticator.TotpGenerator
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class StreamProfil(
    val name: String = "",
    val host: String = "192.168.178.72",
    val port: Int = 48500,
    val pcName: String = "",
    val mac: String = "",
    val hoehe: Int = 0,
    val codec: Int = 0,
    val sparsam: Boolean = false
) {
    val anzeige: String get() = name.ifBlank { host }

    val gueltig: Boolean get() = host.isNotBlank() && port in 1..65535
}

object StreamdProfile {

    private const val DATEI = "stream_profile"
    private const val KEY_LISTE = "liste"
    private const val KEY_AUSWAHL = "auswahl"

    private val typ = object : TypeToken<List<StreamProfil>>() {}.type

    fun laden(context: Context): List<StreamProfil> {
        val roh = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).getString(KEY_LISTE, null)
            ?: return listOf(StreamProfil())
        val liste = runCatching { Gson().fromJson<List<StreamProfil>>(roh, typ) }.getOrNull()
        return if (liste.isNullOrEmpty()) listOf(StreamProfil()) else liste
    }

    fun auswahl(context: Context): Int {
        val prefs = context.getSharedPreferences(DATEI, Context.MODE_PRIVATE)
        val anzahl = laden(context).size
        return prefs.getInt(KEY_AUSWAHL, 0).coerceIn(0, anzahl - 1)
    }

    fun speichern(context: Context, liste: List<StreamProfil>, auswahl: Int) {
        context.getSharedPreferences(DATEI, Context.MODE_PRIVATE).edit {
            putString(KEY_LISTE, Gson().toJson(liste))
            putInt(KEY_AUSWAHL, auswahl.coerceAtLeast(0))
        }
    }
}

fun streamTcpErreichbar(host: String, port: Int, timeoutMs: Int = 1500): Boolean {
    if (host.isBlank() || port !in 1..65535) return false
    return try {
        Socket().use {
            it.tcpNoDelay = true
            it.connect(InetSocketAddress(host, port), timeoutMs)
        }
        true
    } catch (_: Exception) {
        false
    }
}

fun streamWakeOnLan(mac: String): Boolean {
    val hex = mac.filter { it.isLetterOrDigit() }
    if (hex.length != 12) return false
    val adresse = ByteArray(6) { i ->
        hex.substring(i * 2, i * 2 + 2).toIntOrNull(16)?.toByte() ?: return false
    }
    val paket = ByteArray(6 + 16 * 6)
    for (i in 0 until 6) paket[i] = 0xFF.toByte()
    for (i in 0 until 16) adresse.copyInto(paket, 6 + i * 6)
    return try {
        DatagramSocket().use { socket ->
            socket.broadcast = true
            for (ziel in listOf("255.255.255.255", "192.168.178.255")) {
                runCatching {
                    socket.send(DatagramPacket(paket, paket.size, InetAddress.getByName(ziel), 9))
                }
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}

fun streamTotp(context: Context, pcName: String): String? {
    val gespeichert = context.getSharedPreferences("sync_prefs", Context.MODE_PRIVATE)
        .getString("last_laptop_name", "").orEmpty()
    val name = pcName.ifBlank { laptopName.ifBlank { gespeichert } }
    if (name.isBlank()) return null
    val secret = resolveSyncSecret(context, name) ?: return null
    return runCatching { TotpGenerator.generateTOTP(secret) }.getOrNull()
}
