// 提示词模板数据类，区分内置模板和用户自定义模板
package com.llmapp.data.model

data class PromptTemplate(
    val id: String,
    val name: String,
    val promptText: String,
    val isBuiltIn: Boolean = true   // true 为内置模板，false 为用户自定义
)
