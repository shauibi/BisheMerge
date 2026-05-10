package com.llmapp.ui.chat

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmapp.LLMApplication
import com.llmapp.R
import com.llmapp.data.database.MessageEntity
import com.llmapp.data.model.PromptTemplate
import com.llmapp.utils.MarkdownBuffer
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// 聊天主界面 Composable，包含会话抽屉、消息列表、底部工具栏和输入栏
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    isModelLoaded: Boolean = false,
    onTakePhoto: () -> Unit,
    onPickImage: () -> Unit,
    selectedImagePath: String? = null,
    onClearImage: () -> Unit = {},
    templateChips: List<PromptTemplate> = emptyList(),
    onTemplateChipClick: (PromptTemplate) -> Unit = {},
    isListening: Boolean = false,
    onMicPress: (() -> Unit)? = null,
    onMicRelease: (() -> Unit)? = null,
    voicePartialText: String? = null,
    isVoiceInitializing: Boolean = false
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val markdownBuffers = remember { mutableStateMapOf<Long, MarkdownBuffer>() }

    val context = LocalContext.current
    val app = context.applicationContext as LLMApplication
    // 监听应用内模板选中事件，填入输入框
    LaunchedEffect(Unit) {
        app.templateSelected.collect { text -> inputText = text }
    }

    // 切换会话时清空 Markdown 缓存
    LaunchedEffect(uiState.currentSessionId) { markdownBuffers.clear() }

    // 显示错误 Snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SessionDrawer(
                sessions = uiState.sessions,
                currentSessionId = uiState.currentSessionId,
                onSessionClick = {
                    viewModel.switchSession(it)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    viewModel.createNewSession()
                    scope.launch { drawerState.close() }
                },
                onDeleteSession = { viewModel.deleteSession(it) }
            )
        }
    ) {
        Scaffold(
            topBar = {
                // 顶部栏：菜单按钮 + 聊天标题 + 导出按钮
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.chat_title),
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.menu))
                        }
                    },
                    actions = {
                        if (uiState.messages.isNotEmpty()) {
                            IconButton(onClick = {
                                exportChat(context, uiState.messages)
                            }) {
                                Icon(
                                    Icons.Default.Share,
                                    contentDescription = stringResource(R.string.export_chat)
                                )
                            }
                        }
                    }
                )
            },
            bottomBar = {
                Column {
                    // Compact toolbar row: template chips + image badge
                    val hasToolbar = templateChips.isNotEmpty() || selectedImagePath != null
                    if (hasToolbar) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            // 提示词模板快捷选择芯片
                            if (templateChips.isNotEmpty()) {
                                Row(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    templateChips.forEach { template ->
                                        AssistChip(
                                            onClick = { onTemplateChipClick(template) },
                                            label = {
                                                Text(template.name, fontSize = 11.sp, maxLines = 1)
                                            },
                                            modifier = Modifier.height(28.dp),
                                            shape = RoundedCornerShape(14.dp),
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.AutoAwesome,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            // 图片预览徽章
                            if (selectedImagePath != null) {
                                AssistChip(
                                    onClick = onClearImage,
                                    label = { Text("1", fontSize = 11.sp) },
                                    leadingIcon = {
                                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                                    },
                                    trailingIcon = {
                                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(12.dp))
                                    },
                                    modifier = Modifier.height(28.dp),
                                    shape = RoundedCornerShape(14.dp)
                                )
                            }
                        }
                    }

                    // RAG 开关芯片 + 推理耗时显示
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilterChip(
                            selected = uiState.ragEnabled,
                            onClick = { viewModel.setRagEnabled(!uiState.ragEnabled) },
                            label = {
                                Text(
                                    text = if (uiState.ragEnabled && uiState.retrievedChunkCount > 0)
                                        "${uiState.retrievedChunkCount} chunks"
                                    else
                                        "RAG",
                                    fontSize = 11.sp
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Storage,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            },
                            modifier = Modifier.height(28.dp),
                            shape = RoundedCornerShape(14.dp)
                        )

                        // 推理耗时信息
                        if (uiState.timingInfo != null) {
                            Text(
                                text = uiState.timingInfo!!,
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                                maxLines = 1
                            )
                        }
                    }

                    InputBar(
                        inputText = inputText,
                        onInputChange = { inputText = it },
                        onSend = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText, selectedImagePath)
                                inputText = ""
                            }
                        },
                        onTakePhoto = onTakePhoto,
                        onPickImage = onPickImage,
                        isGenerating = uiState.isGenerating,
                        isModelLoaded = isModelLoaded,
                        isListening = isListening,
                        onMicPress = onMicPress,
                        onMicRelease = onMicRelease,
                        voicePartialText = voicePartialText,
                        isVoiceInitializing = isVoiceInitializing
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { innerPadding ->
            if (uiState.messages.isEmpty()) {
                // 空状态占位
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Outlined.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(56.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.no_messages),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    state = listState,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        val buffer = markdownBuffers.getOrPut(message.id) { MarkdownBuffer() }
                        // 流式生成中的消息使用 Streaming 文本，否则使用已保存的完整文本
                        val displayText = if (message.id == uiState.generatingMessageId) {
                            val sourceText = uiState.streamingContents[message.id] ?: message.content
                            buffer.getDisplayText(sourceText)
                        } else {
                            markdownBuffers.remove(message.id)
                            message.content
                        }
                        MessageBubble(
                            message = message,
                            displayText = displayText,
                            onDelete = { viewModel.deleteMessage(message.id) },
                            chunks = uiState.retrievedChunks[message.id] ?: emptyList()
                        )
                        // RAG retrieval timing below assistant bubble
                        if (uiState.ragQueryDone && message.id == uiState.ragQueryMessageId) {
                            Text(
                                text = "RAG: ${uiState.ragTotalChunks} chunks | embed ${uiState.ragEmbedMs}ms + search ${uiState.ragSearchMs}ms = ${uiState.ragTotalMs}ms",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                                modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                            )
                        }
                        if (message.imagePath != null && message.role == MessageEntity.ROLE_USER) {
                            ImagePreview(imagePath = message.imagePath)
                        }
                    }
                }

                // 新消息到达时自动滚动到底部
                LaunchedEffect(uiState.messages.size) {
                    if (uiState.messages.isNotEmpty()) {
                        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                        val isNearBottom = lastVisible?.index == uiState.messages.size - 2 ||
                            listState.firstVisibleItemIndex > uiState.messages.size - 3
                        if (isNearBottom || uiState.messages.size <= 2) {
                            listState.animateScrollToItem(uiState.messages.size - 1)
                        }
                    }
                }
            }
        }
    }
}

// 将当前会话导出为 Markdown 文件并通过系统分享发送
private fun exportChat(context: Context, messages: List<MessageEntity>) {
    if (messages.isEmpty()) return

    val sdf = SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.getDefault())
    val fileName = "chat_${sdf.format(Date())}.md"

    val sb = StringBuilder()
    sb.appendLine("# Chat Export")
    sb.appendLine("> ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())}")
    sb.appendLine()

    for (msg in messages) {
        val role = if (msg.role == MessageEntity.ROLE_USER) "**You**" else "**LLM**"
        sb.appendLine("### $role")
        sb.appendLine(msg.content.trim())
        sb.appendLine()
        if (!msg.imagePath.isNullOrBlank()) {
            sb.appendLine("> [Image: ${msg.imagePath}]")
            sb.appendLine()
        }
    }

    val file = File(context.cacheDir, fileName)
    file.writeText(sb.toString())

    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/markdown"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.export_chat)))
}
