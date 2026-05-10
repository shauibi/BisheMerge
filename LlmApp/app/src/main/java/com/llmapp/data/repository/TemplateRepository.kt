// 提示词模板仓库，管理内置模板和用户自定义模板（DataStore 持久化）
package com.llmapp.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.llmapp.data.model.PromptTemplate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.templateDataStore: DataStore<Preferences> by preferencesDataStore(name = "templates")

class TemplateRepository(context: Context) {
    private val dataStore = context.templateDataStore
    private val CUSTOM_TEMPLATES_KEY = stringPreferencesKey("custom_templates")

    // 内置模板列表
    private val builtInTemplates = listOf(
        PromptTemplate("builtin_explain_code", "代码解释", "请解释以下代码的功能和逻辑：\n"),
        PromptTemplate("builtin_translate_en", "翻译成英文", "请将以下内容翻译成英文：\n"),
        PromptTemplate("builtin_polish", "文本润色", "请润色以下文本，使其更加流畅自然：\n"),
        PromptTemplate("builtin_summarize", "总结要点", "请总结以下内容的要点：\n"),
        PromptTemplate("builtin_write_email", "写邮件", "请根据以下要点撰写一封专业邮件：\n"),
        PromptTemplate("builtin_generate_title", "生成标题", "请为以下内容生成一个简洁的标题：\n"),
        PromptTemplate("builtin_explain_concept", "解释概念", "请用通俗易懂的语言解释以下概念：\n"),
        PromptTemplate("builtin_weekly_report", "写周报", "请根据以下工作内容，撰写一份周报：\n"),
    )

    // 自定义模板的 Flow，从 DataStore 读取 JSON
    private val customTemplatesFlow: Flow<List<PromptTemplate>> = dataStore.data.map { prefs ->
        val json = prefs[CUSTOM_TEMPLATES_KEY] ?: "[]"
        parseTemplates(json)
    }

    // 获取所有模板（内置 + 自定义）
    fun getAllTemplates(): Flow<List<PromptTemplate>> = customTemplatesFlow.map { custom ->
        builtInTemplates + custom
    }

    // 新增自定义模板
    suspend fun addCustomTemplate(name: String, promptText: String) {
        dataStore.edit { prefs ->
            val json = prefs[CUSTOM_TEMPLATES_KEY] ?: "[]"
            val list = parseTemplates(json).toMutableList()
            val newId = "custom_${System.currentTimeMillis()}"
            list.add(PromptTemplate(newId, name, promptText, isBuiltIn = false))
            prefs[CUSTOM_TEMPLATES_KEY] = serializeTemplates(list)
        }
    }

    // 删除自定义模板
    suspend fun deleteCustomTemplate(id: String) {
        dataStore.edit { prefs ->
            val json = prefs[CUSTOM_TEMPLATES_KEY] ?: "[]"
            val list = parseTemplates(json).filter { it.id != id }
            prefs[CUSTOM_TEMPLATES_KEY] = serializeTemplates(list)
        }
    }

    // 解析 JSON 数组为模板列表
    private fun parseTemplates(json: String): List<PromptTemplate> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                PromptTemplate(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    promptText = obj.getString("promptText"),
                    isBuiltIn = false
                )
            }
        } catch (_: Exception) { emptyList() }
    }

    // 模板列表序列化为 JSON 字符串
    private fun serializeTemplates(list: List<PromptTemplate>): String {
        val arr = JSONArray()
        list.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("name", t.name)
                put("promptText", t.promptText)
            })
        }
        return arr.toString()
    }
}
