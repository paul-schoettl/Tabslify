package com.tabslify.tabs.aitab

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tabslify.R
import com.tabslify.core.objects.Config
import com.tabslify.core.objects.prvt
import com.tabslify.privatetabslifyapp.isOnline
import com.tabslify.quiethoursnotificationhelper.AiProvider
import com.tabslify.quiethoursnotificationhelper.AiTarget
import com.tabslify.quiethoursnotificationhelper.aiSystemPrompt
import com.tabslify.quiethoursnotificationhelper.askServer
import com.tabslify.quiethoursnotificationhelper.sendAiRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.util.Calendar
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds

const val DAILY_LIMIT = 30
private const val USAGE_RESET_MS = 6 * 60 * 60 * 1000L
private const val USAGE_PREFS = "ai_usage"
private const val USAGE_KEY_COUNT = "usage_count"
private const val USAGE_KEY_RESET_AT = "usage_reset_at"
private const val TOKEN_THROTTLE_MS = 33L
private const val MAX_CONTEXT_MESSAGES = 40
private const val MAX_CONTEXT_CHARS = 32_000
private const val MAX_SYSTEM_PROMPT_CHARS = 2_000

data class AiUsage(
    val count: Int,
    val resetAt: Long,
    val remainingHours: Int,
    val remainingMinutes: Int
)

fun readAiUsage(ctx: Context): AiUsage {
    val prefs = ctx.getSharedPreferences(USAGE_PREFS, MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val savedResetAt = prefs.getLong(USAGE_KEY_RESET_AT, 0L)
    val shouldReset = savedResetAt == 0L || now - savedResetAt >= USAGE_RESET_MS
    val resetAt = if (shouldReset) now else savedResetAt
    val count = if (shouldReset) 0 else prefs.getInt(USAGE_KEY_COUNT, 0)
    val remaining = resetAt + USAGE_RESET_MS - now
    return AiUsage(
        count = count,
        resetAt = resetAt,
        remainingHours = if (remaining <= 0) 0 else (remaining / 3_600_000).toInt(),
        remainingMinutes = if (remaining <= 0) 0 else ((remaining % 3_600_000) / 60_000).toInt()
    )
}

fun writeAiUsage(ctx: Context, count: Int, resetAt: Long) {
    ctx.getSharedPreferences(USAGE_PREFS, MODE_PRIVATE).edit {
        putInt(USAGE_KEY_COUNT, count)
        putLong(USAGE_KEY_RESET_AT, resetAt)
    }
}

fun aiUsageText(ctx: Context, usage: AiUsage): String {
    if (usage.remainingHours <= 0 && usage.remainingMinutes <= 0) {
        return ctx.getString(R.string.wird_gleich_zuruckgesetzt)
    }
    val resetTime = Calendar.getInstance().apply { timeInMillis = usage.resetAt + USAGE_RESET_MS }
    val hh = resetTime.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
    val mm = resetTime.get(Calendar.MINUTE).toString().padStart(2, '0')
    return ctx.getString(R.string.reset_um_h_m, hh, mm, usage.remainingHours, usage.remainingMinutes)
}

fun reserveAiUsage(ctx: Context, weight: Int): Boolean {
    val usage = readAiUsage(ctx)
    if (usage.count + weight > DAILY_LIMIT) return false
    writeAiUsage(ctx, usage.count + weight, usage.resetAt)
    return true
}

@Serializable
data class ChatMessage(
    val text: String,
    val ts: Long,
    val own: Boolean,
    val mode: String? = null,
    val id: String = UUID.randomUUID().toString()
)

data class ChatSession(
    val id: String,
    val title: String,
    val systemPrompt: String,
    val createdAt: Long,
    val updatedAt: Long,
    val mode: String,
    val model: String,
    val messages: SnapshotStateList<ChatMessage>
)

class AITabViewModel(application: Application) : AndroidViewModel(application) {
    var currentMode by mutableStateOf("Nvidia")
    var currentMsg by mutableStateOf("")
    var selectedImageUri by mutableStateOf<Uri?>(null)
    var selectedAudioUri by mutableStateOf<Uri?>(null)
    var showAiModels by mutableStateOf(false)
    var editIndex: Int? by mutableStateOf(null)
    var isEditMode by mutableStateOf(false)
    var currentEditMsg by mutableStateOf("")
    var selectedMsg: Int? by mutableStateOf(null)
    var lastSelectedMsg: Int? by mutableStateOf(null)
    var showLimitReached by mutableStateOf(false)
    var showChatList by mutableStateOf(false)
    var streamSeq by mutableLongStateOf(0L)
    var activeChatId by mutableStateOf<String?>(null)
        private set

    val chats = mutableStateListOf<ChatSession>()

    private val loadingChats = mutableStateMapOf<String, Boolean>()
    private val sendJobs = mutableMapOf<String, Job>()
    private val persistMutex = Mutex()
    private var persistVersion = 0L
    private var persistedVersion = 0L
    private var activePersistVersion = 0L
    private var activePersistedVersion = 0L
    private var chatsLoaded = false

    val activeChat: ChatSession?
        get() = chats.firstOrNull { it.id == activeChatId }

    val history: List<ChatMessage>
        get() = activeChat?.messages ?: emptyList()

    val isLoading: Boolean
        get() = loadingChats[activeChatId] == true

    val availableModels
        get() = when (currentMode) {
            "Nvidia" -> nvidiaModels
            "OpenRouter" -> openrouterModels
            "Server" -> serverModels
            "Gemini" -> geminiModels
            else -> emptyList()
        }

    var selectedModel by mutableStateOf(availableModels[0])

    var todayUsage by mutableIntStateOf(0)
    var usageResetAt by mutableLongStateOf(0L)

    fun loadHistory(createIfEmpty: Boolean = true) {
        if (chatsLoaded) return
        chatsLoaded = true
        val ctx = getApplication<Application>()
        checkUsageResetIfNeeded(ctx)
        val loaded = ChatStore.load(ctx)
        chats.clear()
        loaded.chats.forEach { stored ->
            chats.add(
                ChatSession(
                    id = stored.id,
                    title = stored.title,
                    systemPrompt = stored.systemPrompt,
                    createdAt = stored.createdAt,
                    updatedAt = stored.updatedAt,
                    mode = stored.mode,
                    model = stored.model,
                    messages = mutableStateListOf<ChatMessage>().apply { addAll(stored.messages) }
                )
            )
        }
        val chosen = loaded.activeChatId?.takeIf { id -> chats.any { it.id == id } }
            ?: chats.maxByOrNull { it.updatedAt }?.id
        if (chosen == null) {
            if (!createIfEmpty) return
            val created = createSession("Nvidia", nvidiaModels.first().realname)
            chats.add(created)
            activeChatId = created.id
            resetUiState()
            currentMode = "Nvidia"
            selectedModel = nvidiaModels.first()
            persist()
            persistActive()
        } else {
            activeChatId = chosen
            applyActiveChatState()
            persistActive()
        }
    }

    private fun updateTodayUsage(count: Int) {
        todayUsage = count
        writeAiUsage(getApplication(), count, usageResetAt)
    }

    fun checkUsageResetIfNeeded(ctx: Context) {
        val usage = readAiUsage(ctx)
        usageResetAt = usage.resetAt
        todayUsage = usage.count
        writeAiUsage(ctx, usage.count, usage.resetAt)
    }

    fun getUsageProgress(): Float = (todayUsage.toFloat() / DAILY_LIMIT).coerceIn(0f, 1f)

    fun getUsageResetText(): String = aiUsageText(getApplication(), readAiUsage(getApplication()))

    fun setMode(mode: String) {
        if (currentMode == mode) return
        currentMode = mode
        selectedModel = availableModels[0]
        val chat = activeChat ?: return
        updateChatMeta(chat.id) { it.copy(mode = mode, model = selectedModel.realname) }
        persist()
    }

    fun selectModel(model: Model) {
        selectedModel = model
        if (!model.vision) selectedImageUri = null
        if (!model.audio) selectedAudioUri = null
        val chat = activeChat ?: return
        updateChatMeta(chat.id) { it.copy(model = model.realname) }
        persist()
    }

    fun newChat() {
        loadHistory()
        val current = activeChat
        if (current != null &&
            current.messages.isEmpty() &&
            current.title.isBlank() &&
            current.systemPrompt.isBlank()
        ) {
            showChatList = false
            persistActive()
            return
        }
        val chat = createSession(currentMode, selectedModel.realname)
        chats.add(chat)
        activeChatId = chat.id
        resetUiState()
        showChatList = false
        persist()
        persistActive()
    }

    fun openChat(id: String) {
        loadHistory()
        chats.firstOrNull { it.id == id } ?: run {
            showChatList = false
            return
        }
        if (activeChatId != id) {
            activeChatId = id
            resetUiState()
        }
        showChatList = false
        applyActiveChatState()
        persistActive()
    }

    fun startNewChatWithPrompt(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        loadHistory(createIfEmpty = false)
        val model = geminiModels.firstOrNull { it.realname == Config.DEF_GEMINI }
            ?: geminiModels.first()
        currentMode = "Gemini"
        selectedModel = model
        val chat = createSession("Gemini", model.realname, ChatTitles.from(prompt))
        chats.add(chat)
        activeChatId = chat.id
        resetUiState()
        showChatList = false
        persist()
        persistActive()
        currentMsg = prompt
        sendMessage()
    }

    fun renameChat(id: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        updateChatMeta(id) { it.copy(title = clean) }
        persist()
    }

    fun setChatSystemPrompt(id: String, text: String) {
        val clean = text.trim().take(MAX_SYSTEM_PROMPT_CHARS)
        updateChatMeta(id) { it.copy(systemPrompt = clean) }
        persist()
    }

    fun deleteChat(id: String) {
        sendJobs.remove(id)?.cancel()
        loadingChats.remove(id)
        val index = chats.indexOfFirst { it.id == id }
        if (index < 0) return
        chats.removeAt(index)
        if (activeChatId == id) {
            val next = chats.maxByOrNull { it.updatedAt }
            if (next == null) {
                val created = createSession(currentMode, selectedModel.realname)
                chats.add(created)
                activeChatId = created.id
            } else {
                activeChatId = next.id
            }
            resetUiState()
            applyActiveChatState()
        }
        persist()
        persistActive()
    }

    fun deleteMessage(chatId: String, index: Int) {
        if (loadingChats[chatId] == true) return
        val chat = chats.firstOrNull { it.id == chatId } ?: return
        if (index !in chat.messages.indices) return
        chat.messages.removeAt(index)
        if (activeChatId == chatId) {
            selectedMsg = null
            val edit = editIndex
            if (edit != null) {
                if (edit == index) {
                    editIndex = null
                    isEditMode = false
                    currentEditMsg = ""
                } else if (edit > index) {
                    editIndex = edit - 1
                }
            }
        }
        touchChat(chatId)
        persist()
    }

    fun regenerateLast() {
        loadHistory()
        val chat = activeChat ?: return
        if (loadingChats[chat.id] == true) return
        val last = chat.messages.lastOrNull() ?: return
        if (last.own) return
        val userMessage = chat.messages.lastOrNull { it.own } ?: return
        dispatchSend(
            chat = chat,
            rawText = userMessage.text,
            addUserMessage = false,
            editIndexToRemove = null,
            replaceLastAssistant = true
        )
    }

    fun sendMessage() {
        loadHistory()
        val chat = activeChat ?: return
        val wasEditing = isEditMode
        val userText = if (wasEditing) currentEditMsg.trim() else currentMsg.trim()
        val removeFrom = if (wasEditing) editIndex else null
        if (userText.isEmpty() && selectedImageUri == null) return
        val started = dispatchSend(
            chat = chat,
            rawText = userText,
            addUserMessage = true,
            editIndexToRemove = removeFrom,
            replaceLastAssistant = false
        )
        if (started) {
            isEditMode = false
            editIndex = null
            currentEditMsg = ""
        }
    }

    private fun dispatchSend(
        chat: ChatSession,
        rawText: String,
        addUserMessage: Boolean,
        editIndexToRemove: Int?,
        replaceLastAssistant: Boolean
    ): Boolean {
        if (loadingChats[chat.id] == true) return false
        val ctx = getApplication<Application>()
        val modeAtSend = currentMode
        val modelAtSend = selectedModel
        val isServer = modeAtSend == "Server"
        val isPrivate = prvt()
        if (!isServer && !isPrivate) {
            checkUsageResetIfNeeded(ctx)
            if (todayUsage + modelAtSend.weight > DAILY_LIMIT) {
                showLimitReached = true
                return false
            }
        }
        val userText = rawText.ifEmpty { ctx.getString(R.string.beschreibe_das_bild) }
        if (editIndexToRemove != null) {
            val toRemove = chat.messages.drop(editIndexToRemove)
            chat.messages.removeAll(toRemove)
        }
        if (replaceLastAssistant) {
            val userIndex = chat.messages.indexOfLast { it.own }
            if (userIndex < 0) return false
            while (chat.messages.size > userIndex + 1) {
                chat.messages.removeAt(chat.messages.lastIndex)
            }
        }
        val priorSource = if (replaceLastAssistant) {
            val userIndex = chat.messages.indexOfLast { it.own }
            if (userIndex >= 0) chat.messages.take(userIndex) else chat.messages.toList()
        } else {
            chat.messages.toList()
        }
        val prior = contextMessages(priorSource)
        val systemPrompt = buildSystemPrompt(chat)
        val customInstruction = chat.systemPrompt.trim()
        val audioAtSend = if (addUserMessage) selectedAudioUri else null
        val imageAtSend = if (addUserMessage) selectedImageUri else null
        if (addUserMessage) {
            chat.messages.add(
                ChatMessage(
                    text = userText,
                    ts = System.currentTimeMillis(),
                    own = true
                )
            )
            autoTitleAndTouch(chat, userText)
            currentMsg = ""
        } else {
            touchChat(chat.id)
        }
        persist()
        if (!isServer && !isPrivate) updateTodayUsage(todayUsage + modelAtSend.weight)
        loadingChats[chat.id] = true
        val placeholder = ChatMessage(
            text = "",
            ts = System.currentTimeMillis(),
            own = false,
            mode = modeAtSend
        )
        chat.messages.add(placeholder)
        val placeholderId = placeholder.id
        val buffer = StringBuilder()
        var lastFlushMs = 0L

        fun updatePlaceholder(text: String) {
            val index = chat.messages.indexOfFirst { it.id == placeholderId }
            if (index >= 0) chat.messages[index] = chat.messages[index].copy(text = text)
        }

        val onToken: (String) -> Unit = { delta ->
            buffer.append(delta)
            val now = System.currentTimeMillis()
            if (now - lastFlushMs >= TOKEN_THROTTLE_MS) {
                lastFlushMs = now
                updatePlaceholder(buffer.toString())
                if (activeChatId == chat.id) streamSeq = System.nanoTime()
            }
        }

        val job = viewModelScope.launch {
            try {
                val effectivePic = if (modelAtSend.vision) {
                    withContext(Dispatchers.IO) { imageAtSend?.let { encodeImage(ctx, it) } }
                } else null
                val response = withContext(Dispatchers.IO) {
                    send(
                        ctx = ctx,
                        mode = modeAtSend,
                        model = modelAtSend,
                        priorMessages = prior,
                        userMessage = userText,
                        pic = effectivePic,
                        audioUri = audioAtSend,
                        systemPrompt = systemPrompt,
                        customInstruction = customInstruction,
                        onToken = onToken
                    )
                }
                val answer = response.ifBlank { ctx.getString(R.string.fehler) }
                updatePlaceholder(answer)
                if (activeChatId == chat.id) {
                    if (selectedImageUri == imageAtSend) selectedImageUri = null
                    if (selectedAudioUri == audioAtSend) selectedAudioUri = null
                }
                val title = chats.firstOrNull { it.id == chat.id }?.title.orEmpty()
                sendAITabBackgroundNotification(
                    ctx,
                    title = title.ifBlank { ctx.getString(R.string.ai_tab_antwort) },
                    message = answer,
                    chatId = chat.id
                )
            } catch (e: CancellationException) {
                val index = chat.messages.indexOfFirst { it.id == placeholderId }
                if (index >= 0 && chat.messages[index].text.isBlank()) {
                    chat.messages[index] = chat.messages[index].copy(
                        text = ctx.getString(R.string.fehler)
                    )
                }
                throw e
            } catch (e: Exception) {
                val index = chat.messages.indexOfFirst { it.id == placeholderId }
                if (index >= 0) {
                    chat.messages[index] = chat.messages[index].copy(
                        text = ctx.getString(R.string.fehler_msg, e.message)
                    )
                }
            } finally {
                loadingChats.remove(chat.id)
                sendJobs.remove(chat.id)
                touchChat(chat.id)
                persist()
            }
        }
        sendJobs[chat.id] = job
        return true
    }

    private fun contextMessages(source: List<ChatMessage>): List<ChatMessage> {
        val result = ArrayDeque<ChatMessage>()
        var chars = 0
        for (message in source.asReversed()) {
            if (message.text.isBlank()) continue
            if (result.size >= MAX_CONTEXT_MESSAGES) break
            val remaining = MAX_CONTEXT_CHARS - chars
            if (remaining <= 0) break
            val text = if (message.text.length > remaining) {
                if (result.isEmpty()) message.text.take(remaining) else break
            } else {
                message.text
            }
            result.addFirst(message.copy(text = text))
            chars += text.length
        }
        return result.toList()
    }

    private fun buildSystemPrompt(chat: ChatSession): String {
        val base = aiSystemPrompt(AiTarget.AITab)
        val custom = chat.systemPrompt.trim()
        return if (custom.isEmpty()) {
            base
        } else {
            "$base\n\nZusätzliche Anweisung für diesen Chat:\n$custom"
        }
    }

    private suspend fun send(
        ctx: Context,
        mode: String,
        model: Model,
        priorMessages: List<ChatMessage>,
        userMessage: String,
        pic: String?,
        audioUri: Uri?,
        systemPrompt: String,
        customInstruction: String,
        onToken: (String) -> Unit
    ): String {
        if (!isOnline(ctx)) return ctx.getString(R.string.kein_netzwerk)
        return when (mode) {
            "Nvidia" -> sendAiRequest(
                ctx,
                userMessage,
                priorMessages,
                pic,
                target = AiTarget.AITab,
                model = model.realname,
                provider = AiProvider.NVIDIA,
                onToken = onToken,
                systemPrompt = systemPrompt
            ) ?: ctx.getString(R.string.fehler)

            "Server" -> {
                val question = if (customInstruction.isBlank()) {
                    userMessage
                } else {
                    "$customInstruction\n\n$userMessage"
                }
                askServer(priorMessages, question, model.realname, pic)
            }

            "OpenRouter" -> sendAiRequest(
                ctx,
                userMessage,
                priorMessages,
                pic,
                target = AiTarget.AITab,
                model = model.realname,
                provider = AiProvider.OPENROUTER,
                onToken = onToken,
                systemPrompt = systemPrompt
            ) ?: ctx.getString(R.string.fehler)

            "Gemini" -> sendAiRequest(
                context = ctx,
                userMessage = userMessage,
                history = priorMessages,
                pic = pic,
                audioUri = audioUri,
                target = AiTarget.AITab,
                model = model.realname,
                provider = AiProvider.GEMINI,
                onToken = onToken,
                systemPrompt = systemPrompt
            ) ?: ctx.getString(R.string.fehler)

            else -> ctx.getString(R.string.wahle_einen_modus)
        }
    }

    private fun createSession(mode: String, model: String, title: String = ""): ChatSession {
        val now = System.currentTimeMillis()
        return ChatSession(
            id = UUID.randomUUID().toString(),
            title = title,
            systemPrompt = "",
            createdAt = now,
            updatedAt = now,
            mode = mode,
            model = model,
            messages = mutableStateListOf()
        )
    }

    private fun applyActiveChatState() {
        val chat = activeChat ?: return
        val modes = listOfNotNull(
            "Nvidia",
            "Gemini",
            "OpenRouter",
            if (prvt()) "Server" else null
        )
        val mode = chat.mode.takeIf { it in modes } ?: "Nvidia"
        currentMode = mode
        val models = availableModels
        val model = models.firstOrNull { it.realname == chat.model } ?: models.firstOrNull()
        if (model != null) selectedModel = model
        if (mode != chat.mode || (model != null && model.realname != chat.model)) {
            updateChatMeta(chat.id) {
                it.copy(mode = mode, model = model?.realname.orEmpty())
            }
            persist()
        }
    }

    private fun updateChatMeta(id: String, transform: (ChatSession) -> ChatSession) {
        val index = chats.indexOfFirst { it.id == id }
        if (index >= 0) chats[index] = transform(chats[index])
    }

    private fun touchChat(id: String) {
        val index = chats.indexOfFirst { it.id == id }
        if (index < 0) return
        chats[index] = chats[index].copy(updatedAt = System.currentTimeMillis())
    }

    private fun autoTitleAndTouch(chat: ChatSession, userText: String) {
        val index = chats.indexOfFirst { it.id == chat.id }
        if (index < 0) return
        val current = chats[index]
        val title = current.title.ifBlank { ChatTitles.from(userText) }
        chats[index] = current.copy(title = title, updatedAt = System.currentTimeMillis())
    }

    private fun resetUiState() {
        currentMsg = ""
        selectedImageUri = null
        selectedAudioUri = null
        showAiModels = false
        editIndex = null
        isEditMode = false
        currentEditMsg = ""
        selectedMsg = null
        lastSelectedMsg = null
    }

    private fun persist() {
        enqueuePersist(full = true)
    }

    private fun persistActive() {
        enqueuePersist(full = false)
    }

    private fun enqueuePersist(full: Boolean) {
        val ctx = getApplication<Application>()
        if (full) {
            val version = ++persistVersion
            val snapshot = chats.map {
                StoredChat(
                    id = it.id,
                    title = it.title,
                    systemPrompt = it.systemPrompt,
                    createdAt = it.createdAt,
                    updatedAt = it.updatedAt,
                    mode = it.mode,
                    model = it.model,
                    messages = it.messages.toList()
                )
            }
            viewModelScope.launch(Dispatchers.IO) {
                persistMutex.withLock {
                    if (version < persistedVersion) return@withLock
                    ChatStore.save(ctx, snapshot)
                    persistedVersion = version
                }
            }
        } else {
            val version = ++activePersistVersion
            val active = activeChatId
            viewModelScope.launch(Dispatchers.IO) {
                persistMutex.withLock {
                    if (version < activePersistedVersion) return@withLock
                    ChatStore.saveActive(ctx, active)
                    activePersistedVersion = version
                }
            }
        }
    }

    private fun encodeImage(ctx: Context, uri: Uri): String? = try {
        val (outWidth, outHeight) = ctx.contentResolver.openInputStream(uri)?.use { input ->
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, bounds)
            bounds.outWidth to bounds.outHeight
        } ?: return null
        if (outWidth <= 0 || outHeight <= 0) return null

        val targetDimension = 1280
        val maxDimension = maxOf(outWidth, outHeight)
        var sampleSize = 1
        while (maxDimension / (sampleSize * 2) >= targetDimension) sampleSize *= 2

        ctx.contentResolver.openInputStream(uri)?.use { input ->
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeStream(input, null, opts) ?: return null
            try {
                val output = ByteArrayOutputStream()
                android.util.Base64OutputStream(output, Base64.NO_WRAP).use { base64Out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 90, base64Out)
                }
                output.toString("UTF-8")
            } finally {
                bmp.recycle()
            }
        }
    } catch (_: Exception) {
        null
    }

    suspend fun animateAlpha(alpha: Animatable<Float, AnimationVector1D>) {
        delay(100.milliseconds)
        alpha.animateTo(
            1f,
            animationSpec = tween(durationMillis = 150, easing = FastOutSlowInEasing)
        )
    }
}
