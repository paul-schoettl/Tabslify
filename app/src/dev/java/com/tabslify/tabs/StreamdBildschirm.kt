package com.tabslify.tabs

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.tabslify.core.logging.Log
import android.view.KeyEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardAlt
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.tabslify.R
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.streamVollbild
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

private enum class StreamStatus { PRUEFEN, VERBINDEN, WARTE_BILD, LAEUFT, OFFLINE, FEHLER, PAUSIERT }

private fun datenGroesse(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.0f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.0f kB".format(bytes / 1024.0)
    else -> "$bytes B"
}

private fun Context.aktivitaet(): Activity? {
    var aktuell: Context = this
    while (aktuell is ContextWrapper) {
        if (aktuell is Activity) return aktuell
        aktuell = aktuell.baseContext
    }
    return null
}

@Suppress("DEPRECATION")
private fun streamdHoehe(kontext: Context): Int {
    val cm = kontext.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        ?: return 720
    val hatWlan = cm.allNetworks.any { netz ->
        val faehig = cm.getNetworkCapabilities(netz) ?: return@any false
        faehig.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
            faehig.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    return if (hatWlan) 1080 else 720
}

@Suppress("DEPRECATION")
private fun streamdMobilfunk(kontext: Context): Boolean {
    val cm = kontext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val hatWlan = cm.allNetworks.any { netz ->
        val faehig = cm.getNetworkCapabilities(netz) ?: return@any false
        faehig.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            faehig.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    if (hatWlan) return false
    return cm.allNetworks.any { netz ->
        val faehig = cm.getNetworkCapabilities(netz) ?: return@any false
        faehig.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            faehig.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}

private fun hevcMoeglich(): Boolean = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
        !info.isEncoder && info.supportedTypes.any {
            it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
        }
    }
}.getOrDefault(false)

@SuppressLint("SourceLockedOrientationActivity")
@Composable
fun StreamdBildschirm(profil: StreamProfil, onZurueck: () -> Unit) {
    val kontext = LocalContext.current
    val activity = remember(kontext) { kontext.aktivitaet() }
    var codecFallback by remember { mutableStateOf(false) }
    val hevcVerfuegbar = remember { hevcMoeglich() }
    val mobilfunk = remember { streamdMobilfunk(kontext) }
    val autoSparsam = remember(profil, mobilfunk) {
        !profil.sparsam && profil.hoehe == 0 && mobilfunk
    }
    val sparsam = profil.sparsam || autoSparsam
    val hoehe = remember(profil, sparsam) {
        when {
            sparsam -> 720
            profil.hoehe > 0 -> profil.hoehe
            else -> streamdHoehe(kontext)
        }
    }
    val effektiverCodec = when {
        codecFallback -> 1
        profil.codec == 2 -> 2
        profil.codec == 1 -> 1
        hevcVerfuegbar -> 2
        else -> 1
    }
    val fpsWunsch = if (sparsam) 15 else 0
    val bitrateWunsch = when {
        sparsam -> 1500
        effektiverCodec == 2 -> if (hoehe >= 1080) 5100 else 2150
        else -> 0
    }
    val client = remember(profil, codecFallback) {
        StreamdClient(
            host = profil.host,
            port = profil.port,
            qualitaetHoehe = hoehe,
            codecWunsch = effektiverCodec,
            fpsWunsch = fpsWunsch,
            bitrateWunsch = bitrateWunsch,
            authCode = { streamTotp(kontext, profil.pcName) }
        )
    }
    val audio = remember { StreamdAudio() }
    val fokus = remember { FocusRequester() }
    val clipboard = remember {
        kontext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    }

    var flaeche by remember { mutableStateOf<Surface?>(null) }
    var status by remember { mutableStateOf(StreamStatus.PRUEFEN) }
    var fehler by remember { mutableStateOf<String?>(null) }
    var versuch by remember { mutableIntStateOf(0) }
    var vollbild by remember { mutableStateOf(false) }
    var verhaeltnis by remember { mutableFloatStateOf(16f / 9f) }
    var cursor by remember { mutableStateOf<Triple<Float, Float, Boolean>?>(null) }
    var zieht by remember { mutableStateOf(false) }
    var statistikSichtbar by remember { mutableStateOf(false) }
    var statistik by remember { mutableStateOf(StreamdStatistik()) }
    var audioAn by remember { mutableStateOf(false) }
    var textDialog by remember { mutableStateOf(false) }
    var dateienDialog by remember { mutableStateOf(false) }
    var tastenDialog by remember { mutableStateOf(false) }
    var totpWiederholt by remember { mutableStateOf(false) }
    var lupe by remember { mutableIntStateOf(100) }
    var pausiert by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            audio.stoppen()
            client.close()
            streamVollbild = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            activity?.let {
                WindowCompat.getInsetsController(it.window, it.window.decorView)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    DisposableEffect(Unit) {
        val wifi = kontext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val sperre = wifi
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "tabslify-stream")
            ?.also { runCatching { it.acquire() } }
        onDispose {
            if (sperre?.isHeld == true) runCatching { sperre.release() }
        }
    }

    DisposableEffect(vollbild) {
        streamVollbild = vollbild
        val a = activity
        if (a != null) {
            val steuerung = WindowCompat.getInsetsController(a.window, a.window.decorView)
            if (vollbild) {
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                steuerung.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                steuerung.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                steuerung.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, client) {
        val beobachter = LifecycleEventObserver { _, ereignis ->
            when (ereignis) {
                Lifecycle.Event.ON_STOP -> {
                    if (!pausiert) {
                        pausiert = true
                        status = StreamStatus.PAUSIERT
                        client.close()
                    }
                }
                Lifecycle.Event.ON_START -> {
                    if (pausiert) {
                        pausiert = false
                        versuch++
                    }
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(beobachter)
        onDispose { lifecycle.removeObserver(beobachter) }
    }

    LaunchedEffect(Unit) {
        runCatching { fokus.requestFocus() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            statistik = client.statistik
        }
    }

    LaunchedEffect(flaeche, versuch, client) {
        if (pausiert) return@LaunchedEffect
        val ziel = flaeche ?: return@LaunchedEffect
        fehler = null
        status = StreamStatus.PRUEFEN
        val erreichbar = withContext(Dispatchers.IO) {
            streamTcpErreichbar(profil.host, profil.port)
        }
        if (!erreichbar) {
            status = StreamStatus.OFFLINE
            return@LaunchedEffect
        }
        status = StreamStatus.VERBINDEN
        client.starten(
            surface = ziel,
            onConfig = { cfg ->
                if (cfg.hoehe > 0) verhaeltnis = cfg.breite.toFloat() / cfg.hoehe.toFloat()
                if (status != StreamStatus.LAEUFT) status = StreamStatus.WARTE_BILD
                totpWiederholt = false
                client.ansicht(lupe)
                client.audioAktiv(audioAn)
            },
            onFehler = { meldung ->
                when {
                    meldung == "CODEC_HEVC" && !codecFallback -> {
                        codecFallback = true
                        versuch++
                    }
                    meldung.contains("Anmeldung fehlgeschlagen") && !totpWiederholt -> {
                        totpWiederholt = true
                        versuch++
                    }
                    else -> {
                        fehler = meldung
                        status = StreamStatus.FEHLER
                    }
                }
            },
            onCursor = { x, y, sichtbar ->
                if (!zieht && sichtbar) cursor = Triple(x, y, true)
            },
            onClipboard = { text ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    runCatching {
                        clipboard?.setPrimaryClip(ClipData.newPlainText("PC", text))
                    }
                }
            },
            onAudio = { daten -> audio.schreibe(daten) },
            onBild = {
                if (status != StreamStatus.LAEUFT) status = StreamStatus.LAEUFT
            }
        )
    }

    BackHandler(enabled = true) {
        if (vollbild) {
            vollbild = false
        } else {
            onZurueck()
        }
    }

    val dichte = LocalDensity.current.density

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fokus)
            .onPreviewKeyEvent { ereignis ->
                val typ = ereignis.type
                if (typ == KeyEventType.KeyDown || typ == KeyEventType.KeyUp) {
                    val code = ereignis.nativeKeyEvent.keyCode
                    if (code == KeyEvent.KEYCODE_BACK) {
                        false
                    } else {
                        client.taste(code, typ == KeyEventType.KeyDown)
                        true
                    }
                } else {
                    false
                }
            }
            .focusable()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var modus = 0
                    var modusStart = 0L
                    var modusBewegt = false
                    var downZeit = 0L
                    var downX = 0f
                    var downY = 0f
                    var letzteTap = 0L
                    var dragAktiv = false
                    var imSteuer = false
                    var letzteX = 0f
                    var letzteY = 0f
                    var letzteBewegung = 0L
                    var tempo = 0f
                    var letztesZentrum = Offset.Zero
                    var scrollRest = 0f
                    try {
                        while (true) {
                            val ereignis = awaitPointerEvent()
                            val aktive = ereignis.changes.filter { it.pressed }
                            val aenderung = aktive.lastOrNull() ?: ereignis.changes.lastOrNull() ?: continue
                            val pos = aenderung.position
                            val jetzt = System.currentTimeMillis()
                            if (aktive.isNotEmpty()) {
                                val zentrum = if (aktive.size == 1) {
                                    aktive[0].position
                                } else {
                                    aktive.fold(Offset.Zero) { summe, c -> summe + c.position } / aktive.size.toFloat()
                                }
                                if (aktive.size == 1 && modus == 0) {
                                    modus = 1
                                    modusStart = jetzt
                                    modusBewegt = false
                                    dragAktiv = false
                                    letzteX = pos.x
                                    letzteY = pos.y
                                    downZeit = jetzt
                                    downX = pos.x
                                    downY = pos.y
                                    letzteBewegung = jetzt
                                    tempo = 0f
                                    imSteuer = pos.x > size.width - 260f * dichte &&
                                        pos.y < 132f * dichte
                                    if (!imSteuer && jetzt - letzteTap < 350L) {
                                        dragAktiv = true
                                        zieht = true
                                        client.knopf(1, true)
                                    }
                                } else if (aktive.size >= 2) {
                                    if (dragAktiv) {
                                        dragAktiv = false
                                        client.knopf(1, false)
                                    }
                                    if (modus < 2) {
                                        modus = 2
                                        modusStart = jetzt
                                        modusBewegt = false
                                        letztesZentrum = zentrum
                                    } else if (aktive.size >= 3 && modus == 2) {
                                        modus = 3
                                        modusStart = jetzt
                                        modusBewegt = false
                                        letztesZentrum = zentrum
                                    }
                                }

                                if (modus == 1 && aktive.size == 1) {
                                    val dx = pos.x - letzteX
                                    val dy = pos.y - letzteY
                                    if (hypot(pos.x - downX, pos.y - downY) > 8f * dichte) {
                                        modusBewegt = true
                                    }
                                    if (!imSteuer && (dx != 0f || dy != 0f)) {
                                        val basis = cursor ?: Triple(0.5f, 0.5f, true)
                                        val einheit = dx / dichte
                                        val einheitY = dy / dichte
                                        val dt = (jetzt - letzteBewegung).coerceIn(1L, 200L)
                                        letzteBewegung = jetzt
                                        val gemessen = hypot(einheit, einheitY) / (dt / 1000f)
                                        tempo = if (dt >= 120L) gemessen else tempo + (gemessen - tempo) * 0.25f
                                        val anteil = ((tempo - 80f) / (800f - 80f)).coerceIn(0f, 1f)
                                        val gewinn = 2.6f + (5.4f - 2.6f) * anteil
                                        val cx = (basis.first + einheit * gewinn / 1920f).coerceIn(0f, 1f)
                                        val cy = (basis.second + einheitY * gewinn / 1080f).coerceIn(0f, 1f)
                                        cursor = Triple(cx, cy, true)
                                        zieht = true
                                        client.zeiger(cx, cy)
                                    }
                                } else if (modus == 2 && aktive.size >= 2) {
                                    val dy = zentrum.y - letztesZentrum.y
                                    if (abs(dy) > 12f * dichte) modusBewegt = true
                                    scrollRest += dy / dichte
                                    var schritte = 0
                                    while (abs(scrollRest) >= 24f && schritte < 6) {
                                        client.rad(if (scrollRest > 0) 1 else -1)
                                        scrollRest -= if (scrollRest > 0) 24f else -24f
                                        schritte++
                                    }
                                    letztesZentrum = zentrum
                                }
                                if (modus == 1) {
                                    letzteX = pos.x
                                    letzteY = pos.y
                                }
                            }
                            if (ereignis.type == PointerEventType.Scroll) {
                                val delta = ereignis.changes.lastOrNull()?.scrollDelta?.y ?: 0f
                                if (delta != 0f) client.rad(delta.toInt())
                            }

                            val nochAktiv = ereignis.changes.count { it.pressed }
                            if (nochAktiv == 0 && modus != 0) {
                                val dauer = jetzt - modusStart
                                if (modus == 1) {
                                    if (!imSteuer) {
                                        if (dragAktiv) {
                                            dragAktiv = false
                                            client.knopf(1, false)
                                            letzteTap = 0L
                                        } else if (!modusBewegt && jetzt - downZeit < 260L) {
                                            client.knopf(1, true)
                                            client.knopf(1, false)
                                            letzteTap = jetzt
                                        } else if (!modusBewegt && jetzt - downZeit >= 600L) {
                                            client.knopf(2, true)
                                            client.knopf(2, false)
                                            letzteTap = 0L
                                        } else {
                                            letzteTap = 0L
                                        }
                                    }
                                } else if (modus == 2 && !modusBewegt && dauer < 350L) {
                                    client.knopf(2, true)
                                    client.knopf(2, false)
                                    letzteTap = 0L
                                } else if (modus == 3 && !modusBewegt && dauer < 350L) {
                                    client.knopf(3, true)
                                    client.knopf(3, false)
                                    letzteTap = 0L
                                }
                                if (modus >= 2) letzteTap = 0L
                                zieht = false
                                modus = 0
                            }
                        }
                    } finally {
                        if (dragAktiv) client.knopf(1, false)
                        zieht = false
                    }
                }
            }
    ) {
        val platzVerhaeltnis = maxWidth.value / maxHeight.value
        val zielBreite = if (platzVerhaeltnis > verhaeltnis) maxHeight * verhaeltnis else maxWidth
        val zielHoehe = zielBreite / verhaeltnis

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(zielBreite, zielHoehe)
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    SurfaceView(ctx).also { view ->
                        view.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                Log.i("CLOUDSA", "[STREAM] Surface erstellt")
                                flaeche = holder.surface
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                Log.i("CLOUDSA", "[STREAM] Surface zerstoert")
                                flaeche = null
                                client.close()
                            }
                        })
                    }
                }
            )
            val c = cursor
            if (c != null) {
                Canvas(Modifier.fillMaxSize()) {
                    val z = lupe / 100f
                    val rw = 1f / z
                    val rh = 1f / z
                    val links = (c.first - rw / 2f).coerceIn(0f, 1f - rw)
                    val oben = (c.second - rh / 2f).coerceIn(0f, 1f - rh)
                    val vx = (c.first - links) / rw
                    val vy = (c.second - oben) / rh
                    if (vx !in 0f..1f || vy !in 0f..1f) return@Canvas
                    val k = size.width / 1920f
                    val x = vx * size.width
                    val y = vy * size.height
                    val p = Path()
                    p.moveTo(x, y)
                    p.lineTo(x, y + 16f * 1.4f * k)
                    p.lineTo(x + 4f * 1.4f * k, y + 12f * 1.4f * k)
                    p.lineTo(x + 7f * 1.4f * k, y + 17f * 1.4f * k)
                    p.lineTo(x + 9f * 1.4f * k, y + 16f * 1.4f * k)
                    p.lineTo(x + 6f * 1.4f * k, y + 10f * 1.4f * k)
                    p.lineTo(x + 10f * 1.4f * k, y + 10f * 1.4f * k)
                    p.close()
                    drawPath(p, Color.White)
                    drawPath(p, Color.Black, style = Stroke(width = 2f * k))
                }
            }
        }

        if (statistikSichtbar) {
            val s = statistik
            val codec = if (s.codec == 2) "HEVC" else "H.264"
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .background(Color(0x99000000), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = "${s.breite}×${s.hoehe} · ${codec} · ${"%.1f".format(s.fps)} fps · " +
                        "${"%.1f".format(s.kbit / 1000.0)} Mbit/s · ${if (s.rttMs >= 0) "${s.rttMs} ms" else "–"}" +
                        (if (lupe > 100) " · Lupe ${lupe / 100}×" else "") +
                        (if (sparsam) " · ${stringResource(R.string.stream_sparsam)}" else ""),
                    color = TextPrimary
                )
                Text(
                    text = stringResource(R.string.stream_daten) + ": " + datenGroesse(s.bytes),
                    color = TextSecondary
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = 8.dp, vertical = 16.dp)
                .zIndex(10f),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { tastenDialog = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.KeyboardAlt,
                        contentDescription = stringResource(R.string.stream_tasten),
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { vollbild = !vollbild }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = if (vollbild) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                        contentDescription = stringResource(R.string.vollbild),
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { onZurueck() }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.abbrechen),
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = {
                        lupe = when (lupe) {
                            100 -> 200
                            200 -> 400
                            else -> 100
                        }
                        client.ansicht(lupe)
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.ZoomIn,
                        contentDescription = if (lupe > 100) {
                            stringResource(R.string.stream_lupe_stufe, lupe / 100)
                        } else {
                            stringResource(R.string.stream_lupe)
                        },
                        tint = if (lupe > 100) Color(0xFFFFA500) else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { statistikSichtbar = !statistikSichtbar }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.BarChart,
                        contentDescription = stringResource(R.string.stream_statistik),
                        tint = if (statistikSichtbar) Color(0xFFFFA500) else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = {
                        audioAn = !audioAn
                        client.audioAktiv(audioAn)
                        if (audioAn) audio.starten() else audio.stoppen()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        if (audioAn) Icons.AutoMirrored.Filled.VolumeUp
                        else Icons.AutoMirrored.Filled.VolumeOff,
                        contentDescription = stringResource(R.string.stream_audio),
                        tint = if (audioAn) Color(0xFFFFA500) else TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = {
                        val text = clipboard?.primaryClip
                            ?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)
                            ?.coerceToText(kontext)
                            ?.toString()
                        if (!text.isNullOrEmpty()) client.clipboardSet(text)
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        Icons.Default.ContentPaste,
                        contentDescription = stringResource(R.string.stream_clipboard),
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { textDialog = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Keyboard,
                        contentDescription = stringResource(R.string.stream_text),
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = { dateienDialog = true }, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = stringResource(R.string.stream_dateien),
                        tint = TextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        when (status) {
            StreamStatus.PRUEFEN, StreamStatus.VERBINDEN, StreamStatus.WARTE_BILD -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xCC000000), RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(color = TextPrimary)
                    Text(
                        text = when (status) {
                            StreamStatus.PRUEFEN -> stringResource(R.string.stream_pruefe)
                            StreamStatus.VERBINDEN -> stringResource(R.string.stream_verbinde, profil.host)
                            else -> stringResource(R.string.stream_warte_bild)
                        },
                        color = TextPrimary
                    )
                }
            }
            StreamStatus.PAUSIERT -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color(0xCC000000), RoundedCornerShape(14.dp))
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.stream_pausiert), color = TextPrimary)
                }
            }
            StreamStatus.OFFLINE -> {
                Card(
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xEE1E1E1E))
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(stringResource(R.string.stream_offline), color = TextSecondary)
                        Text("${profil.host}:${profil.port}", color = TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (profil.mac.isNotBlank()) {
                                Button(onClick = {
                                    versuch++
                                    val mac = profil.mac
                                    Thread({ streamWakeOnLan(mac) }, "stream-wol").start()
                                }) {
                                    Text(stringResource(R.string.stream_aufwecken), color = TextPrimary)
                                }
                            }
                            Button(onClick = { versuch++ }) {
                                Text(stringResource(R.string.verbinden), color = TextPrimary)
                            }
                        }
                    }
                }
            }
            StreamStatus.FEHLER -> {
                val meldung = fehler
                if (meldung != null) {
                    Card(
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xEE1E1E1E))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(meldung, color = TextSecondary)
                            Text("${profil.host}:${profil.port}", color = TextSecondary)
                            Button(onClick = { versuch++ }) {
                                Text(stringResource(R.string.verbinden), color = TextPrimary)
                            }
                        }
                    }
                }
            }
            StreamStatus.LAEUFT -> Unit
        }
    }

    if (textDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { textDialog = false },
            title = { Text(stringResource(R.string.stream_text)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(R.string.stream_text)) },
                    modifier = Modifier.width(320.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    client.text(text)
                    textDialog = false
                }) {
                    Text(stringResource(R.string.stream_senden))
                }
            },
            dismissButton = {
                TextButton(onClick = { textDialog = false }) {
                    Text(stringResource(R.string.abbrechen))
                }
            }
        )
    }

    if (tastenDialog) {
        StreamdTastenDialog(
            onSchliessen = { tastenDialog = false },
            onFolge = { folge ->
                folge.forEach { (code, gedrueckt) -> client.taste(code, gedrueckt) }
            }
        )
    }

    if (dateienDialog) {
        StreamdDateien(
            host = profil.host,
            authCode = { streamTotp(kontext, profil.pcName) },
            onSchliessen = { dateienDialog = false }
        )
    }
}
