package com.llmapp.utils

// Markdown 缓冲区，清理流式生成中未闭合的 Markdown 语法，防止渲染异常
class MarkdownBuffer {

    // 获取安全的显示文本：清理未完成的 Markdown 标记
    fun getDisplayText(fullText: String): String {
        return sanitizeUnfinishedMarkdown(fullText)
    }

    // 依次清理各类未闭合的 Markdown 语法
    private fun sanitizeUnfinishedMarkdown(text: String): String {
        var result = text

        result = sanitizeCodeBlocks(result)
        result = sanitizeInlineCode(result)
        result = sanitizeBold(result)
        result = sanitizeItalic(result)
        result = sanitizeLinks(result)

        return result
    }

    // 清理未闭合的代码块（``` 出现奇数次时截断）
    private fun sanitizeCodeBlocks(text: String): String {
        val codeBlockCount = countOccurrences(text, "```")
        if (codeBlockCount % 2 != 0) {
            val lastBacktick = text.lastIndexOf("```")
            if (lastBacktick >= 0) {
                return text.substring(0, lastBacktick)
            }
        }
        return text
    }

    // 清理未闭合的行内代码（去除代码块后 ` 出现奇数次时截断）
    private fun sanitizeInlineCode(text: String): String {
        val withoutCodeBlocks = text.replace("```", "")
        val inlineCodeCount = countOccurrences(withoutCodeBlocks, "`")
        if (inlineCodeCount % 2 != 0) {
            val lastBacktick = withoutCodeBlocks.lastIndexOf("`")
            val precedingChars = withoutCodeBlocks.substring(0, lastBacktick)
            // 确保不是代码块标记的一部分
            if (!precedingChars.endsWith("``")) {
                return text.substring(0, lastBacktick)
            }
        }
        return text
    }

    // 清理未闭合的粗体标记（** 出现奇数次时截断）
    private fun sanitizeBold(text: String): String {
        val boldCount = countOccurrences(text, "**")
        if (boldCount % 2 != 0) {
            val lastBold = text.lastIndexOf("**")
            if (lastBold > 0) {
                return text.substring(0, lastBold)
            }
        }
        return text
    }

    // 清理未闭合的斜体标记（去除粗体和代码后 * 出现奇数次时截断）
    private fun sanitizeItalic(text: String): String {
        val withoutBold = text.replace("**", "")
        val withoutCode = withoutBold.replace("`", "")
        val italicCount = withoutCode.count { it == '*' }
        if (italicCount % 2 != 0) {
            val lastStar = text.lastIndexOf("*")
            val precedingChar = if (lastStar > 0) text[lastStar - 1] else ' '
            val followingChar = if (lastStar < text.length - 1) text[lastStar + 1] else ' '
            // 确保不是粗体标记的一部分
            if (precedingChar != '*' && followingChar != '*') {
                return text.substring(0, lastStar)
            }
        }
        return text
    }

    // 清理未闭合的链接标记（[ 和 ]( 和 ) 数量不匹配时截断）
    private fun sanitizeLinks(text: String): String {
        val openBrackets = countOccurrences(text, "[")
        val closeWithParen = countOccurrences(text, "](")
        val closeParens = countOccurrences(text, ")")

        return when {
            openBrackets > closeWithParen -> {
                val lastBracket = text.lastIndexOf("[")
                text.substring(0, lastBracket)
            }
            closeWithParen > closeParens -> {
                val lastCloseParen = text.lastIndexOf("](")
                if (lastCloseParen >= 0) text.substring(0, lastCloseParen) else text
            }
            else -> text
        }
    }

    // 统计子串在文本中的出现次数
    private fun countOccurrences(text: String, delimiter: String): Int {
        var count = 0
        var index = 0
        while (index < text.length) {
            val found = text.indexOf(delimiter, index)
            if (found == -1) break
            count++
            index = found + delimiter.length
        }
        return count
    }
}
