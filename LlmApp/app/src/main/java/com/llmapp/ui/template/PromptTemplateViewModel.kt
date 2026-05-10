package com.llmapp.ui.template

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.llmapp.LLMApplication
import com.llmapp.data.model.PromptTemplate
import com.llmapp.data.repository.TemplateRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// 提示词模板页 ViewModel：管理内置和自定义模板的增删与选择
class PromptTemplateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LLMApplication
    private val repository = TemplateRepository(application)

    private val _templates = MutableStateFlow<List<PromptTemplate>>(emptyList())
    val templates: StateFlow<List<PromptTemplate>> = _templates.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getAllTemplates().collect { _templates.value = it }
        }
    }

    // 选中模板：将模板的提示词文本发送到聊天页
    fun selectTemplate(template: PromptTemplate) {
        app.emitTemplateSelected(template.promptText)
    }

    // 添加自定义模板
    fun addCustomTemplate(name: String, promptText: String) {
        viewModelScope.launch {
            repository.addCustomTemplate(name, promptText)
        }
    }

    // 删除自定义模板
    fun deleteCustomTemplate(id: String) {
        viewModelScope.launch {
            repository.deleteCustomTemplate(id)
        }
    }
}
