// 文档解析器工厂：根据文件扩展名返回对应的解析器实例
package com.llmapp.data.rag

import java.io.InputStream

class DocumentParserFactory {
    fun createParser(fileName: String): DocumentParser = when {
        fileName.endsWith(".txt", true) -> TextParser()
        fileName.endsWith(".md", true) -> MarkdownParser()
        fileName.endsWith(".pdf", true) -> PdfParser()
        fileName.endsWith(".docx", true) -> DocxParser()
        else -> throw IllegalArgumentException("Unsupported file type: $fileName")
    }
}
