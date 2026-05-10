// PDF 解析器：使用 pdfbox-android 库提取文本
package com.llmapp.data.rag

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

class PdfParser : DocumentParser {
    // PDF 解析较耗时，切到 IO 线程执行
    override suspend fun parse(inputStream: InputStream): String = withContext(Dispatchers.IO) {
        inputStream.use { stream ->
            PDDocument.load(stream).use { doc ->
                val stripper = PDFTextStripper()
                stripper.getText(doc)
            }
        }
    }
}
