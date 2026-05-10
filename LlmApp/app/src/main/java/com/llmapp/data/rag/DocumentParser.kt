// 文档解析器接口：从 InputStream 中提取纯文本
package com.llmapp.data.rag

import java.io.InputStream

interface DocumentParser {
    suspend fun parse(inputStream: InputStream): String
}
