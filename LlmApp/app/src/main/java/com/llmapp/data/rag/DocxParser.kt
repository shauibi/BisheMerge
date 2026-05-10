// DOCX 解析器：解压 ZIP 后解析 word/document.xml 提取段落文本
package com.llmapp.data.rag

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.util.zip.ZipInputStream

class DocxParser : DocumentParser {
    override suspend fun parse(inputStream: InputStream): String = withContext(Dispatchers.IO) {
        val text = StringBuilder()
        inputStream.use { rawStream ->
            ZipInputStream(rawStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        parseDocumentXml(zip, text)
                        break
                    }
                    entry = zip.nextEntry
                }
            }
        }
        text.toString().trim()
    }

    // 使用 XmlPullParser 解析 <w:p>/<w:t> 标签提取文本和段落
    private fun parseDocumentXml(inputStream: InputStream, output: StringBuilder) {
        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(inputStream, "UTF-8")

        var event = parser.eventType
        var inParagraph = false
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "p" -> inParagraph = true
                        "t" -> {
                            event = parser.next()
                            if (event == XmlPullParser.TEXT) {
                                output.append(parser.text)
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "p" && inParagraph) {
                        output.append('\n')
                        inParagraph = false
                    }
                }
            }
            event = parser.next()
        }
    }
}
