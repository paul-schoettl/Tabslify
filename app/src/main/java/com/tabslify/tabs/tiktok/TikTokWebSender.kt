package com.tabslify.tabs.tiktok

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.tabslify.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface TikTokSendState {
    data object Idle : TikTokSendState
    data object Preparing : TikTokSendState
    data object SelectingChat : TikTokSendState
    data object Typing : TikTokSendState
    data object Verifying : TikTokSendState
    data object Done : TikTokSendState
    data class Failed(val reason: String) : TikTokSendState
}

object TikTokWebSender {

    private const val TAG = "TikTokWeb"
    private const val MESSAGES_URL = "https://www.tiktok.com/messages"
    private const val DESKTOP_UA =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 45_000L
    private const val STEP_DELAY_MS = 700L
    private const val MAX_POLLS = 12
    private const val MAX_RETAPS = 2

    private fun blockedByAllowlist(convId: String): String? =
        TikTokSendPolicy.handleBlockedReason(convId)

    private val SELF_LABELS = listOf("Notizen", "Notes", "Saved Messages", "Message yourself", "Notiz", "Gemerkt")

    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<TikTokSendState>(TikTokSendState.Idle)
    val state: StateFlow<TikTokSendState> = _state.asStateFlow()

    private var webView: WebView? = null
    private var pageReady = false
    private var repairDone = false

    private data class SendJob(
        val convId: String,
        val names: List<String>,
        val uids: List<String>,
        val verifyHeader: Boolean,
        val text: String,
        val startedAt: Long,
        val onResult: (Boolean, String) -> Unit,
        var selectDone: Boolean = false,
        var selectScheduled: Boolean = false,
        var selectRunning: Boolean = false,
        var wrongPages: Int = 0,
        var retaps: Int = 0,
        var observed: Boolean = false,
        var tapCssX: Float = 0f,
        var tapCssY: Float = 0f,
        var tapScale: Float = 1f
    )

    private fun logTapDiag(job: SendJob) {
        if (expired(job)) return
        val js = TAP_DIAG_JS
            .replace("__X__", job.tapCssX.toInt().toString())
            .replace("__Y__", job.tapCssY.toInt().toString())
            .replace("__CONV__", JSONObject.quote(job.convId))
        eval(js) { raw -> Log.d(TAG, "tap diag -> ${raw.unquoteJs()}") }
    }

    private var current: SendJob? = null

    @SuppressLint("SetJavaScriptEnabled")
    fun attach(view: WebView) {
        if (webView === view) return
        webView = view
        pageReady = false
        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadsImagesAutomatically = true
            blockNetworkImage = false
            userAgentString = DESKTOP_UA
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = false
            loadWithOverviewMode = false
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                if (message == null) return true
                val line = message.lineNumber()
                val src = (message.sourceId() ?: "").takeLast(90)
                Log.d(TAG, "console: ${message.message()} @${src.ifEmpty { "?" }}${if (line > 0) ":$line" else ""}")
                return true
            }
        }
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.resumeTimers()
        Log.d(TAG, "renderer: software layer + resumeTimers")
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "page finished: $url")
                if (url != null && url.startsWith("https://www.tiktok.com/messages")) {
                    pageReady = true
                    repairDone = false
                    CookieManager.getInstance().flush()
                    eval(PAGE_INFO_JS) { raw -> Log.d(TAG, "page info -> ${raw.unquoteJs()}") }
                    later(1500) { runRepair() }
                    current?.let { job ->
                        if (!job.selectDone && !job.selectScheduled) {
                            job.selectScheduled = true
                            main.postDelayed({ runSelect(job, 0) }, 2500)
                        }
                    }
                } else if (url != null && current != null &&
                    (url.contains("login") || url.contains("signup") || url.contains("auth"))
                ) {
                    fail("Nicht eingeloggt (Login-Seite)")
                    finish(false, "Nicht eingeloggt")
                }
            }
        }
        Log.d(TAG, "webview attached, " + stateLine(view))
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                Log.d(TAG, "webview attachedToWindow, " + stateLine(view))
            }

            override fun onViewDetachedFromWindow(v: View) {
                Log.d(TAG, "webview DETACHED from window, " + stateLine(view))
            }
        })
        if (current == null) {
            val urlNow = view.url
            if (urlNow == null || urlNow == "about:blank") {
                Log.d(TAG, "eager load der Nachrichten-Seite")
                view.loadUrl(MESSAGES_URL)
            }
        }
    }

    private fun stateLine(view: WebView): String =
        "att=${view.isAttachedToWindow},shown=${view.isShown},token=${view.windowToken != null},hidden=${view.visibility != View.VISIBLE},size=${view.width}x${view.height}"

    fun onVisible() {
        val view = webView
        if (view == null) {
            Log.d(TAG, "visible: webview fehlt")
            return
        }
        Log.d(TAG, "visible: " + stateLine(view) + " job=" + (current != null))
        view.requestLayout()
        view.invalidate()
        eval(ENV_JS) { raw -> Log.d(TAG, "env vis -> ${raw.unquoteJs()}") }
    }

    fun reset() {
        current = null
        _state.value = TikTokSendState.Idle
    }

    fun send(
        scope: CoroutineScope,
        context: Context,
        convId: String,
        displayName: String,
        uniqueId: String,
        text: String,
        onResult: (Boolean, String) -> Unit
    ) {
        Log.d(TAG, "send() convId=$convId active=${current != null} state=${_state.value}")
        blockedByAllowlist(convId)?.let { reason ->
            Log.w(TAG, "send blocked, uid not in allowlist: $reason")
            _state.value = TikTokSendState.Failed("Senden nur an eigene Accounts (Testmodus)")
            onResult(false, "Senden ist im Testmodus auf eigene Accounts beschränkt")
            return
        }
        if (!TikTokSendPolicy.recipientAllowed(uniqueId)) {
            Log.w(TAG, "send blocked, recipient not in allowlist: uid=$uniqueId")
            _state.value = TikTokSendState.Failed("Empfänger nicht freigegeben")
            onResult(false, "Senden ist ausschließlich an programmi erlaubt")
            return
        }
        val stale = current
        if (stale != null) {
            if (System.currentTimeMillis() - stale.startedAt > TIMEOUT_MS) {
                Log.w(TAG, "stale job detected, resetting")
                current = null
                _state.value = TikTokSendState.Idle
            } else {
                onResult(false, "Bereits ein Sendevorgang aktiv")
                return
            }
        }
        val clean = text.trim()
        if (clean.isEmpty()) {
            onResult(false, "Leere Nachricht")
            return
        }
        _state.value = TikTokSendState.Preparing
        scope.launch {
            val session = try {
                withContext(Dispatchers.IO) { TikTokCookieRepository.session(context) }
            } catch (e: Exception) {
                fail("Kein Cookie: ${e.message}")
                onResult(false, "Kein Cookie")
                return@launch
            }
            if (session.cookie.isEmpty()) {
                fail("Kein Cookie")
                onResult(false, "Kein Cookie")
                return@launch
            }
            installCookies(session.cookie)
            var resolvedName = displayName
            if (resolvedName.isBlank() && uniqueId.isNotBlank()) {
                val fresh = runCatching {
                    withContext(Dispatchers.IO) { TikTokDmClient.resolvePeerName(context, uniqueId) }
                }.getOrNull()
                if (!fresh.isNullOrBlank()) {
                    Log.d(TAG, "peer name refreshed: $fresh")
                    resolvedName = fresh
                }
            }
            val distinctUids = TikTokSendPolicy.convUidsOf(convId)
            val isSelfChat = distinctUids.size <= 1
            val searchNames = (listOf(resolvedName, uniqueId) + if (isSelfChat) SELF_LABELS else emptyList())
                .filter { it.isNotBlank() }.distinct()
            val allowedHandle = TikTokSendPolicy.SELF_HANDLE_BY_UID[uniqueId]
            if (allowedHandle == null) {
                Log.w(TAG, "send blocked, uid has no self handle: uid=$uniqueId")
                _state.value = TikTokSendState.Failed("Account nicht freigegeben")
                onResult(false, "Senden ist im Testmodus auf eigene Accounts beschränkt")
                return@launch
            }
            Log.d(TAG, "selfChat=$isSelfChat allowedHandle=$allowedHandle")
            val job = SendJob(
                convId = convId,
                names = searchNames,
                uids = listOf(uniqueId).filter { it.isNotBlank() }.distinct(),
                verifyHeader = isSelfChat,
                text = clean,
                startedAt = System.currentTimeMillis(),
                onResult = onResult
            )
            Log.d(TAG, "send job convId=${job.convId} names=${job.names} uids=${job.uids}")
            if (job.names.isEmpty() && job.uids.isEmpty()) {
                fail("Chat ohne Namen/ID nicht zuordenbar")
                finish(false, "Chat kann nicht zugeordnet werden")
                return@launch
            }
            current = job
            main.postDelayed({ watchdog(job) }, TIMEOUT_MS + 5000)
            val view = webView
            if (view == null) {
                fail("WebView nicht bereit")
                finish(false, "WebView nicht bereit")
                return@launch
            }
            Log.d(TAG, "send view state: " + stateLine(view))
            Log.d(TAG, "loading messages page (fresh, kein alter Chat offen)")
            job.selectDone = false
            pageReady = false
            view.loadUrl(MESSAGES_URL)
        }
    }

    private fun installCookies(cookieHeader: String) {
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        var count = 0
        var domainCount = 0
        for (part in cookieHeader.split(';')) {
            val trimmed = part.trim()
            if (trimmed.isEmpty() || !trimmed.contains('=')) continue
            val name = trimmed.substringBefore('=').trim()
            if (name.isEmpty() || name.startsWith("$")) continue
            manager.setCookie("https://www.tiktok.com", trimmed)
            manager.setCookie("https://www.tiktok.com", "$trimmed; domain=.tiktok.com; path=/")
            domainCount++
            count++
        }
        manager.flush()
        Log.d(TAG, "installed $count cookies, davon domain=.tiktok.com: $domainCount")
    }

    private fun watchdog(job: SendJob) {
        if (current === job) {
            Log.w(TAG, "watchdog timeout, last state=${_state.value}")
            fail("Timeout (Watchdog)")
            finish(false, "Timeout")
        }
    }

    private fun expired(job: SendJob): Boolean {
        if (System.currentTimeMillis() - job.startedAt > TIMEOUT_MS) {
            fail("Timeout")
            finish(false, "Timeout")
            return true
        }
        if (current !== job) return true
        return false
    }

    private fun fail(reason: String) {
        Log.w(TAG, "send failed: $reason")
        _state.value = TikTokSendState.Failed(reason)
    }

    private fun finish(ok: Boolean, message: String) {
        val job = current
        current = null
        if (ok) _state.value = TikTokSendState.Done
        main.post { job?.onResult?.invoke(ok, message) }
        main.postDelayed({ if (current == null) _state.value = TikTokSendState.Idle }, 2500)
    }

    private fun eval(js: String, callback: (String?) -> Unit) {
        val view = webView
        if (view == null) {
            callback(null)
            return
        }
        main.post { view.evaluateJavascript(js, callback) }
    }

    private fun later(delayMs: Long, block: () -> Unit) {
        main.postDelayed(block, delayMs)
    }

    private fun runRepair(force: Boolean = false) {
        if (!force && repairDone) return
        repairDone = true
        eval(REPAIR_JS) { raw -> Log.d(TAG, "repair -> ${raw.unquoteJs()}") }
    }

    private fun runSelect(job: SendJob, poll: Int) {
        if (expired(job)) return
        if (job.selectDone || job.selectRunning) return
        job.selectRunning = true
        _state.value = TikTokSendState.SelectingChat
        val namesJson = job.names.joinToString(",", "[", "]") { JSONObject.quote(it) }
        val uidsJson = job.uids.joinToString(",", "[", "]") { JSONObject.quote(it) }
        val js = SELECT_JS
            .replace("__NAMES__", namesJson)
            .replace("__UIDS__", uidsJson)
            .replace("__CONV__", JSONObject.quote(job.convId))
            .replace("__POLL__", poll.toString())
        eval(js) { raw ->
            job.selectRunning = false
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "select -> $result")
            val match = Regex("at=(-?\\d+),(-?\\d+)(?:,dpr=([\\d.]+))?").find(result)
            when {
                match != null -> {
                    val dpr = match.groupValues[3].toDoubleOrNull() ?: 1.0
                    val cssX = match.groupValues[1].toFloat()
                    val cssY = match.groupValues[2].toFloat()
                    job.tapCssX = cssX
                    job.tapCssY = cssY
                    job.selectDone = true
                    val view = webView
                    if (view == null) {
                        fail("WebView nicht da")
                        finish(false, "WebView nicht da")
                    } else {
                        val inner = Regex("inner=([\\d.]+)").find(result)?.groupValues?.get(1)?.toFloatOrNull()
                        val scale = if (inner != null && inner > 0f && view.width > 0) {
                            view.width.toFloat() / inner
                        } else {
                            dpr.toFloat()
                        }
                        job.tapScale = scale
                        Log.d(TAG, "tap scale=$scale inner=$inner viewW=${view.width} viewH=${view.height}")
                        runPrecheck(job, poll)
                    }
                }
                result.startsWith("convonly:") -> {
                    fail("Chat-Zeile nicht eindeutig ($result)")
                    finish(false, "Chat nicht eindeutig zuordenbar")
                }
                result.startsWith("notfound:") && poll < MAX_POLLS -> {
                    later(1500) { runSelect(job, poll + 1) }
                }
                result.startsWith("notfound:") -> {
                    val searched = job.names.take(2).joinToString("/").take(48)
                    fail("Chat nicht gefunden ($result)")
                    finish(false, "Chat nicht gefunden (gesucht: $searched)")
                }
                else -> {
                    fail("Chat-Auswahl unklar ($result)")
                    finish(false, "Chat-Auswahl unklar")
                }
            }
        }
    }

    private fun runPrecheck(job: SendJob, poll: Int) {
        if (expired(job)) return
        val js = TAP_PRECHECK_JS
            .replace("__X__", job.tapCssX.toInt().toString())
            .replace("__Y__", job.tapCssY.toInt().toString())
            .replace("__CONV__", JSONObject.quote(job.convId))
        eval(js) { raw ->
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "precheck -> $result")
            if (result.startsWith("precheck:ok")) {
                doTap(job)
            } else if (poll < MAX_POLLS) {
                job.selectDone = false
                Log.d(TAG, "Tap-Punkt nicht (mehr) die Zeile, frisch messen")
                runRepair(true)
                later(1500) { runSelect(job, poll + 1) }
            } else {
                fail("Tap-Punkt verifiziert nicht ($result)")
                finish(false, "Chat-Zeile verschoben - nicht getippt")
            }
        }
    }

    private fun doTap(job: SendJob) {
        if (expired(job)) return
        if (!job.observed) {
            job.observed = true
            Log.d(TAG, "beobachtung ohne tap")
            later(250) { logTapDiag(job) }
            later(700) { logTapDiag(job) }
            later(1400) { logTapDiag(job) }
            later(2000) { logTapDiag(job) }
            later(2900) { doTap(job) }
            return
        }
        val view = webView
        if (view == null) {
            fail("WebView nicht da")
            finish(false, "WebView nicht da")
            return
        }
        tapAt(view, job.tapCssX * job.tapScale, job.tapCssY * job.tapScale)
        later(250) { logTapDiag(job) }
        later(700) { logTapDiag(job) }
        later(1400) { logTapDiag(job) }
        later(2800) { runWaitInput(job, 0) }
    }

    private fun tapAt(view: WebView, x: Float, y: Float) {
        try {
            val down = SystemClock.uptimeMillis()
            view.dispatchTouchEvent(
                MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
            )
            Thread.sleep(70)
            view.dispatchTouchEvent(
                MotionEvent.obtain(down, down + 70, MotionEvent.ACTION_UP, x, y, 0)
            )
            Log.d(TAG, "tap -> ${x.toInt()},${y.toInt()}")
        } catch (e: Exception) {
            Log.d(TAG, "tap failed: ${e.message}")
        }
    }

    private fun runWaitInput(job: SendJob, poll: Int) {
        if (expired(job)) return
        eval(INPUT_PROBE_JS) { raw ->
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "input probe -> $result")
            when {
                result == "input:ok" -> runVerifyPane(job)
                result.startsWith("input:wrongpage") -> {
                    if (job.wrongPages >= 2) {
                        fail("Falsche Seite geöffnet ($result)")
                        finish(false, "Falscher Chat geöffnet")
                    } else {
                        job.wrongPages++
                        job.selectDone = false
                        Log.d(TAG, "wrong page, back + reselect (${job.wrongPages})")
                        eval("history.back()") { later(1500) { runSelect(job, 0) } }
                    }
                }
                poll >= 1 && job.retaps < MAX_RETAPS -> {
                    job.retaps++
                    Log.d(TAG, "kein Editor, Re-Select + Re-Tap (${job.retaps}/$MAX_RETAPS)")
                    job.selectDone = false
                    later(800) { runSelect(job, 0) }
                }
                poll < MAX_POLLS -> {
                    later(1500) { runWaitInput(job, poll + 1) }
                }
                else -> {
                    fail("Eingabefeld nicht gefunden")
                    finish(false, "Eingabefeld nicht gefunden")
                }
            }
        }
    }

    private fun runVerifyPane(job: SendJob) {
        if (expired(job)) return
        eval(PANE_JS) { raw ->
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "pane -> $result")
            val handles = Regex("handles=([^,]*)").find(result)?.groupValues?.get(1)
                ?.split(",")?.map { it.removePrefix("/@").trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
            val foreign = handles.filter { it !in TikTokSendPolicy.SELF_HANDLES }
            val known = handles.any { it in TikTokSendPolicy.SELF_HANDLES }
            when {
                result.startsWith("pane:ok") && known && foreign.isEmpty() -> runType(job)
                result.startsWith("pane:ok") && foreign.isNotEmpty() -> {
                    fail("Fremder Chat offen ($result)")
                    finish(false, "Fremder Chat geöffnet - nichts gesendet")
                }
                else -> {
                    fail("Chat-Pane zweifelhaft ($result)")
                    finish(false, "Chat nicht verifiziert - nichts gesendet")
                }
            }
        }
    }

    private fun runType(job: SendJob) {
        if (expired(job)) return
        _state.value = TikTokSendState.Typing
        eval(PREP_JS) { raw ->
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "prep -> $result")
            if (!result.startsWith("prepared:")) {
                fail("Eingabefeld nicht fokussierbar ($result)")
                finish(false, "Eingabefeld nicht fokussierbar")
                return@eval
            }
            later(400) { typeTrusted(job, 0) }
        }
    }

    private fun typeTrusted(job: SendJob, attempt: Int) {
        if (expired(job)) return
        val view = webView
        if (view == null) {
            fail("WebView nicht da")
            finish(false, "WebView nicht da")
            return
        }
        view.requestFocus()
        if (attempt == 0) dispatchTextKeys(view, job.text)
        later(900) { checkTyped(job, attempt) }
    }

    private fun checkTyped(job: SendJob, attempt: Int) {
        if (expired(job)) return
        val js = EDITOR_TEXT_JS.replace("__TEXT__", JSONObject.quote(job.text))
        eval(js) { raw ->
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "typed check -> $result")
            when {
                result.startsWith("text:1") -> later(STEP_DELAY_MS) { runSend(job) }
                attempt == 0 -> later(1200) { checkTyped(job, 1) }
                else -> {
                    fail("Tippen fehlgeschlagen ($result)")
                    finish(false, "Tippen fehlgeschlagen")
                }
            }
        }
    }

    private fun dispatchTextKeys(view: WebView, text: String) {
        try {
            view.requestFocus()
            val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            var sent = 0
            for (ch in text) {
                val events = map.getEvents(charArrayOf(ch)) ?: continue
                for (e in events) {
                    if (e.action == KeyEvent.ACTION_DOWN) {
                        val typed = KeyEvent(
                            e.downTime, e.eventTime, KeyEvent.ACTION_DOWN, e.keyCode,
                            e.repeatCount, e.metaState, ch.code, 0
                        )
                        if (view.dispatchKeyEvent(typed)) sent++
                    }
                }
            }
            Log.d(TAG, "keys dispatched: $sent")
        } catch (e: Exception) {
            Log.d(TAG, "keys failed: ${e.message}")
        }
    }

    private fun runSend(job: SendJob) {
        if (expired(job)) return
        eval(SEND_JS) { raw ->
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "send click -> $result")
            if (result.startsWith("nobutton")) dispatchEnter()
            later(STEP_DELAY_MS) { runVerify(job, 0) }
        }
    }

    private fun dispatchEnter() {
        try {
            val view = webView ?: return
            val now = SystemClock.uptimeMillis()
            view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0))
            view.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0))
            Log.d(TAG, "enter dispatched")
        } catch (e: Exception) {
            Log.d(TAG, "enter failed: ${e.message}")
        }
    }

    private fun runVerify(job: SendJob, poll: Int) {
        if (expired(job)) return
        _state.value = TikTokSendState.Verifying
        val js = VERIFY_JS.replace("__TEXT__", JSONObject.quote(job.text))
        eval(js) { raw ->
            if (expired(job)) return@eval
            val result = raw.unquoteJs()
            Log.d(TAG, "verify -> $result")
            when {
                result == "verify:ok" -> {
                    _state.value = TikTokSendState.Done
                    finish(true, "Gesendet")
                }
                poll < MAX_POLLS -> later(1500) { runVerify(job, poll + 1) }
                else -> {
                    fail("Senden unbestätigt ($result)")
                    finish(false, "Senden unbestätigt")
                }
            }
        }
    }

    private fun String?.unquoteJs(): String {
        if (this == null) return ""
        var s = trim()
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }

private val PAGE_INFO_JS = """
        (function(){
          try {
            var body = (document.body && document.body.innerText) ? document.body.innerText : "";
            var lower = body.toLowerCase();
            var err = lower.indexOf("entschuldigung");
            if (err < 0) err = lower.indexOf("etwas stimmt mit dem server");
            if (err < 0) err = lower.indexOf("something went wrong");
            var dm = document.querySelector('[data-e2e="top-dm-icon"]');
            var r = dm ? dm.getBoundingClientRect() : null;
            return JSON.stringify({
              inner: [window.innerWidth, window.innerHeight],
              dpr: window.devicePixelRatio,
              title: (document.title || "").slice(0, 60),
              login: lower.indexOf("anmelden") >= 0 ? "ja" : "nein",
              error: err >= 0 ? body.slice(Math.max(0, err - 40), err + 80).replace(/\s+/g, " ") : "keine",
              convRows: document.querySelectorAll('[data-conv-id]').length,
              editables: document.querySelectorAll('[contenteditable="true"]').length,
              dmIcon: r ? Math.round(r.width) + "x" + Math.round(r.height) : "keine",
              head: body.replace(/\s+/g, " ").slice(0, 150)
            });
          } catch (e) { return "info:error:" + e.message; }
        })()
    """.trimIndent()

private val REPAIR_JS = """
        (function(){
          try {
            var vh = Math.max(window.innerHeight || 0, document.documentElement.clientHeight || 0);
            var before = Math.round(document.documentElement.getBoundingClientRect().height);
            var docEl = document.documentElement;
            var body = document.body;
            if (before < 100 && vh > 100) {
              docEl.style.height = vh + "px";
              docEl.style.minHeight = vh + "px";
              body.style.height = vh + "px";
              body.style.minHeight = vh + "px";
              body.style.overflowY = "auto";
            }
            var probe = document.createElement("div");
            probe.style.cssText = "position:absolute;height:100%;width:2px;left:0;top:0;pointer-events:none;";
            (body || docEl).appendChild(probe);
            var icb = Math.round(probe.getBoundingClientRect().height);
            probe.remove();
            var after = Math.round(docEl.getBoundingClientRect().height);
            var bodyAfter = Math.round(body.getBoundingClientRect().height);
            var el = document.elementFromPoint(233, 388);
            return "repair:vh=" + vh + ",icb=" + icb + ",html=" + before + ">" + after
              + ",body=" + bodyAfter + ",hit=" + (el ? el.tagName : "kein")
              + ",conv=" + (el && el.closest && el.closest("[data-conv-id]") ? "ja" : "keine");
          } catch (e) { return "repair:error:" + e.message; }
        })()
    """.trimIndent()

private val ENV_JS = """
        (function(){
          try {
            var vv = window.visualViewport;
            var hr = document.documentElement.getBoundingClientRect();
            var row = document.querySelector('[data-conv-id]');
            var rr = row ? row.getBoundingClientRect() : null;
            var pts = null;
            try { pts = document.elementsFromPoint(233, 388).slice(0, 5).map(function(e){ return e.tagName; }); } catch (e5) {}
            function cs(el) {
              if (!el) return null;
              var s = getComputedStyle(el);
              return "pe=" + s.pointerEvents + ",v=" + s.visibility + ",d=" + s.display.slice(0, 10)
                + ",tf=" + (s.transform || "").slice(0, 40) + ",zo=" + (s.zoom || "-") + ",op=" + s.opacity;
            }
            var chain = [];
            try {
              var cur = row, d = 0;
              while (cur && d < 9) {
                chain.push(cur.tagName + ":" + cs(cur));
                cur = cur.offsetParent; d++;
              }
            } catch (e6) {}
            return JSON.stringify({
              vvScale: vv ? +(vv.scale || 0).toFixed(3) : null,
              vvH: vv ? +(vv.height || 0).toFixed(1) : null,
              vvOff: vv ? [Math.round(vv.pageLeft || 0), Math.round(vv.pageTop || 0)] : null,
              dch: document.documentElement.clientHeight,
              bch: document.body.clientHeight,
              inner: [window.innerWidth, window.innerHeight],
              scroll: [Math.round(window.scrollX), Math.round(window.scrollY)],
              html: [Math.round(hr.left), Math.round(hr.top), Math.round(hr.width), Math.round(hr.height)],
              hh: cs(document.documentElement),
              bh: cs(document.body),
              row: row ? [Math.round(rr.left), Math.round(rr.top), Math.round(rr.width), Math.round(rr.height)] : null,
              chain: chain,
              pts: pts
            });
          } catch (e) { return "env:error:" + e.message; }
        })()
    """.trimIndent()

private val TAP_PRECHECK_JS = """
        (function(){
          try {
            var cx = __X__;
            var cy = __Y__;
            var conv = __CONV__;
            var el = document.elementFromPoint(cx, cy);
            if (!el) return "precheck:miss:hit=kein_Element";
            var row = null;
            try { row = el.closest ? el.closest("[data-conv-id]") : null; } catch (e1) {}
            var rowConv = row ? (row.getAttribute("data-conv-id") || "") : "";
            var target = null;
            try { target = document.querySelector('[data-conv-id="' + conv + '"]'); } catch (e2) {}
            var covered = false;
            if (target && target.getBoundingClientRect) {
              var tr = target.getBoundingClientRect();
              covered = tr.width > 0 && tr.height > 0 && cx >= tr.left && cx <= tr.right && cy >= tr.top && cy <= tr.bottom;
            }
            if (rowConv === conv || covered) return "precheck:ok:conv=" + conv;
            return "precheck:miss:hit=" + el.tagName + ",convOfHit=" + (rowConv || "keine") + ",pointInTarget=" + covered;
          } catch (e) { return "precheck:error:" + e.message; }
        })()
    """.trimIndent()

private val TAP_DIAG_JS = """
        (function(){
          try {
            var cx = __X__;
            var cy = __Y__;
            var convId = __CONV__;
            var el = document.elementFromPoint(cx, cy);
            var chain = [];
            var cur = el;
            for (var i = 0; i < 4 && cur; i++) {
              chain.push(cur.tagName + "." + ((cur.getAttribute("class") || "").toString().slice(0, 30)));
              cur = cur.parentElement;
            }
            var conv = null;
            try { conv = el && el.closest ? el.closest("[data-conv-id]") : null; } catch (e1) {}
            var box = document.querySelector('div[class*="ChatBox"]');
            var target = null;
            var tRect = null;
            try { target = document.querySelector('[data-conv-id="' + convId + '"]'); } catch (e7) {}
            if (target) {
              var tr = target.getBoundingClientRect();
              tRect = [Math.round(tr.left), Math.round(tr.top), Math.round(tr.width), Math.round(tr.height)];
            }
            return JSON.stringify({
              at: [cx, cy],
              hit: el ? el.tagName + "|" + (el.innerText || "").replace(/\s+/g, " ").trim().slice(0, 40) : "nichts",
              chain: chain,
              convOfHit: conv ? (conv.getAttribute("data-conv-id") || "") : "keine",
              rows: document.querySelectorAll('[data-conv-id]').length,
              rowOK: target ? true : false,
              rowRect: tRect,
              chatBox: box ? Math.round(box.getBoundingClientRect().width) + "x" + Math.round(box.getBoundingClientRect().height) : "keine",
              composer: document.querySelectorAll('[data-e2e="dm-new-input-editor"] [contenteditable="true"]').length,
              editorAll: document.querySelectorAll('[contenteditable="true"]').length,
              searchInputs: document.querySelectorAll('input').length
            });
          } catch (e) { return "tapdiag:error:" + e.message; }
        })()
    """.trimIndent()

private val SELECT_JS = """
        (function(){
          try {
            var names = __NAMES__;
            var uids = __UIDS__;
            var conv = __CONV__;
            var pollIndex = __POLL__;
            var dpr = window.devicePixelRatio || 1;
            function fmt(r, tag) {
              return "selected:" + tag + ":at=" + Math.round(r.left + r.width / 2) + "," + Math.round(r.top + r.height / 2)
                + ",dpr=" + (Math.round(dpr * 100) / 100) + ",inner=" + window.innerWidth
                + ",size=" + Math.round(r.width) + "x" + Math.round(r.height);
            }
            function rowRectOf(el) {
              var cur = el, depth = 0;
              while (cur && cur !== document.body && depth < 6) {
                var r = cur.getBoundingClientRect();
                if (r.width > 150 && r.height > 30) return r;
                cur = cur.parentElement; depth++;
              }
              return null;
            }
            if (conv) {
              var nodes = document.querySelectorAll('[data-conv-id]');
              for (var i = 0; i < nodes.length; i++) {
                if ((nodes[i].getAttribute('data-conv-id') || '') !== conv) continue;
                var r0 = rowRectOf(nodes[i]);
                if (r0) return fmt(r0, "conv");
              }
            }
            var lowered = (names || []).map(function(n){ return (n||"").toLowerCase(); }).filter(function(n){ return n.length > 1; });
            var byConv = document.querySelectorAll('[data-conv-id]');
            for (var j = 0; j < byConv.length; j++) {
              var t = (byConv[j].innerText || "").toLowerCase();
              var hit = false;
              for (var k = 0; k < lowered.length; k++) { if (lowered[k] && t.indexOf(lowered[k]) === 0) { hit = true; break; } }
              if (!hit) continue;
              var rj = rowRectOf(byConv[j]);
              if (rj) return fmt(rj, "convname");
            }
            var rows = document.querySelectorAll('li[class*="LiInboxItem"], div[class*="DivItemWrapper"], div[class*="InboxItem"]');
            for (var m = 0; m < rows.length; m++) {
              var raw = (rows[m].innerText || "").replace(/\s+/g, " ").trim();
              var low = raw.toLowerCase();
              var hit2 = false;
              for (var n = 0; n < lowered.length; n++) { if (lowered[n] && low.indexOf(lowered[n]) === 0) { hit2 = true; break; } }
              if (!hit2) continue;
              var rm = rowRectOf(rows[m]);
              if (rm) return fmt(rm, "rowname");
            }
            var viewport = describeViewport();
            var scrollers = describeScrollers();
            var tabs = describeTabs();
            var scrolled = scrollMore();
            var sample = [];
            var anyRow = document.querySelectorAll('[data-conv-id], li, div[class*="DivItemWrapper"], div[class*="InboxItem"]');
            for (var s = 0; s < anyRow.length && sample.length < 6; s++) {
              var st = (anyRow[s].innerText || "").replace(/\s+/g, " ").trim();
              if (st.length > 1) sample.push(st.slice(0, 34));
            }
            var listHtml = "";
            try {
              var host = document.querySelector('li[class*="LiInboxItem"], [data-conv-id], div[class*="DivItemWrapper"]');
              listHtml = host ? (host.outerHTML || "").slice(0, 260).replace(/\s+/g, " ") : "";
            } catch (e9) {}
            var login = (document.body.innerText || "").toLowerCase().indexOf("anmelden") >= 0 ? "maybe" : "no";
            return "notfound:login=" + login + ",rows=" + anyRow.length
              + ",conv=" + (conv ? conv.slice(-14) : "-") + ",sample=" + JSON.stringify(sample)
              + ",viewport=" + JSON.stringify(viewport) + ",scrolled=" + scrolled
              + ",scrollers=" + JSON.stringify(scrollers) + ",tabs=" + JSON.stringify(tabs)
              + ",html=" + listHtml;
          } catch (e) { return "error:" + e.message; }
          function describeViewport() {
            var root = document.documentElement;
            var vv = window.visualViewport;
            return {
              inner: [Math.round(window.innerWidth || 0), Math.round(window.innerHeight || 0)],
              document: [Math.round(root ? root.clientWidth : 0), Math.round(root ? root.clientHeight : 0)],
              visual: vv ? [Math.round(vv.width || 0), Math.round(vv.height || 0)] : null,
              screen: [Math.round(screen.width || 0), Math.round(screen.height || 0)]
            };
          }
          function describeScrollers() {
            var out = [];
            var all = document.querySelectorAll('*');
            for (var i = 0; i < all.length; i++) {
              try {
                var el = all[i];
                if (el.clientHeight <= 0 || el.scrollHeight <= el.clientHeight * 2) continue;
                var r = el.getBoundingClientRect();
                var cls = String(el.className || '').replace(/\s+/g, '.').slice(0, 56);
                var e2e = el.getAttribute('data-e2e') || '';
                out.push(el.tagName.toLowerCase() + '.' + cls + ':' + Math.round(r.width) + 'x' + Math.round(r.height)
                  + ':top=' + Math.round(el.scrollTop) + ':client=' + Math.round(el.clientHeight)
                  + ':scroll=' + Math.round(el.scrollHeight) + ':e2e=' + e2e.slice(0, 36));
              } catch (e2) {}
            }
            return out;
          }
          function scrollMore() {
            var all = document.querySelectorAll('*');
            var target = null;
            var targetScore = 0;
            for (var i = 0; i < all.length; i++) {
              try {
                var el = all[i];
                if (el.clientHeight <= 0 || el.scrollHeight <= el.clientHeight * 2) continue;
                var r = el.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) continue;
                var cls = String(el.className || '');
                var score = el.scrollHeight - el.clientHeight;
                if (/ScrollingContentContainer|Inbox|List/.test(cls)) score += 1000000;
                if (score > targetScore) {
                  target = el;
                  targetScore = score;
                }
              } catch (e3) {}
            }
            if (!target) return "none";
            var before = target.scrollTop;
            target.scrollTop = Math.min(target.scrollTop + Math.round(target.clientHeight * 0.9), target.scrollHeight - target.clientHeight);
            return target.scrollTop > before ? "down" : "bottom";
          }
          function describeTabs() {
            var out = [];
            var nodes = document.querySelectorAll('[role="tab"], button, [data-e2e]');
            for (var i = 0; i < nodes.length; i++) {
              try {
                var el = nodes[i];
                var text = (el.innerText || el.textContent || '').replace(/\s+/g, ' ').trim();
                var role = el.getAttribute('role') || '';
                var e2e = el.getAttribute('data-e2e') || '';
                var searchable = (role + ' ' + e2e + ' ' + text).toLowerCase();
                if (role !== 'tab' && !/(nachrichten|messages|aktivität|aktivitaet|activity|inbox|direct|\bdm\b|chat)/.test(searchable)) continue;
                var r = el.getBoundingClientRect();
                var active = el.getAttribute('aria-selected') || el.getAttribute('data-state') || '';
                if (!active && /active|selected/.test(String(el.className || '').toLowerCase())) active = 'class';
                out.push(el.tagName.toLowerCase() + ':text=' + text.slice(0, 48) + ':role=' + role
                  + ':e2e=' + e2e.slice(0, 36) + ':active=' + active
                  + ':size=' + Math.round(r.width) + 'x' + Math.round(r.height));
              } catch (e3) {}
            }
            return out;
          }
        })()
    """.trimIndent()

private val PANE_JS = """
        (function(){
          try {
            var box = document.querySelector('div[class*="ChatBox"]');
            if (!box) return "pane:nobox";
            var composer = box.querySelector('[data-e2e="dm-new-input-editor"] [contenteditable="true"]')
                        || box.querySelector('[contenteditable="true"]');
            if (!composer) return "pane:nocomposer";
            var handles = [];
            var links = box.querySelectorAll('a[href^="/@"]');
            for (var i = 0; i < links.length; i++) {
              var h = links[i].getAttribute('href') || '';
              if (h && handles.indexOf(h) < 0) handles.push(h);
            }
            if (!handles.length) return "pane:nohandle";
            var r = box.getBoundingClientRect();
            return "pane:ok:handles=" + handles.slice(0, 4).join(",") + ",size=" + Math.round(r.width) + "x" + Math.round(r.height);
          } catch (e) { return "pane:error:" + e.message; }
        })()
    """.trimIndent()

    private val VERIFYCHAT_JS = """
        (function(){
          try {
            var names = __NAMES__;
            var lowered = names.map(function(n){ return (n||"").toLowerCase(); }).filter(function(n){ return n.length > 1; });
            var heads = document.querySelectorAll('header h1, header h2, main h1, main h2, h1, h2, [role="heading"]');
            var seen = [];
            for (var i = 0; i < heads.length; i++) {
              var t = ((heads[i].innerText || "").replace(/\s+/g, " ").trim()).toLowerCase();
              if (!t) continue;
              if (seen.indexOf(t) < 0) seen.push(t.slice(0, 60));
              for (var j = 0; j < lowered.length; j++) {
                if (t.indexOf(lowered[j]) >= 0) return "chat:ok:" + lowered[j];
              }
            }
            return "chat:wrong:" + JSON.stringify(seen.slice(0, 6));
          } catch (e) { return "chat:error:" + e.message; }
        })()
    """.trimIndent()

    private val INPUT_PROBE_JS = """
        (function(){
          try {
            var href0 = location.href || "";
            if (href0.indexOf("/@") >= 0 && href0.indexOf("/messages") < 0) return "input:wrongpage:" + href0.slice(-60);
            if (findEditor()) return "input:ok";
            return "input:missing:url=" + href0.slice(-60)
              + ",rows=" + document.querySelectorAll('[data-conv-id]').length
              + ",fields=" + JSON.stringify(dumpFields());
          } catch (e) { return "input:error:" + e.message; }
          function findEditor() {
            var strong = ['[data-e2e="dm-new-input-editor"] [contenteditable="true"]',
                          '[data-e2e="message-input-area"] [contenteditable="true"]',
                          'div[contenteditable="true"][role="textbox"]',
                          '[data-e2e="dm-new-input-editor"]',
                          '[data-e2e="message-input-area"]'];
            for (var i = 0; i < strong.length; i++) {
              var els = document.querySelectorAll(strong[i]);
              for (var j = 0; j < els.length; j++) {
                var r = els[j].getBoundingClientRect();
                if (r.width > 40 && r.height > 6) return els[j];
              }
            }
            var sels = ['[contenteditable="true"]', '[role="textbox"]', 'textarea', 'input[type="text"]'];
            for (var i2 = 0; i2 < sels.length; i2++) {
              var els2 = document.querySelectorAll(sels[i2]);
              for (var j2 = 0; j2 < els2.length; j2++) {
                var r2 = els2[j2].getBoundingClientRect();
                if (r2.width > 40 && r2.height > 6) return els2[j2];
              }
            }
            return null;
          }
          function dumpFields() {
            var out = [];
            var sels = ['[contenteditable]', '[role="textbox"]', 'textarea', 'input', '[data-e2e*="input"]', 'div[placeholder]', '[role="combobox"]'];
            for (var i = 0; i < sels.length && out.length < 10; i++) {
              var els = document.querySelectorAll(sels[i]);
              for (var j = 0; j < els.length && out.length < 10; j++) {
                var r = els[j].getBoundingClientRect();
                var ph = "";
                try { ph = els[j].getAttribute("placeholder") || els[j].getAttribute("aria-label") || els[j].getAttribute("data-e2e") || ""; } catch (e5) {}
                var cls = "";
                try { cls = (els[j].className || "").toString().slice(0, 24); } catch (e6) {}
                out.push(els[j].tagName + "." + cls + ":" + Math.round(r.width) + "x" + Math.round(r.height) + ":" + ph.slice(0, 30));
              }
            }
            return out;
          }
        })()
    """.trimIndent()

    private val PREP_JS = """
        (function(){
          try {
            var ed = findEditor();
            if (!ed) return "noeditor";
            try { if (ed.scrollIntoView) ed.scrollIntoView({ block: "center" }); } catch (e0) {}
            ed.focus();
            var r = ed.getBoundingClientRect();
            var tag = "";
            try { tag = ed.tagName + "." + ((ed.getAttribute("class") || "").toString().slice(0, 28)); } catch (e1) {}
            var ce = "";
            try { ce = ed.getAttribute("contenteditable") || ""; } catch (e2) {}
            return "prepared:" + tag + ",ce=" + ce + ",size=" + Math.round(r.width) + "x" + Math.round(r.height) + ",at=" + Math.round(r.left + r.width / 2) + "," + Math.round(r.top + r.height / 2);
          } catch (e) { return "error:" + e.message; }
          function findEditor() {
            var strong = ['[data-e2e="dm-new-input-editor"] [contenteditable="true"]',
                          '[data-e2e="message-input-area"] [contenteditable="true"]',
                          'div[contenteditable="true"][role="textbox"]',
                          '[data-e2e="dm-new-input-editor"]',
                          '[data-e2e="message-input-area"]'];
            for (var i = 0; i < strong.length; i++) {
              var els = document.querySelectorAll(strong[i]);
              for (var j = 0; j < els.length; j++) {
                var r = els[j].getBoundingClientRect();
                if (r.width > 40 && r.height > 6) return els[j];
              }
            }
            var sels = ['[contenteditable="true"]', '[role="textbox"]', 'textarea', 'input[type="text"]'];
            for (var i2 = 0; i2 < sels.length; i2++) {
              var els2 = document.querySelectorAll(sels[i2]);
              for (var j2 = 0; j2 < els2.length; j2++) {
                var r2 = els2[j2].getBoundingClientRect();
                if (r2.width > 40 && r2.height > 6) return els2[j2];
              }
            }
            return null;
          }
        })()
    """.trimIndent()

    private val EDITOR_TEXT_JS = """
        (function(){
          try {
            var text = __TEXT__;
            var ed = findEditor();
            if (!ed) return "text:noeditor";
            var v = ed.value !== undefined && ed.value !== null ? String(ed.value) : (ed.innerText || "");
            if (v.indexOf(text.slice(0, Math.min(16, text.length))) >= 0) return "text:1:" + v.replace(/\s+/g, " ").trim().slice(0, 40);
            return "text:0:" + v.replace(/\s+/g, " ").trim().slice(0, 40);
          } catch (e) { return "text:error:" + e.message; }
          function findEditor() {
            var strong = ['[data-e2e="dm-new-input-editor"] [contenteditable="true"]',
                          '[data-e2e="message-input-area"] [contenteditable="true"]',
                          'div[contenteditable="true"][role="textbox"]',
                          '[data-e2e="dm-new-input-editor"]',
                          '[data-e2e="message-input-area"]'];
            for (var i = 0; i < strong.length; i++) {
              var els = document.querySelectorAll(strong[i]);
              for (var j = 0; j < els.length; j++) {
                var r = els[j].getBoundingClientRect();
                if (r.width > 40 && r.height > 6) return els[j];
              }
            }
            return null;
          }
        })()
    """.trimIndent()

    private val SEND_JS = """
        (function(){
          try {
            var ed = findEditor();
            if (!ed) return "noeditor";
            var scope = document.querySelector('[data-e2e="message-input-area"]');
            var box = scope && scope.parentElement ? scope.parentElement : document.body;
            var btns = box.querySelectorAll('button, div[role="button"]');
            for (var i = 0; i < btns.length; i++) {
              var b = btns[i];
              var label = ((b.getAttribute("aria-label") || "") + " " + (b.innerText || "") + " " + (b.getAttribute("data-e2e") || "")).toLowerCase();
              var isSend = label.indexOf("send") >= 0 || label.indexOf("senden") >= 0 || label.indexOf("absenden") >= 0
                        || label.indexOf("enviar") >= 0 || label.indexOf("发送") >= 0 || label.indexOf("posten") >= 0;
              if (!isSend) continue;
              if (b.getAttribute("aria-disabled") === "true" || b.disabled === true) continue;
              var r = b.getBoundingClientRect();
              if (r.width <= 0 || r.height <= 0) continue;
              b.click();
              return "clicked:" + (b.getAttribute("aria-label") || b.getAttribute("data-e2e") || b.tagName).slice(0, 30);
            }
            return "nobutton";
          } catch (e) { return "error:" + e.message; }
          function findEditor() {
            var strong = ['[data-e2e="dm-new-input-editor"] [contenteditable="true"]',
                          '[data-e2e="message-input-area"] [contenteditable="true"]',
                          'div[contenteditable="true"][role="textbox"]'];
            for (var i = 0; i < strong.length; i++) {
              var els = document.querySelectorAll(strong[i]);
              for (var j = 0; j < els.length; j++) {
                var r = els[j].getBoundingClientRect();
                if (r.width > 40 && r.height > 6) return els[j];
              }
            }
            return null;
          }
        })()
    """.trimIndent()

    private val VERIFY_JS = """
        (function(){
          try {
            var text = __TEXT__;
            var ed = findEditor();
            if (ed) {
              var v = ed.value !== undefined && ed.value !== null ? String(ed.value) : (ed.innerText || "");
              if (v.trim().length === 0) return "verify:ok";
              if (v.indexOf(text.slice(0, Math.min(16, text.length))) >= 0) return "verify:notsent";
            }
            var body = document.body.innerText || "";
            if (text.length > 0 && body.indexOf(text.slice(0, Math.min(24, text.length))) >= 0) return "verify:ok";
            return "verify:wait";
          } catch (e) { return "verify:error:" + e.message; }
          function findEditor() {
            var strong = ['[data-e2e="dm-new-input-editor"] [contenteditable="true"]',
                          '[data-e2e="message-input-area"] [contenteditable="true"]',
                          'div[contenteditable="true"][role="textbox"]',
                          '[data-e2e="dm-new-input-editor"]',
                          '[data-e2e="message-input-area"]'];
            for (var i = 0; i < strong.length; i++) {
              var els = document.querySelectorAll(strong[i]);
              for (var j = 0; j < els.length; j++) {
                var r = els[j].getBoundingClientRect();
                if (r.width > 40 && r.height > 6) return els[j];
              }
            }
            return null;
          }
        })()
    """.trimIndent()
}
