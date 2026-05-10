package com.llmapp

import android.app.Application
import com.llmapp.data.database.AppDatabase
import com.llmapp.data.rag.RagRepository
import com.llmapp.data.repository.ChatRepository
import com.llmapp.data.repository.ModelRepository
import com.llmapp.jni.NativeLib
import com.llmapp.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

// 全局 Application 单例，持有 LLM 和 Embedding 会话指针及数据层实例
class LLMApplication : Application() {

    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val chatRepository: ChatRepository by lazy { ChatRepository(database.chatDao()) }
    val modelRepository: ModelRepository by lazy { ModelRepository(this) }
    val ragRepository: RagRepository by lazy { RagRepository(database.ragDao(), this) }

    // 全局唯一的 LLM 会话指针
    @Volatile
    var sessionPtr: Long = 0
        private set

    // LLM 模型加载状态
    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded.asStateFlow()

    // ── Embedding model (RAG) ──────────────────────────────────────

    // 全局唯一的 Embedding 会话指针
    @Volatile
    var embeddingSessionPtr: Long = 0
        private set

    private val _isEmbeddingModelLoaded = MutableStateFlow(false)
    val isEmbeddingModelLoaded: StateFlow<Boolean> = _isEmbeddingModelLoaded.asStateFlow()

    // 模型加载中（用于 UI 显示 Loading）
    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    // 模板选中事件（模板页点击后，通知聊天页填入输入框）
    private val _templateSelected = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val templateSelected = _templateSelected.asSharedFlow()

    // 发出模板选中事件
    fun emitTemplateSelected(text: String) {
        _templateSelected.tryEmit(text)
    }

    // 当前模型路径
    private val _currentModelPath = MutableStateFlow<String?>(null)
    val currentModelPath: StateFlow<String?> = _currentModelPath.asStateFlow()

    // 模型切换事件（通知 ChatViewModel 等观察者）
    private val _modelReloadEvents = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val modelReloadEvents = _modelReloadEvents.asSharedFlow()

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 初始化 PDFBox 资源加载器
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(this)

        // 启动时自动加载上次选中的模型
        appScope.launch {
            val path = modelRepository.getSelectedModelPath().first()
            if (!path.isNullOrEmpty()) {
                createModelSession(path)
            }
        }

        // 自动加载已存储的 embedding 模型
        appScope.launch {
            val embedPath = modelRepository.getEmbeddingModelPath().first()
            if (!embedPath.isNullOrEmpty()) {
                // 仅当保存的路径仍然有效时才加载
                if (File(embedPath, "config.json").exists()) {
                    loadEmbeddingModel(embedPath)
                } else {
                    Logger.d("Saved embedding model path gone: $embedPath")
                }
            }
        }
    }

    // 创建并加载 LLM 模型会话，返回 true 表示加载成功
    fun createModelSession(path: String): Boolean {
        _isModelLoading.value = true
        _isModelLoaded.value = false
        destroyModelSession()
        return try {
            sessionPtr = NativeLib.createSession(path)
            Logger.d("Global session created with ptr=$sessionPtr for path=$path")
            val loaded = NativeLib.loadModel(sessionPtr)
            if (loaded) {
                _isModelLoaded.value = true
                _currentModelPath.value = path
                Logger.d("Global model loaded successfully: $path")
            } else {
                Logger.e("Failed to load model: $path")
                NativeLib.destroySession(sessionPtr)
                sessionPtr = 0L
            }
            loaded
        } catch (e: Exception) {
            Logger.e("Exception creating model session", e)
            false
        } finally {
            _isModelLoading.value = false
        }
    }

    // 销毁当前 LLM 模型会话
    fun destroyModelSession() {
        if (sessionPtr != 0L) {
            NativeLib.destroySession(sessionPtr)
            Logger.d("Global session destroyed, ptr was $sessionPtr")
            sessionPtr = 0L
        }
        _isModelLoaded.value = false
    }

    // 异步切换模型（不阻塞 UI）
    fun reloadModel(path: String) {
        appScope.launch {
            val success = createModelSession(path)
            if (success) {
                _modelReloadEvents.tryEmit(path)
            }
        }
    }

    // 通知观察者模型已切换（外部调用时使用，无需重新加载）
    fun notifyModelReload(path: String) {
        _modelReloadEvents.tryEmit(path)
    }

    // ── Embedding model management ──────────────────────────────────

    // 加载 Embedding 模型，返回 true 表示成功
    fun loadEmbeddingModel(modelPath: String): Boolean {
        android.util.Log.d("LLMApp_DIAG", "loadEmbeddingModel called, path=$modelPath")
        destroyEmbeddingSession()
        return try {
            android.util.Log.d("LLMApp_DIAG", "Calling createEmbeddingSession...")
            embeddingSessionPtr = NativeLib.createEmbeddingSession(modelPath)
            android.util.Log.d("LLMApp_DIAG", "createEmbeddingSession returned ptr=$embeddingSessionPtr")
            android.util.Log.d("LLMApp_DIAG", "Calling loadEmbeddingModel JNI...")
            val loaded = NativeLib.loadEmbeddingModel(embeddingSessionPtr)
            android.util.Log.d("LLMApp_DIAG", "loadEmbeddingModel JNI returned: $loaded")
            if (loaded) {
                _isEmbeddingModelLoaded.value = true
                Logger.d("Embedding model loaded: $modelPath")
            } else {
                NativeLib.destroyEmbeddingSession(embeddingSessionPtr)
                embeddingSessionPtr = 0L
            }
            loaded
        } catch (e: Exception) {
            android.util.Log.e("LLMApp_DIAG", "Exception in loadEmbeddingModel", e)
            Logger.e("Exception loading embedding model", e)
            false
        }
    }

    // 销毁 Embedding 模型会话
    fun destroyEmbeddingSession() {
        if (embeddingSessionPtr != 0L) {
            NativeLib.destroyEmbeddingSession(embeddingSessionPtr)
            embeddingSessionPtr = 0L
        }
        _isEmbeddingModelLoaded.value = false
    }

    // 异步重新加载 Embedding 模型并保存路径
    fun reloadEmbeddingModel(path: String) {
        appScope.launch {
            if (loadEmbeddingModel(path)) {
                modelRepository.saveEmbeddingModelPath(path)
            }
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        destroyModelSession()
        destroyEmbeddingSession()
    }
}
