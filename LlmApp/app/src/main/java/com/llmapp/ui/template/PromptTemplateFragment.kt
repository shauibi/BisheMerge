package com.llmapp.ui.template

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import com.llmapp.ui.main.MainActivity

// 提示词模板页面 Fragment：选择模板后自动跳转到聊天页
class PromptTemplateFragment : Fragment() {
    private val viewModel: PromptTemplateViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MaterialTheme {
                    TemplateScreen(
                        viewModel = viewModel,
                        onTemplateSelected = {
                            // 选中模板后切换到聊天 Tab 并应用提示词
                            (requireActivity() as? MainActivity)?.switchToChatTab()
                            viewModel.selectTemplate(it)
                        }
                    )
                }
            }
        }
    }
}
