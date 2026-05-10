/**
 * 聊天界面ViewModel
 * 负责管理聊天界面的状态和业务逻辑
 * 包括：会话管理、消息管理、模型推理等
 * 模型会话由 LLMApplication 全局持有，此处只引用
 */
package com.llmapp.ui.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmapp.LLMApplication
import com.llmapp.R
import com.llmapp.data.database.MessageEntity
import com.llmapp.data.database.SessionEntity
import com.llmapp.data.rag.ChunkWithDocInfo
import com.llmapp.jni.InferenceCallback
import com.llmapp.jni.NativeLib
import com.llmapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 聊天界面完整 UI 状态
data class ChatUiState(
    val sessions: List<SessionEntity> = emptyList(),
    val currentSessionId: Long = -1,
    val messages: List<MessageEntity> = emptyList(),
    val isGenerating: Boolean = false,
    val generatingMessageId: Long = -1,
    val streamingContents: Map<Long, String> = emptyMap(),
    val timingInfo: String? = null,
    val selectedImagePath: String? = null,
    val error: String? = null,
    val ragEnabled: Boolean = false,
    val retrievedChunkCount: Int = 0,
    val retrievedChunks: Map<Long, List<ChunkWithDocInfo>> = emptyMap(),
    val maxHistoryTurns: Int = 20,
    val ragEmbedMs: Long = 0,
    val ragSearchMs: Long = 0,
    val ragTotalMs: Long = 0,
    val ragTotalChunks: Int = 0,
    val ragQueryDone: Boolean = false,
    val ragQueryMessageId: Long = -1
)

// 聊天界面 ViewModel，管理会话/消息/流式推理/RAG 检索等核心逻辑
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LLMApplication
    private val chatRepository = app.chatRepository

    private var messagesJob: Job? = null
    private var activeInferenceJob: Job? = null

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        loadSessions()
    }

    /** 便捷的 sessionPtr 获取，由 LLMApplication 全局持有 */
    private val sessionPtr: Long get() = app.sessionPtr

    // 取消当前正在进行的推理任务
    fun cancelActiveInference() {
        activeInferenceJob?.cancel()
        activeInferenceJob = null
        _uiState.value = _uiState.value.copy(
            isGenerating = false,
            generatingMessageId = -1,
            streamingContents = emptyMap()
        )
    }

    // 从 Room 加载所有会话列表，自动选定第一个或保持当前会话
    private fun loadSessions() {
        viewModelScope.launch {
            chatRepository.getAllSessions().collect { sessions ->
                val currentState = _uiState.value
                val needLoadMessages = currentState.currentSessionId == -1L && sessions.isNotEmpty()
                val newCurrentId = if (needLoadMessages) {
                    sessions.first().id
                } else {
                    if (currentState.currentSessionId != -1L && sessions.none { it.id == currentState.currentSessionId }) {
                        sessions.firstOrNull()?.id ?: -1L
                    } else {
                        currentState.currentSessionId
                    }
                }
                _uiState.value = currentState.copy(
                    sessions = sessions,
                    currentSessionId = newCurrentId
                )
                if (needLoadMessages || newCurrentId != currentState.currentSessionId) {
                    if (newCurrentId != -1L) {
                        loadMessages(newCurrentId)
                    }
                }
            }
        }
    }

    // 设置当前选中的待发送图片路径
    fun setSelectedImagePath(path: String?) {
        _uiState.value = _uiState.value.copy(selectedImagePath = path)
    }

    // 清除当前选中的图片
    fun clearSelectedImage() {
        _uiState.value = _uiState.value.copy(selectedImagePath = null)
    }

    // 清除错误信息
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    // 切换 RAG 检索开关
    fun setRagEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(ragEnabled = enabled, retrievedChunkCount = 0, ragEmbedMs = 0, ragSearchMs = 0, ragTotalMs = 0, ragTotalChunks = 0, ragQueryDone = false, ragQueryMessageId = -1)
    }

    // 设置最大对话轮数，超出部分在下次推理前自动裁剪
    fun setMaxHistoryTurns(turns: Int) {
        _uiState.value = _uiState.value.copy(maxHistoryTurns = turns)
    }

    // 创建新会话，清空 LLM 历史记录
    fun createNewSession() {
        viewModelScope.launch {
            activeInferenceJob?.cancel()
            if (sessionPtr != 0L) {
                NativeLib.clearHistory(sessionPtr)
            }
            val title = app.getString(R.string.new_chat)
            val sessionId = chatRepository.createSession(title)
            messagesJob?.cancel()
            _uiState.value = _uiState.value.copy(
                currentSessionId = sessionId,
                messages = emptyList(),
                isGenerating = false,
                generatingMessageId = -1,
                streamingContents = emptyMap(),
                retrievedChunks = emptyMap()
            )
            loadMessages(sessionId)
        }
    }

    // 切换到指定会话，清空上一条会话的原生历史并重新加载
    fun switchSession(sessionId: Long) {
        activeInferenceJob?.cancel()
        messagesJob?.cancel()
        if (sessionPtr != 0L) {
            NativeLib.clearHistory(sessionPtr)
        }
        _uiState.value = _uiState.value.copy(
            currentSessionId = sessionId,
            messages = emptyList(),
            isGenerating = false,
            generatingMessageId = -1,
            streamingContents = emptyMap(),
            retrievedChunks = emptyMap()
        )
        loadMessages(sessionId)
    }

    // 删除指定会话，自动切换到下一个可用会话
    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            val currentSessions = _uiState.value.sessions.filter { it.id != sessionId }
            val newCurrentId = if (_uiState.value.currentSessionId == sessionId) {
                currentSessions.firstOrNull()?.id ?: -1L
            } else {
                _uiState.value.currentSessionId
            }
            if (newCurrentId == _uiState.value.currentSessionId && newCurrentId != -1L) {
                loadMessages(newCurrentId)
            } else {
                messagesJob?.cancel()
                _uiState.value = _uiState.value.copy(
                    messages = emptyList(),
                    currentSessionId = newCurrentId
                )
            }
        }
    }

    // 发送消息：插入用户消息和助理占位消息，触发 LLM 流式推理
    fun sendMessage(text: String, imagePath: String?) {
        viewModelScope.launch {
            activeInferenceJob?.cancel()
            var sessionId = _uiState.value.currentSessionId

            // 无会话时自动创建，首个消息自动更新会话标题
            if (sessionId == -1L) {
                val title = if (text.length > 20) text.substring(0, 20) + "..." else text
                sessionId = chatRepository.createSession(title)
                _uiState.value = _uiState.value.copy(currentSessionId = sessionId)
            } else {
                val currentSession = _uiState.value.sessions.find { it.id == sessionId }
                val defaultTitle = app.getString(R.string.new_chat)
                if (currentSession != null && currentSession.title == defaultTitle && _uiState.value.messages.isEmpty()) {
                    val newTitle = if (text.length > 20) text.substring(0, 20) + "..." else text
                    chatRepository.updateSessionTitle(sessionId, newTitle)
                }
            }

            val currentSessionId = sessionId
            val finalImagePath = imagePath ?: _uiState.value.selectedImagePath

            val userMsg = MessageEntity(
                sessionId = currentSessionId,
                role = MessageEntity.ROLE_USER,
                content = text,
                imagePath = finalImagePath
            )
            chatRepository.insertMessage(userMsg)

            val assistantMsg = MessageEntity(
                sessionId = currentSessionId,
                role = MessageEntity.ROLE_ASSISTANT,
                content = ""
            )
            val assistantMsgId = chatRepository.insertMessage(assistantMsg)

            // RAG: prepend retrieved context when enabled
            val finalPrompt = if (_uiState.value.ragEnabled && app.isEmbeddingModelLoaded.value) {
                var embedMs = 0L
                var searchMs = 0L
                val results = withContext(Dispatchers.IO) {
                    // Phase 1: full search (searchSimilar includes its own embedding)
                    val t0 = System.currentTimeMillis()
                    val r = app.ragRepository.searchSimilar(text, topK = 3)
                    val totalMs = System.currentTimeMillis() - t0

                    // Phase 2: measure pure embedding AFTER search (warm cache,
                    // so subtraction is accurate)
                    val ptr = app.embeddingSessionPtr
                    if (ptr != 0L) {
                        val t1 = System.currentTimeMillis()
                        NativeLib.computeEmbedding(ptr, text)
                        embedMs = System.currentTimeMillis() - t1
                    }
                    searchMs = if (totalMs > embedMs) totalMs - embedMs else 0
                    r
                }
                val totalChunks = app.database.ragDao().getAllChunks().size
                if (results.isNotEmpty()) {
                    val enriched = app.ragRepository.enrichChunks(results)
                    _uiState.value = _uiState.value.copy(
                        retrievedChunkCount = results.size,
                        retrievedChunks = _uiState.value.retrievedChunks + (assistantMsgId to enriched),
                        ragEmbedMs = embedMs,
                        ragSearchMs = searchMs,
                        ragTotalMs = embedMs + searchMs,
                        ragTotalChunks = totalChunks,
                        ragQueryDone = true,
                        ragQueryMessageId = assistantMsgId
                    )
                    val contextStr = results.joinToString("\n\n---\n\n") { it.chunk.content }
                    buildRagPrompt(text, contextStr)
                } else {
                    _uiState.value = _uiState.value.copy(
                        retrievedChunkCount = 0,
                        ragEmbedMs = embedMs,
                        ragSearchMs = searchMs,
                        ragTotalMs = embedMs + searchMs,
                        ragTotalChunks = totalChunks,
                        ragQueryDone = true,
                        ragQueryMessageId = assistantMsgId
                    )
                    text
                }
            } else {
                text
            }

            _uiState.value = _uiState.value.copy(
                isGenerating = true,
                generatingMessageId = assistantMsgId,
                selectedImagePath = null
            )

            // 自动裁剪过长的对话历史，防止 KV cache 溢出
            if (sessionPtr != 0L) {
                val turnCount = NativeLib.getHistoryTurnCount(sessionPtr)
                val maxTurns = _uiState.value.maxHistoryTurns
                if (turnCount >= maxTurns) {
                    NativeLib.trimHistory(sessionPtr, maxTurns)
                }
            }

            startStreamingInference(assistantMsgId, currentSessionId, finalPrompt, finalImagePath)
        }
    }

    // 启动 LLM 流式推理，通过 NativeLib.inferenceStream 异步回调逐 token 更新 UI
    private fun startStreamingInference(
        assistantMsgId: Long,
        sessionId: Long,
        prompt: String,
        imagePath: String?
    ) {
        if (sessionPtr == 0L) {
            viewModelScope.launch {
                chatRepository.updateMessageContent(
                    assistantMsgId,
                    app.getString(R.string.error_prefix, app.getString(R.string.error_model_not_loaded))
                )
                _uiState.value = _uiState.value.copy(
                    isGenerating = false,
                    generatingMessageId = -1,
                    streamingContents = emptyMap()
                )
            }
            return
        }

        activeInferenceJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val streamingBuilder = StringBuilder()
                var lastUiUpdateTime = 0L
                val batchIntervalMs = 30L

                NativeLib.inferenceStream(
                    sessionPtr,
                    prompt,
                    imagePath,
                    object : InferenceCallback {

                        // 收到 token 后追加到 StringBuilder，约 30ms 批量刷新 UI
                        override fun onToken(token: String) {
                            streamingBuilder.append(token)
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateTime >= batchIntervalMs) {
                                lastUiUpdateTime = now
                                val currentText = streamingBuilder.toString()
                                _uiState.value = _uiState.value.copy(
                                    streamingContents = _uiState.value.streamingContents + (assistantMsgId to currentText)
                                )
                            }
                        }

                        // 推理完成：写入最终文本到数据库并清除流式状态
                        override fun onComplete(fullText: String) {
                            if (_uiState.value.generatingMessageId != assistantMsgId) return

                            val finalText = when {
                                streamingBuilder.isNotEmpty() -> {
                                    val text = streamingBuilder.toString()
                                    _uiState.value = _uiState.value.copy(
                                        streamingContents = _uiState.value.streamingContents + (assistantMsgId to text)
                                    )
                                    text
                                }
                                fullText.isNotEmpty() -> fullText
                                else -> {
                                    viewModelScope.launch(Dispatchers.Main) {
                                        _uiState.value = _uiState.value.copy(
                                            isGenerating = false,
                                            generatingMessageId = -1,
                                            streamingContents = emptyMap()
                                        )
                                    }
                                    return
                                }
                            }

                            viewModelScope.launch(Dispatchers.IO) {
                                chatRepository.updateMessageContent(assistantMsgId, finalText)
                                withContext(Dispatchers.Main) {
                                    val timing = try { NativeLib.getLastTiming() } catch (_: Exception) { null }
                                    _uiState.value = _uiState.value.copy(
                                        isGenerating = false,
                                        generatingMessageId = -1,
                                        streamingContents = emptyMap(),
                                        timingInfo = timing
                                    )
                                }
                            }
                        }

                        // 推理出错：写入错误信息到消息内容
                        override fun onError(error: String) {
                            if (_uiState.value.generatingMessageId != assistantMsgId) return
                            viewModelScope.launch(Dispatchers.IO) {
                                chatRepository.updateMessageContent(assistantMsgId, "Error: $error")
                                withContext(Dispatchers.Main) {
                                    val timing = try { NativeLib.getLastTiming() } catch (_: Exception) { null }
                                    _uiState.value = _uiState.value.copy(
                                        isGenerating = false,
                                        generatingMessageId = -1,
                                        streamingContents = emptyMap(),
                                        timingInfo = timing
                                    )
                                }
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Logger.e("Inference error", e)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isGenerating = false,
                        generatingMessageId = -1,
                        streamingContents = emptyMap()
                    )
                }
            }
        }
    }

    // 构建带检索上下文的 RAG 提示词（英文指令）
    private fun buildRagPrompt(query: String, context: String): String {
        return "Use the context below to answer the question. " +
            "If the context does not contain enough information, " +
            "say so and answer based on your own knowledge.\n\n" +
            "Context:\n$context\n\n" +
            "Question: $query"
    }

    // 删除单条消息
    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
        }
    }

    // 订阅某会话的消息流
    private fun loadMessages(sessionId: Long) {
        messagesJob = viewModelScope.launch {
            chatRepository.getMessagesBySession(sessionId).collect { messages ->
                _uiState.value = _uiState.value.copy(messages = messages)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeInferenceJob?.cancel()
        messagesJob?.cancel()
        // 不销毁 session — 由 LLMApplication 全局持有
    }
}
