// 纯文本解析器：直接读取 InputStream 全部内容为字符串
package com.llmapp.data.rag

import java.io.InputStream

class TextParser : DocumentParser {
    override suspend fun parse(inputStream: InputStream): String {
        return inputStream.bufferedReader().use { it.readText() }
    }
}
