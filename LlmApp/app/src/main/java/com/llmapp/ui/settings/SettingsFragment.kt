package com.llmapp.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.llmapp.R
import com.llmapp.utils.Logger
import java.io.File

// 模型设置页面 Fragment，使用 ComposeView 承载设置界面
class SettingsFragment : Fragment() {
    private val viewModel: SettingsViewModel by viewModels()

    // SAF 目录选择器：选择模型目录后复制到应用私有目录
    private val directoryPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            requireContext().contentResolver.takePersistableUriPermission(
                it,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            val dirName = androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), it)?.name
                ?: "model_${System.currentTimeMillis()}"
            val destDir = File(requireContext().filesDir, "models/$dirName")
            viewModel.copyModelFromUri(it, destDir)
            Toast.makeText(requireContext(), R.string.adding_model_directory, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    SettingsScreen(
                        viewModel = viewModel,
                        onAddModelDir = {
                            directoryPicker.launch(null)
                        }
                    )
                }
            }
        }
    }
}
