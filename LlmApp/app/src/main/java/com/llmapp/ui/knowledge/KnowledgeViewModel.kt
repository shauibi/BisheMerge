package com.llmapp.ui.knowledge

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmapp.LLMApplication
import com.llmapp.data.database.DocumentEntity
import com.llmapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 知识库页 UI 状态
data class KnowledgeUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val isImporting: Boolean = false,
    val importFileName: String? = null,
    val error: String? = null,
    val isEmbeddingModelLoaded: Boolean = false
)

// 知识库页 ViewModel：管理文档列表、导入、删除及 Embedding 模型加载
class KnowledgeViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LLMApplication

    private val _uiState = MutableStateFlow(KnowledgeUiState())
    val uiState: StateFlow<KnowledgeUiState> = _uiState.asStateFlow()

    init {
        // 监听文档列表变化
        viewModelScope.launch {
            app.ragRepository.getAllDocuments().collect { docs ->
                _uiState.value = _uiState.value.copy(documents = docs)
            }
        }
        // 监听 Embedding 模型加载状态
        viewModelScope.launch {
            app.isEmbeddingModelLoaded.collect { loaded ->
                _uiState.value = _uiState.value.copy(isEmbeddingModelLoaded = loaded)
            }
        }
    }

    // 导入文档：通过 RagRepository 解析、切片、向量化并入库
    fun importDocument(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isImporting = true, importFileName = null, error = null)
            val result = app.ragRepository.importDocument(uri, getApplication())
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isImporting = false, importFileName = null)
                },
                onFailure = { e ->
                    Logger.e("Knowledge: import failed", e)
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importFileName = null,
                        error = e.message ?: "Import failed"
                    )
                }
            )
        }
    }

    // 删除文档及其所有切片
    fun deleteDocument(docId: Long) {
        viewModelScope.launch {
            app.ragRepository.deleteDocument(docId)
        }
    }

    // 加载 Embedding 模型：校验路径和 config.json 后委托给 Application
    fun loadEmbeddingModel(path: String, safUri: android.net.Uri? = null) {
        viewModelScope.launch {
            // 前置校验，给出具体错误信息
            if (!path.startsWith("/")) {
                _uiState.value = _uiState.value.copy(error = "Invalid path (not a real filesystem path): $path")
                return@launch
            }
            val configFile = java.io.File(path, "config.json")
            if (!configFile.exists()) {
                _uiState.value = _uiState.value.copy(
                    error = "config.json not found at $path\nPlease select the directory containing config.json"
                )
                return@launch
            }
            val ok = app.loadEmbeddingModel(path)
            if (ok) {
                app.modelRepository.saveEmbeddingModelPath(path)
            } else {
                _uiState.value = _uiState.value.copy(
                    error = "MNN failed to load embedding model from $path\nModel file or format may be invalid"
                )
            }
        }
    }

    // 清除错误状态
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}
