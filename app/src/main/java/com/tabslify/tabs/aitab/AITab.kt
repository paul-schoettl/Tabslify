package com.tabslify.tabs.aitab

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults.buttonColors
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Color.Companion.Black
import androidx.compose.ui.graphics.Color.Companion.Transparent
import androidx.compose.ui.graphics.Color.Companion.White
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.tabslify.R
import com.tabslify.core.TabNavigationViewModel
import com.tabslify.core.objects.prvt
import com.tabslify.core.ui.Accent
import com.tabslify.core.ui.AccentDim
import com.tabslify.core.ui.AccentViolet
import com.tabslify.core.ui.AlertDialogTabslify
import com.tabslify.core.ui.BgCard
import com.tabslify.core.ui.BgSurface
import com.tabslify.core.ui.NeonBox
import com.tabslify.core.ui.PloppingButton
import com.tabslify.core.ui.SharedViewModel
import com.tabslify.core.ui.TextPrimary
import com.tabslify.core.ui.TextSecondary
import com.tabslify.core.ui.TextTertiary
import com.tabslify.core.ui.calloutAwareMarkdownComponents
import com.tabslify.core.ui.normalizeCallouts
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt
import com.tabslify.core.ui.BgDeep
import com.tabslify.core.ui.ErrorRed

@Composable
fun AITabContent(
    vm: AITabViewModel = viewModel(),
    svm: SharedViewModel = viewModel(),
    navViewModel: TabNavigationViewModel = viewModel(),
    paddingValues: PaddingValues = PaddingValues(0.dp)
) {
    val listState = rememberLazyListState()
    val chatListState = rememberLazyListState()
    val alpha = remember { Animatable(0f) }
    val context = LocalContext.current
    var chatToRename by remember { mutableStateOf<ChatSession?>(null) }
    var chatToEditInstruction by remember { mutableStateOf<ChatSession?>(null) }
    var chatToDelete by remember { mutableStateOf<ChatSession?>(null) }
    var renameInput by remember { mutableStateOf("") }
    var instructionInput by remember { mutableStateOf("") }
    var messageToDelete by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val markdownComponents = calloutAwareMarkdownComponents()
    val markdownColors = markdownColor(text = TextPrimary)
    val markdownTypography = markdownTypography(
        h1 = TextStyle(
            fontSize = 20.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textDecoration = TextDecoration.Underline
        ),
        h2 = TextStyle(
            fontSize = 18.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary,
            textDecoration = TextDecoration.Underline
        ),
        h3 = TextStyle(
            fontSize = 17.sp,
            lineHeight = 19.sp,
            fontWeight = FontWeight.Medium,
            color = TextPrimary,
            textDecoration = TextDecoration.Underline
        ),
        text = TextStyle(
            fontSize = 15.sp,
            lineHeight = 15.sp,
            color = TextPrimary
        ),
        paragraph = TextStyle(
            fontSize = 15.sp,
            lineHeight = 15.sp,
            color = TextPrimary
        ),
        list = TextStyle(
            fontSize = 15.sp,
            lineHeight = 15.sp,
            color = TextPrimary
        ),
        ordered = TextStyle(
            fontSize = 15.sp,
            lineHeight = 15.sp,
            color = TextPrimary
        )
    )

    val overlayAlpha = animateFloatAsState(
        targetValue = if (vm.selectedMsg != null) 1f else 0f,
        animationSpec = tween(400),
        label = "overlay"
    )

    LaunchedEffect(vm.history.size, vm.activeChatId) {
        val sel = vm.selectedMsg
        if (sel != null && sel >= vm.history.size) {
            vm.selectedMsg = null
            svm.fireEvent(false)
        }
    }

    LaunchedEffect(vm.showChatList) {
        navViewModel.updateBackState(vm.showChatList) {
            vm.showChatList = false
        }
        if (vm.showChatList) {
            vm.selectedMsg = null
            vm.isEditMode = false
            svm.fireEvent(false)
        }
    }

    DisposableEffect(Unit) {
        onDispose { navViewModel.reset() }
    }

    LaunchedEffect(chatToRename?.id) {
        renameInput = chatToRename?.title.orEmpty()
    }

    LaunchedEffect(chatToEditInstruction?.id) {
        instructionInput = chatToEditInstruction?.systemPrompt.orEmpty()
    }

    val density = LocalDensity.current

    BackHandler(vm.showChatList) {
        vm.showChatList = false
    }

    BackHandler(vm.isEditMode) {
        vm.isEditMode = false
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        vm.selectedImageUri = uri
    }

    val audioPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        vm.selectedAudioUri = uri
        if (uri != null) vm.selectedImageUri = null
    }

    LaunchedEffect(Unit) {
        vm.loadHistory()
        vm.animateAlpha(alpha)
    }

    if (vm.showLimitReached) {
        AlertDialogTabslify(
            onDismiss = { vm.showLimitReached = false },
            onConfirm = { vm.showLimitReached = false },
            title = stringResource(R.string.tageslimit_erreicht),
            text = stringResource(R.string.du_hast_dein_tagliches_limit),
            confirmText = "OK",
            oneButton = true,
        )
    }

    chatToRename?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToRename = null },
            containerColor = BgSurface,
            title = {
                Text(
                    stringResource(R.string.ai_chat_rename),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = renameInput,
                    onValueChange = { renameInput = it },
                    label = { Text(stringResource(R.string.ai_chat_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.renameChat(chat.id, renameInput)
                        chatToRename = null
                    },
                    enabled = renameInput.isNotBlank()
                ) {
                    Text(stringResource(R.string.speichern), color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToRename = null }) {
                    Text(stringResource(R.string.abbrechen), color = TextSecondary)
                }
            }
        )
    }

    chatToEditInstruction?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatToEditInstruction = null },
            containerColor = BgSurface,
            title = {
                Text(
                    stringResource(R.string.ai_chat_instruction_title),
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.ai_chat_instruction_hint),
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = instructionInput,
                        onValueChange = { instructionInput = it.take(2000) },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setChatSystemPrompt(chat.id, instructionInput)
                    chatToEditInstruction = null
                }) {
                    Text(stringResource(R.string.speichern), color = TextPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = { chatToEditInstruction = null }) {
                    Text(stringResource(R.string.abbrechen), color = TextSecondary)
                }
            }
        )
    }

    chatToDelete?.let { chat ->
        AlertDialogTabslify(
            onDismiss = { chatToDelete = null },
            onConfirm = {
                vm.deleteChat(chat.id)
                chatToDelete = null
            },
            title = stringResource(R.string.ai_chat_delete_title),
            text = stringResource(R.string.ai_chat_delete_text),
            confirmText = stringResource(R.string.loschen)
        )
    }

    messageToDelete?.let { (chatId, index) ->
        AlertDialogTabslify(
            onDismiss = { messageToDelete = null },
            onConfirm = {
                vm.deleteMessage(chatId, index)
                messageToDelete = null
            },
            title = stringResource(R.string.ai_message_delete_title),
            text = stringResource(R.string.ai_message_delete_text),
            confirmText = stringResource(R.string.loschen)
        )
    }

    val cs = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val aitabMessageLabel = stringResource(R.string.aitab_message)

    val hazeState = remember { HazeState() }
    var headerHeightDp by remember { mutableStateOf(0.dp) }
    var inputBarHeightDp by remember { mutableStateOf(0.dp) }

    val msgCoords = remember { HashMap<String, LayoutCoordinates>() }
    var bubbleTopRoot by remember { mutableFloatStateOf(0f) }
    var bubbleBottomRoot by remember { mutableFloatStateOf(0f) }
    var overlayTopRoot by remember { mutableFloatStateOf(0f) }
    var overlayHeightPx by remember { mutableIntStateOf(0) }

    val selectedMessage = vm.selectedMsg?.let { vm.history.getOrNull(it) }
    val canModifyMessages = !vm.isLoading && !vm.isEditMode
    val menuActionCount = 1 +
            (if (selectedMessage?.own == true && canModifyMessages) 1 else 0) +
            (if (selectedMessage?.own == false && vm.selectedMsg == vm.history.lastIndex && canModifyMessages) 1 else 0) +
            (if (selectedMessage != null && canModifyMessages) 1 else 0)
    val menuHeightDp = when {
        menuActionCount >= 3 -> 210.dp
        menuActionCount == 2 -> 150.dp
        else -> 90.dp
    }
    val menuHeightPx = with(density) { menuHeightDp.roundToPx() }
    val gapPx = with(density) { 6.dp.roundToPx() }
    val minYPx = with(density) { headerHeightDp.roundToPx() }
    val bottomLimitPx = overlayHeightPx - with(density) { inputBarHeightDp.roundToPx() }
    val menuGoesUp =
        bubbleBottomRoot - overlayTopRoot + gapPx + menuHeightPx > bottomLimitPx

    fun selectMessage(index: Int) {
        val m = vm.history.getOrNull(index) ?: return
        msgCoords[m.id]?.takeIf { it.isAttached }?.let {
            val b = it.boundsInRoot()
            bubbleTopRoot = b.top
            bubbleBottomRoot = b.bottom
        }
        vm.selectedMsg = index
        svm.fireEvent(true)
        vm.lastSelectedMsg = vm.selectedMsg
    }

    Box(
        Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .alpha(alpha.value)
    ) {
        if (vm.showChatList) {
            AiChatListScreen(
                chats = vm.chats.sortedByDescending { it.updatedAt },
                activeChatId = vm.activeChatId,
                topPadding = paddingValues.calculateTopPadding(),
                listState = chatListState,
                onBack = { vm.showChatList = false },
                onNewChat = { vm.newChat() },
                onOpenChat = { vm.openChat(it) },
                onRename = { chatToRename = it },
                onEditInstruction = { chatToEditInstruction = it },
                onDelete = { chatToDelete = it }
            )
        } else {
        Box(
            Modifier
                .fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .hazeSource(state = hazeState)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Spacer(Modifier.height(headerHeightDp))
                }
                items(
                    vm.history.size,
                    key = { index -> vm.history[index].id }) { index ->
                    val msg = vm.history[index]
                    val isUser = msg.own
                    val msgKey = msg.id
                    DisposableEffect(msgKey) { onDispose { msgCoords.remove(msgKey) } }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(1f)
                            .onGloballyPositioned { msgCoords[msgKey] = it },
                        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
                    ) {
                        if (isUser) {
                            Box(
                                modifier = Modifier
                                    .widthIn(max = 280.dp)
                                    .padding(
                                        start = 40.dp,
                                        end = 8.dp
                                    )
                            ) {
                                if (vm.isEditMode && index == vm.editIndex) {
                                    TextField(
                                        value = vm.currentEditMsg,
                                        onValueChange = { vm.currentEditMsg = it },
                                        textStyle = TextStyle(
                                            color = White,
                                            fontSize = 15.sp,
                                            lineHeight = 21.sp
                                        ),
                                        shape = RoundedCornerShape(14.dp),
                                        placeholder = {
                                            Text(
                                                stringResource(R.string.nachricht_bearbeiten),
                                                color = TextSecondary
                                            )
                                        },
                                        colors = TextFieldDefaults.colors(
                                            focusedTextColor = White,
                                            unfocusedTextColor = White,
                                            cursorColor = White,
                                            focusedContainerColor = MaterialTheme.colorScheme.primary,
                                            unfocusedContainerColor = MaterialTheme.colorScheme.primary,
                                            focusedIndicatorColor = Transparent,
                                            unfocusedIndicatorColor = Transparent
                                        ),
                                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                                        keyboardActions = KeyboardActions(onGo = { vm.sendMessage() }),
                                        modifier = Modifier
                                            .clip(
                                                RoundedCornerShape(
                                                    topEnd = 10.dp,
                                                    bottomEnd = 20.dp,
                                                    bottomStart = 10.dp,
                                                    topStart = 2.dp
                                                )
                                            )
                                            .zIndex(1000000f)
                                            .combinedClickable(
                                                onLongClick = { selectMessage(index) },
                                                onClick = {}
                                            )
                                    )
                                } else {
                                    Text(
                                        text = msg.text,
                                        color = White,
                                        fontSize = 15.sp,
                                        lineHeight = 21.sp,
                                        modifier = Modifier
                                            .clip(
                                                RoundedCornerShape(
                                                    topEnd = 2.dp,
                                                    bottomEnd = 10.dp,
                                                    bottomStart = 10.dp,
                                                    topStart = 10.dp
                                                )
                                            )
                                            .background(MaterialTheme.colorScheme.primary)
                                            .zIndex(1000000f)
                                            .combinedClickable(
                                                onLongClick = { selectMessage(index) },
                                                onClick = {}
                                            )
                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                    )
                                }
                            }
                        } else {
                            NeonBox(
                                modifier = Modifier
                                    .widthIn(max = 380.dp)
                                    .padding(
                                        start = 8.dp,
                                        end = 40.dp
                                    )
                                    .clip(RoundedCornerShape(2.dp, 10.dp, 10.dp, 10.dp))
                                    .combinedClickable(
                                        onLongClick = { selectMessage(index) },
                                        onClick = {}
                                    ),
                                cornerRadius = RoundedCornerShape(2.dp, 10.dp, 10.dp, 10.dp),
                                backgroundAlpha = 0.91f,
                                borderWidth = 2.8.dp,
                                neonColors = listOf(Accent, AccentDim),
                            ) {
                                Column(
                                    modifier = Modifier.padding(
                                        horizontal = 14.dp,
                                        vertical = 10.dp
                                    )
                                ) {
                                    Markdown(
                                        content = if (vm.isLoading && msg.text.isEmpty()) "..." else normalizeCallouts(
                                            msg.text
                                        ),
                                        colors = markdownColors,
                                        typography = markdownTypography,
                                        components = markdownComponents
                                    )

                                    if (msg.mode != null) {
                                        Text(
                                            text = msg.mode,
                                            color = TextTertiary,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(inputBarHeightDp))
                }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned {
                            headerHeightDp = with(density) { it.size.height.toDp() }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                            .hazeBlur(
                                input = HazeInput.Sources(state = hazeState),
                                style = HazeBlurStyle {
                                    backgroundColor(BgDeep)
                                    colorEffects(
                                        listOf(
                                            HazeColorEffect.tint(
                                                BgDeep.copy(
                                                    alpha = 0.7f
                                                )
                                            )
                                        )
                                    )
                                    blurRadius(60.dp)
                                    noiseFactor(0f)
                                }
                            )
                            .drawWithContent {
                                drawContent()
                                val fadePx = 24.dp.toPx()
                                val bottomStop = (1f - fadePx / size.height).coerceIn(0f, 1f)
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0f to Black,
                                        bottomStop to Black,
                                        1f to Transparent
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = paddingValues.calculateTopPadding())
                    ) {
                        AiChatHeader(
                            title = vm.activeChat?.title.orEmpty(),
                            hasInstruction = vm.activeChat?.systemPrompt?.isNotBlank() == true,
                            onOpenList = { vm.showChatList = true },
                            onNewChat = { vm.newChat() },
                            onRename = { vm.activeChat?.let { chatToRename = it } },
                            onEditInstruction = { vm.activeChat?.let { chatToEditInstruction = it } },
                            onDelete = { vm.activeChat?.let { chatToDelete = it } }
                        )
                        Row(
                            modifier = Modifier
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isPrivate = prvt()

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                listOfNotNull(
                                    "Nvidia",
                                    "Gemini",
                                    "OpenRouter",
                                    if (isPrivate) "Server" else null
                                ).forEachIndexed { index, mode ->
                                    val containerColor by animateColorAsState(
                                        targetValue = if (vm.currentMode == mode) TextTertiary else Color(
                                            0xFF333333
                                        ),
                                        animationSpec = tween(durationMillis = 300),
                                        label = "containerColor"
                                    )
                                    Box {
                                        PloppingButton(
                                            onClick = {
                                                if (vm.currentMode == mode) vm.showAiModels = true
                                                vm.setMode(mode)
                                            },
                                            colors = buttonColors(containerColor = containerColor),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Text(mode, color = White, fontSize = 13.sp)
                                        }

                                        if (index == 0) {
                                            DropdownMenu(
                                                expanded = vm.showAiModels,
                                                onDismissRequest = { vm.showAiModels = false },
                                                containerColor = BgSurface,
                                                shape = RoundedCornerShape(30.dp),
                                                modifier = Modifier.padding(10.dp, 0.dp)
                                            ) {
                                                var showedDiv = false
                                                vm.availableModels.forEach { model ->
                                                    if (model.vision && !showedDiv && !vm.availableModels[0].vision) {
                                                        Row(
                                                            modifier = Modifier
                                                                .padding(vertical = 16.dp)
                                                                .fillMaxWidth(),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            HorizontalDivider(
                                                                modifier = Modifier.weight(1f),
                                                                thickness = 1.dp,
                                                                color = White.copy(alpha = 0.3f)
                                                            )

                                                            Text(
                                                                text = stringResource(R.string.vision_models),
                                                                modifier = Modifier.padding(horizontal = 8.dp)
                                                            )

                                                            HorizontalDivider(
                                                                modifier = Modifier.weight(1f),
                                                                thickness = 1.dp,
                                                                color = White.copy(alpha = 0.3f)
                                                            )
                                                        }
                                                        showedDiv = true
                                                    }
                                                    val containerColorModel by animateColorAsState(
                                                        targetValue = if (vm.selectedModel == model) Color(
                                                            0xFF555555
                                                        ) else BgSurface,
                                                        animationSpec = tween(durationMillis = 300),
                                                        label = "containerColor"
                                                    )
                                                    PloppingButton(
                                                        onClick = {
                                                            vm.selectModel(model)
                                                        },
                                                        onFinishedClick = { vm.showAiModels = false },
                                                        colors = buttonColors(containerColor = containerColorModel),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        val sizeRegex = Regex("""-\d+b""")
                                                        val sizeRegex1 = Regex("""\d+b""")
                                                        val name = model.name
                                                            .replace(sizeRegex, "")
                                                            .replace("-", " ")
                                                            .substringBeforeLast(":")
                                                        val sizeString: String? =
                                                            when (vm.currentMode) {
                                                                "Nvidia" -> sizeRegex1.find(
                                                                    model.name
                                                                )?.value

                                                                "Gemini" -> null
                                                                "OpenRouter" -> null
                                                                else -> model.name.substringAfter(":")
                                                            }
                                                        val size =
                                                            if (sizeString != null && sizeString != "null") {
                                                                " (${sizeString.replace("-", " ")})"
                                                            } else ""
                                                        val weightInfo =
                                                            if (model.weight > 1 && !prvt()) " [${model.weight}x]" else ""
                                                        Text(
                                                            "$name$size$weightInfo",
                                                            textAlign = TextAlign.Left,
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (vm.currentMode != "Server" && !prvt()) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                LinearProgressIndicator(
                                    progress = { vm.getUsageProgress() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    color = Accent,
                                    trackColor = BgSurface,
                                    strokeCap = ProgressIndicatorDefaults.LinearStrokeCap,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        vm.getUsageResetText(),
                                        color = White.copy(alpha = 0.8f),
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(start = 10.dp)
                                    )
                                    Text(
                                        "${vm.todayUsage}/$DAILY_LIMIT",
                                        color = White,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(end = 10.dp)
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                    }
                }

                if (vm.history.isEmpty()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.ai_chat_empty_hint),
                            color = TextTertiary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }

                val uploadButtonWeight by animateDpAsState(
                    targetValue = if (vm.selectedModel.vision) 48.dp else 0.dp,
                    animationSpec = tween(durationMillis = 300),
                    label = "uploadButtonWeight"
                )

                val uploadButtonAlpha by animateFloatAsState(
                    targetValue = if (vm.selectedModel.vision) 1f else 0f,
                    animationSpec = tween(durationMillis = 300),
                    label = "uploadButtonWeight"
                )

                val audioButtonWeight by animateDpAsState(
                    targetValue = if (vm.selectedModel.audio) 48.dp else 0.dp,
                    animationSpec = tween(300), label = ""
                )
                val audioButtonAlpha by animateFloatAsState(
                    targetValue = if (vm.selectedModel.audio) 1f else 0f,
                    animationSpec = tween(300), label = ""
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(1f)
                        .onGloballyPositioned {
                            inputBarHeightDp = with(density) { it.size.height.toDp() }
                        }
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier
                            .size(uploadButtonWeight)
                            .alpha(uploadButtonAlpha)
                            .background(
                                if (vm.selectedImageUri != null) TextTertiary else Color(
                                    0xFF333333
                                ),
                                RoundedCornerShape(50)
                            )
                    ) {
                        Icon(
                            if (vm.selectedImageUri == null) Icons.Default.CameraAlt else Icons.Default.Check,
                            stringResource(R.string.bild_anhangen),
                            tint = Black
                        )
                    }

                    IconButton(
                        onClick = { audioPickerLauncher.launch("audio/*") },
                        modifier = Modifier
                            .size(audioButtonWeight)
                            .alpha(audioButtonAlpha)
                            .background(
                                if (vm.selectedAudioUri != null) TextTertiary else Color(
                                    0xFF333333
                                ),
                                RoundedCornerShape(50)
                            )
                    ) {
                        Icon(
                            if (vm.selectedAudioUri == null) Icons.Default.MusicNote else Icons.Default.Check,
                            stringResource(R.string.audio_anhangen),
                            tint = Black
                        )
                    }

                    TextField(
                        value = vm.currentMsg,
                        onValueChange = { vm.currentMsg = it; vm.isEditMode = false },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        placeholder = {
                            Text(
                                stringResource(R.string.nachricht_eingeben),
                                color = TextSecondary
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = White,
                            unfocusedTextColor = White,
                            cursorColor = White,
                            focusedContainerColor = BgCard,
                            unfocusedContainerColor = BgCard,
                            focusedIndicatorColor = Transparent,
                            unfocusedIndicatorColor = Transparent
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (!vm.isLoading) vm.sendMessage()
                        })
                    )

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (vm.isLoading) BgCard else BgSurface
                            )
                            .size(48.dp)
                            .clickable(enabled = !vm.isLoading) { vm.sendMessage() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.send_message),
                            tint = if (vm.isLoading) White.copy(alpha = 0.4f) else White
                        )
                    }
                }
            }
        }

        if (overlayAlpha.value > 0) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .alpha(overlayAlpha.value)
                    .alpha(alpha.value)
                    .onGloballyPositioned { overlayTopRoot = it.positionInRoot().y }
                    .onSizeChanged { overlayHeightPx = it.height }
            ) {
                var message: ChatMessage? by remember { mutableStateOf(null) }
                vm.selectedMsg?.let { message = vm.history.getOrNull(it) }

                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Black.copy(0.6f))
                        .imePadding()
                        .then(
                            if (vm.selectedMsg != null && overlayAlpha.value > 0.5f) Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                vm.selectedMsg = null
                                svm.fireEvent(false)
                            } else Modifier
                        )
                )

                Row(
                    Modifier
                        .offset {
                            val top = (bubbleTopRoot - overlayTopRoot).roundToInt()
                            val bottom = (bubbleBottomRoot - overlayTopRoot).roundToInt()
                            val y = if (menuGoesUp) top - menuHeightPx - gapPx else bottom + gapPx
                            val maxY = (bottomLimitPx - menuHeightPx).coerceAtLeast(minYPx)
                            IntOffset(0, y.coerceIn(minYPx, maxY))
                        }
                        .align(
                            if (message?.own == true) Alignment.TopEnd else Alignment.TopStart
                        )
                        .padding(
                            end = if (message?.own == true) 10.dp else 0.dp,
                            start = if (message?.own == true) 0.dp else 10.dp
                        )
                        .width(200.dp)
                        .clip(
                            if (menuGoesUp) {
                                RoundedCornerShape(
                                    topEnd = 10.dp,
                                    bottomEnd = 2.dp,
                                    bottomStart = 10.dp,
                                    topStart = 20.dp
                                )
                            } else if (message?.own == false) {
                                RoundedCornerShape(
                                    bottomStart = 10.dp,
                                    topStart = 2.dp,
                                    topEnd = 10.dp,
                                    bottomEnd = 20.dp
                                )
                            } else {
                                RoundedCornerShape(
                                    topStart = 10.dp,
                                    topEnd = 2.dp,
                                    bottomEnd = 10.dp,
                                    bottomStart = 20.dp
                                )
                            }
                        )
                        .background(BgSurface)
                        .height(menuHeightDp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.End
                    ) {
                        ChatMenuAction(
                            text = stringResource(R.string.kopieren),
                            onClick = {
                                val text = message?.text
                                if (text != null) {
                                    val clip = ClipData(
                                        ClipDescription(
                                            aitabMessageLabel,
                                            arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN)
                                        ),
                                        ClipData.Item(text)
                                    )
                                    cs.setPrimaryClip(clip)
                                    vm.selectedMsg = null
                                    svm.fireEvent(false)
                                }
                            }
                        )

                        if (vm.selectedMsg != null && message?.own == true && canModifyMessages) {
                            Spacer(Modifier.height(12.dp))
                            ChatMenuAction(
                                text = stringResource(R.string.bearbeiten),
                                onClick = {
                                    val msgIndex = vm.selectedMsg
                                    if (msgIndex != null) {
                                        vm.currentEditMsg = vm.history[msgIndex].text
                                        vm.editIndex = msgIndex
                                        vm.isEditMode = true
                                        vm.selectedMsg = null
                                        svm.fireEvent(false)
                                    }
                                }
                            )
                        }

                        if (vm.selectedMsg == vm.history.lastIndex && message?.own == false && canModifyMessages) {
                            Spacer(Modifier.height(12.dp))
                            ChatMenuAction(
                                text = stringResource(R.string.ai_message_regenerate),
                                onClick = {
                                    vm.selectedMsg = null
                                    svm.fireEvent(false)
                                    vm.regenerateLast()
                                }
                            )
                        }

                        if (vm.selectedMsg != null && message != null && canModifyMessages) {
                            Spacer(Modifier.height(12.dp))
                            ChatMenuAction(
                                text = stringResource(R.string.ai_message_delete),
                                color = ErrorRed,
                                onClick = {
                                    val chatId = vm.activeChatId
                                    val index = vm.selectedMsg
                                    if (chatId != null && index != null) {
                                        messageToDelete = chatId to index
                                        vm.selectedMsg = null
                                        svm.fireEvent(false)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
        }
    }

    LaunchedEffect(vm.isLoading, vm.streamSeq, vm.history.size, vm.activeChatId) {
        if (!vm.showChatList && vm.history.isNotEmpty()) {
            listState.animateScrollToItem(vm.history.size)
        }
    }
}

@Composable
private fun AiChatHeader(
    title: String,
    hasInstruction: Boolean,
    onOpenList: () -> Unit,
    onNewChat: () -> Unit,
    onRename: () -> Unit,
    onEditInstruction: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenList) {
            Icon(
                Icons.Default.History,
                contentDescription = stringResource(R.string.ai_chats),
                tint = White
            )
        }
        Text(
            text = title.ifBlank { stringResource(R.string.ai_chat_new) },
            color = White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (hasInstruction) {
            Icon(
                Icons.Default.Tune,
                contentDescription = stringResource(R.string.ai_chat_instruction_active),
                tint = AccentViolet,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onNewChat) {
            Icon(
                Icons.Default.Add,
                contentDescription = stringResource(R.string.ai_chat_new),
                tint = White
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.ai_chat_options),
                    tint = White
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = BgCard
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_chat_rename),
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_chat_instruction_edit),
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onEditInstruction()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_chat_delete),
                            color = ErrorRed,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun AiChatListScreen(
    chats: List<ChatSession>,
    activeChatId: String?,
    topPadding: Dp,
    listState: LazyListState,
    onBack: () -> Unit,
    onNewChat: () -> Unit,
    onOpenChat: (String) -> Unit,
    onRename: (ChatSession) -> Unit,
    onEditInstruction: (ChatSession) -> Unit,
    onDelete: (ChatSession) -> Unit
) {
    LaunchedEffect(activeChatId, chats.size) {
        val index = chats.indexOfFirst { it.id == activeChatId }
        if (index >= 0) listState.scrollToItem(index)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topPadding)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.zuruck),
                    tint = White
                )
            }
            Text(
                stringResource(R.string.ai_chats),
                color = White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNewChat) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = stringResource(R.string.ai_chat_new),
                    tint = White
                )
            }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(chats, key = { it.id }) { chat ->
                AiChatRow(
                    chat = chat,
                    active = chat.id == activeChatId,
                    onOpen = { onOpenChat(chat.id) },
                    onRename = { onRename(chat) },
                    onEditInstruction = { onEditInstruction(chat) },
                    onDelete = { onDelete(chat) }
                )
            }
        }
    }
}

@Composable
private fun AiChatRow(
    chat: ChatSession,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onEditInstruction: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val preview = chat.messages.lastOrNull { it.text.isNotBlank() }?.text
        ?.replace('\n', ' ')
        ?.take(120)
        .orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else BgCard
            )
            .clickable { onOpen() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.title.ifBlank { stringResource(R.string.ai_chat_new) },
                    color = TextPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (chat.systemPrompt.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Tune,
                        contentDescription = stringResource(R.string.ai_chat_instruction_active),
                        tint = AccentViolet,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text(
                text = if (preview.isBlank()) {
                    stringResource(R.string.ai_chat_no_messages)
                } else {
                    preview
                },
                color = TextTertiary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = DateUtils.getRelativeTimeSpanString(
                chat.updatedAt,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS,
                DateUtils.FORMAT_ABBREV_RELATIVE
            ).toString(),
            color = TextTertiary,
            fontSize = 11.sp,
            maxLines = 1
        )
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.ai_chat_options),
                    tint = TextSecondary
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = BgCard
            ) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_chat_rename),
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onRename()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_chat_instruction_edit),
                            color = TextPrimary,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onEditInstruction()
                    }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(R.string.ai_chat_delete),
                            color = ErrorRed,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    }
                )
            }
        }
    }
}

@Composable
private fun ChatMenuAction(
    text: String,
    color: Color = TextPrimary,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 22.sp
        )
    }
}
