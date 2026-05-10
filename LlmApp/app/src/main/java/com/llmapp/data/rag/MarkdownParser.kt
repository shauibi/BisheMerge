// Markdown 解析器：剔除标记语法，仅保留纯文本内容
package com.llmapp.data.rag

import java.io.InputStream

class MarkdownParser : DocumentParser {
    override suspend fun parse(inputStream: InputStream): String {
        val raw = inputStream.bufferedReader().use { it.readText() }
        return raw
            .replace(Regex("#{1,6}\\s*"), "")           // 去除标题标记
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")   // 粗体 → 纯文本
            .replace(Regex("\\*(.+?)\\*"), "$1")          // 斜体 → 纯文本
            .replace(Regex("`{1,3}[^`]*`{1,3}"), "")     // 行内代码
            .replace(Regex("\\[([^]]*)]\\([^)]*\\)"), "$1") // 链接 → 仅显示文字
            .replace(Regex("!\\[[^]]*]\\([^)]*\\)"), "")  // 图片
            .replace(Regex("^[*-]\\s+", RegexOption.MULTILINE), "") // 列表标记
            .replace(Regex("^\\d+\\.\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("^>\\s+", RegexOption.MULTILINE), "")    // 引用标记
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
