package com.llmapp.ui.knowledge

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels

// 知识库管理页面 Fragment：文档导入 + Embedding 模型加载
class KnowledgeFragment : Fragment() {
    private val viewModel: KnowledgeViewModel by viewModels()

    // SAF 文档选择器（txt / md / pdf / docx）
    private val documentPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importDocument(it) }
    }

    // SAF 目录选择器：选择 Embedding 模型目录
    private val embeddingDirPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { treeUri ->
            // 持久化 URI 权限，重启后仍可访问
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            requireContext().contentResolver.takePersistableUriPermission(treeUri, flags)

            val realPath = resolveTreeUriToPath(treeUri)
            val displayPath = realPath ?: treeUri.toString()

            // 将模型文件从 SAF 树复制到内部存储（绕过 Scoped Storage）
            val internalPath = copyModelFromTree(requireContext(), treeUri)
            viewModel.loadEmbeddingModel(
                path = internalPath ?: displayPath,
                safUri = if (internalPath == null) treeUri else null
            )
        }
    }

    // 将 SAF 树 URI 转换为真实文件系统路径
    // 如 content://...primary%3Asdcard%2Fmodels -> /sdcard/models
    private fun resolveTreeUriToPath(uri: Uri): String? {
        val docId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        val colon = docId.indexOf(':')
        if (colon < 0) return null
        val volume = docId.substring(0, colon)
        val relativePath = docId.substring(colon + 1)
        if (volume == "primary") {
            return "${Environment.getExternalStorageDirectory().absolutePath}/$relativePath"
        }
        return "/storage/$volume/$relativePath"
    }

    // 从 SAF 树 URI 复制 Embedding 模型文件到应用内部存储，返回目录路径或 null
    private fun copyModelFromTree(context: Context, treeUri: Uri): String? {
        val destDir = java.io.File(context.filesDir, "embedding_model")
        if (destDir.exists()) destDir.deleteRecursively()
        destDir.mkdirs()

        val cr = context.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE
        )

        val cursor = cr.query(childrenUri, columns, null, null, null) ?: return null
        cursor.use { cur ->
            while (cur.moveToNext()) {
                val docId = cur.getString(0)
                val name = cur.getString(1)
                val size = cur.getLong(2)
                val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                android.util.Log.d("LLMApp_DIAG", "SAF copy: $name ($size bytes)")
                try {
                    cr.openInputStream(fileUri)?.use { input ->
                        java.io.File(destDir, name).outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("LLMApp_DIAG", "Failed to copy $name: ${e.message}")
                }
            }
        }

        // 验证 config.json 是否复制成功
        val configFile = java.io.File(destDir, "config.json")
        return if (configFile.exists()) destDir.absolutePath else null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    KnowledgeScreen(
                        viewModel = viewModel,
                        onImportClick = {
                            documentPicker.launch(arrayOf(
                                "text/plain",
                                "text/markdown",
                                "application/pdf",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                            ))
                        },
                        onSelectEmbeddingModel = {
                            embeddingDirPicker.launch(null)
                        }
                    )
                }
            }
        }
    }
}
