package com.llmapp.ui.settings

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmapp.LLMApplication
import com.llmapp.jni.NativeLib
import com.llmapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// 模型设置页 ViewModel：管理模型目录发现、添加、选择与切换
class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LLMApplication
    private val modelRepository = app.modelRepository

    // 已添加的模型目录列表
    private val _modelDirectories = MutableStateFlow<Set<String>>(emptySet())
    val modelDirectories: StateFlow<Set<String>> = _modelDirectories.asStateFlow()

    // 已发现的可用模型（目录名 → 绝对路径）
    private val _availableModels = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val availableModels: StateFlow<List<Pair<String, String>>> = _availableModels.asStateFlow()

    // 当前选中的模型路径
    private val _selectedModelPath = MutableStateFlow<String?>(null)
    val selectedModelPath: StateFlow<String?> = _selectedModelPath.asStateFlow()

    // 模型加载状态
    val isModelLoaded: StateFlow<Boolean> = app.isModelLoaded
    // 模型加载中
    val isModelLoading: StateFlow<Boolean> = app.isModelLoading

    // 本地库版本号
    private val _nativeVersion = MutableStateFlow("N/A")
    val nativeVersion: StateFlow<String> = _nativeVersion.asStateFlow()

    init {
        loadSettings()
        _nativeVersion.value = try { NativeLib.getNativeVersion() } catch (e: Exception) { "N/A" }
    }

    // 初始化时从 DataStore 读取已保存的目录和选中的模型
    private fun loadSettings() {
        viewModelScope.launch {
            modelRepository.getModelDirectories().collect { dirs ->
                _modelDirectories.value = dirs
                refreshModelList(dirs)
            }
        }
        viewModelScope.launch {
            modelRepository.getSelectedModelPath().collect { path ->
                _selectedModelPath.value = path
            }
        }
    }

    // 从所有已添加的目录中扫描包含 config.json 的子目录，目录名即为模型名称
    private fun refreshModelList(dirs: Set<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val models = mutableListOf<Pair<String, String>>()
            for (dir in dirs) {
                val dirFile = File(dir)
                if (!dirFile.exists() || !dirFile.isDirectory) continue
                // 扫描一级子目录，查找包含 config.json 的
                dirFile.listFiles()?.filter { it.isDirectory }?.forEach { subDir ->
                    val configFile = File(subDir, "config.json")
                    if (configFile.exists()) {
                        models.add(subDir.name to subDir.absolutePath)
                    }
                }
                // 也检查当前目录本身是否包含 config.json
                val configFile = File(dirFile, "config.json")
                if (configFile.exists() && models.none { it.second == dirFile.absolutePath }) {
                    models.add(dirFile.name to dirFile.absolutePath)
                }
            }
            withContext(Dispatchers.Main) {
                _availableModels.value = models
            }
        }
    }

    // 从 SAF URI 复制模型文件到应用私有目录，并将目录加入列表
    fun copyModelFromUri(uri: Uri, destDir: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val context = getApplication<Application>()

                // 清空目标目录并重新创建
                if (destDir.exists()) destDir.deleteRecursively()
                destDir.mkdirs()

                val sourceDir = DocumentFile.fromTreeUri(context, uri)
                if (sourceDir == null) {
                    Logger.e("Failed to access source directory")
                    return@launch
                }

                Logger.d("Copying model files from URI to: ${destDir.absolutePath}")
                for (file in sourceDir.listFiles()) {
                    if (file.isFile && file.name != null) {
                        val destFile = File(destDir, file.name!!)
                        context.contentResolver.openInputStream(file.uri)?.use { input ->
                            destFile.outputStream().use { output -> input.copyTo(output) }
                        }
                        Logger.d("Copied: ${file.name}")
                    }
                }

                // 将新目录添加到模型目录列表
                val newPath = destDir.absolutePath
                modelRepository.addModelDirectory(newPath)
                Logger.d("Model directory added: $newPath")
            } catch (e: Exception) {
                Logger.e("Failed to copy model", e)
            }
        }
    }

    // 选择模型：保存路径到 DataStore 并通知全局 Application 切换模型
    fun setSelectedModel(modelPath: String) {
        viewModelScope.launch {
            modelRepository.saveSelectedModelPath(modelPath)
            app.reloadModel(modelPath)
        }
    }

    fun getNativeVersion(): String {
        return _nativeVersion.value
    }

    // ── Benchmark ────────────────────────────────────────────────────

    private val _benchmarkStatus = MutableStateFlow("")
    val benchmarkStatus: StateFlow<String> = _benchmarkStatus.asStateFlow()

    private val _benchmarkRunning = MutableStateFlow(false)
    val benchmarkRunning: StateFlow<Boolean> = _benchmarkRunning.asStateFlow()

    private val _benchmarkReportA = MutableStateFlow<com.llmapp.benchmark.FullBenchmarkReport?>(null)
    val benchmarkReportA: StateFlow<com.llmapp.benchmark.FullBenchmarkReport?> = _benchmarkReportA.asStateFlow()

    private val _benchmarkReportB = MutableStateFlow<com.llmapp.benchmark.FullBenchmarkReport?>(null)
    val benchmarkReportB: StateFlow<com.llmapp.benchmark.FullBenchmarkReport?> = _benchmarkReportB.asStateFlow()

    private val _benchmarkProgress = MutableStateFlow(0f)
    val benchmarkProgress: StateFlow<Float> = _benchmarkProgress.asStateFlow()

    fun runBenchmark(modelPathNoEagle: String, modelPathEagle: String) {
        if (_benchmarkRunning.value) return
        _benchmarkRunning.value = true
        _benchmarkProgress.value = 0f
        _benchmarkStatus.value = "Starting benchmark..."
        _benchmarkReportA.value = null
        _benchmarkReportB.value = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val runner = com.llmapp.benchmark.BenchmarkRunner(app)
                val configA = com.llmapp.benchmark.BenchConfig(
                    label = "NoEagle",
                    modelPath = modelPathNoEagle,
                    eagleEnabled = false
                )
                val configB = com.llmapp.benchmark.BenchConfig(
                    label = "Eagle",
                    modelPath = modelPathEagle,
                    eagleEnabled = true
                )
                var roundsDone = 0
                var reportA: com.llmapp.benchmark.FullBenchmarkReport? = null
                var reportB: com.llmapp.benchmark.FullBenchmarkReport? = null
                val (ra, rb) = runner.run(configA, configB,
                    onProgress = { progress ->
                        _benchmarkStatus.value = progress
                        if ("TTFT=" in progress) {
                            roundsDone++
                            _benchmarkProgress.value = roundsDone / 24f
                        }
                    },
                    onConfigComplete = { report ->
                        if (report.config.label == "NoEagle") {
                            reportA = report
                            _benchmarkReportA.value = report
                        } else {
                            reportB = report
                            _benchmarkReportB.value = report
                        }
                    }
                )
                if (reportA == null) _benchmarkReportA.value = ra
                if (reportB == null) _benchmarkReportB.value = rb
                _benchmarkProgress.value = 1f
                _benchmarkStatus.value = "Benchmark complete!"
            } catch (e: Exception) {
                _benchmarkStatus.value = "Benchmark failed: ${e.message}"
            } finally {
                _benchmarkRunning.value = false
            }
        }
    }
}
