package com.llmapp.ui.chat

import android.Manifest
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.llmapp.LLMApplication
import com.llmapp.R
import com.llmapp.asr.VoskSpeechRecognizer
import com.llmapp.data.repository.TemplateRepository
import com.llmapp.utils.ImageFileUtils
import com.llmapp.utils.Logger
import com.llmapp.utils.PermissionHelper

// Fragment 宿主，承载 Compose 聊天界面并管理语音/相机/相册等系统交互
class ChatFragment : Fragment() {
    private val viewModel: ChatViewModel by viewModels()
    private val templateRepository by lazy { TemplateRepository(requireContext()) }
    private var speechRecognizer: VoskSpeechRecognizer? = null

    // Direct references set during composition
    private var app: LLMApplication? = null
    private var setIsListening: ((Boolean) -> Unit)? = null
    private var setVoicePartialText: ((String?) -> Unit)? = null
    private var setIsVoiceInitializing: ((Boolean) -> Unit)? = null

    private var isMicPressed = false

    private var photoUri: Uri? = null
    private var photoFilePath: String? = null

    // 拍照 ActivityResult 回调
    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && photoFilePath != null) {
            Logger.d("Photo taken: $photoFilePath")
            viewModel.setSelectedImagePath(photoFilePath)
        }
    }

    // 相册选择 ActivityResult 回调
    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val path = ImageFileUtils.copyUriToCache(requireContext(), it)
            if (path != null) {
                Logger.d("Image selected from gallery: $path")
                viewModel.setSelectedImagePath(path)
            } else {
                Toast.makeText(requireContext(), R.string.failed_to_load_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 存储权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(requireContext(), R.string.error_storage_permission, Toast.LENGTH_SHORT).show()
        }
    }

    // 麦克风权限请求
    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecognition()
        } else {
            Toast.makeText(requireContext(), R.string.voice_error_permission, Toast.LENGTH_SHORT).show()
        }
    }

    // --- Voice control methods ---

    // 启动语音识别：初始化 Vosk 或直接开始监听
    private fun startVoiceRecognition() {
        val sr = speechRecognizer ?: return
        if (sr.state == VoskSpeechRecognizer.State.IDLE || sr.state == VoskSpeechRecognizer.State.ERROR) {
            setIsVoiceInitializing?.invoke(true)
            sr.initialize()
            sr.onStateChanged = { state ->
                setIsVoiceInitializing?.invoke(state == VoskSpeechRecognizer.State.INITIALIZING)
                if (state == VoskSpeechRecognizer.State.READY) {
                    if (isMicPressed) {
                        setVoicePartialText?.invoke(null)
                        sr.startListening()
                        setIsListening?.invoke(true)
                    }
                } else if (state == VoskSpeechRecognizer.State.ERROR) {
                    setIsVoiceInitializing?.invoke(false)
                }
            }
        } else if (sr.state == VoskSpeechRecognizer.State.READY) {
            setVoicePartialText?.invoke(null)
            sr.startListening()
            setIsListening?.invoke(true)
        }
    }

    // 停止语音识别，将识别文本发送到输入框
    private fun stopVoiceRecognition() {
        setIsListening?.invoke(false)
        val result = speechRecognizer?.stopListening()
        setVoicePartialText?.invoke(null)
        Logger.d("Voice stop, result='${result}'")
        if (!result.isNullOrBlank()) {
            app?.emitTemplateSelected(result)
        }
    }

    // 处理麦克风按下：检查权限后启动识别
    private fun handleMicPress() {
        if (!PermissionHelper.hasAudioPermission(requireContext())) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        isMicPressed = true
        startVoiceRecognition()
    }

    // 处理麦克风释放：停止识别
    private fun handleMicRelease() {
        isMicPressed = false
        if (speechRecognizer?.state == VoskSpeechRecognizer.State.RECORDING) {
            stopVoiceRecognition()
        }
    }

    // --- Lifecycle ---

    // 创建 ComposeView 作为 Fragment 视图，组装完整聊天界面
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        val recognizer = VoskSpeechRecognizer(requireContext()).also { speechRecognizer = it }

        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val appRef = context.applicationContext as LLMApplication

                    // Capture references for use in fragment-level methods
                    app = appRef

                    val isLoaded by appRef.isModelLoaded.collectAsState()
                    var wasLoaded by remember { mutableStateOf(false) }

                    // 模型首次加载完成后弹出 Toast 提示
                    if (isLoaded && !wasLoaded) {
                        LaunchedEffect(isLoaded) {
                            val name = appRef.currentModelPath.value?.substringAfterLast('/') ?: ""
                            Toast.makeText(context,
                                context.getString(R.string.model_loaded_toast, name),
                                Toast.LENGTH_SHORT).show()
                        }
                    }
                    SideEffect { wasLoaded = isLoaded }

                    val uiState = viewModel.uiState.collectAsStateWithLifecycle()
                    val templateState = templateRepository.getAllTemplates()
                        .collectAsState(initial = emptyList())

                    // Voice state
                    var isListening by remember { mutableStateOf(false) }
                    var voicePartialText by remember { mutableStateOf<String?>(null) }
                    var isVoiceInitializing by remember { mutableStateOf(false) }

                    // Wire state setters so fragment methods can update compose state
                    SideEffect {
                        setIsListening = { isListening = it }
                        setVoicePartialText = { voicePartialText = it }
                        setIsVoiceInitializing = { isVoiceInitializing = it }
                    }

                    // Wire recognizer callbacks
                    DisposableEffect(recognizer) {
                        recognizer.onPartialResult = { text -> voicePartialText = text }
                        recognizer.onError = { error ->
                            isListening = false
                            voicePartialText = null
                            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
                        }
                        onDispose { }
                    }

                    ChatScreen(
                        viewModel = viewModel,
                        isModelLoaded = isLoaded,
                        onTakePhoto = { handleTakePhoto() },
                        onPickImage = { handlePickImage() },
                        selectedImagePath = uiState.value.selectedImagePath,
                        onClearImage = { viewModel.clearSelectedImage() },
                        templateChips = templateState.value.take(5).filter { it.isBuiltIn },
                        onTemplateChipClick = { template ->
                            appRef.emitTemplateSelected(template.promptText)
                        },
                        isListening = isListening,
                        onMicPress = { handleMicPress() },
                        onMicRelease = { handleMicRelease() },
                        voicePartialText = voicePartialText,
                        isVoiceInitializing = isVoiceInitializing
                    )
                }
            }
        }
    }

    // 处理拍照按钮：检查权限后启动系统相机
    private fun handleTakePhoto() {
        if (!PermissionHelper.hasPermissions(requireContext())) {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
            return
        }
        val photoFile = ImageFileUtils.createImageFile(requireContext())
        photoFilePath = photoFile.absolutePath
        photoUri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            photoFile
        )
        cameraLauncher.launch(photoUri)
    }

    // 处理选择图片按钮：检查权限后启动系统相册
    private fun handlePickImage() {
        if (!PermissionHelper.hasPermissions(requireContext())) {
            permissionLauncher.launch(PermissionHelper.getRequiredPermissions())
            return
        }
        galleryLauncher.launch("image/*")
    }

    // 释放 Fragment 持有的所有引用
    override fun onDestroyView() {
        super.onDestroyView()
        speechRecognizer?.release()
        speechRecognizer = null
        app = null
        setIsListening = null
        setVoicePartialText = null
        setIsVoiceInitializing = null
    }
}
