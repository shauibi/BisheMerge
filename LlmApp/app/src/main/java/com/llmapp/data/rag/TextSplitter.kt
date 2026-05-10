// 文本分片器：按段落/句子切割文本并合并带重叠的块，用于 RAG 文档切片
package com.llmapp.data.rag

class TextSplitter(
    private val chunkSize: Int = 512,
    private val chunkOverlap: Int = 64
) {
    init {
        require(chunkSize > chunkOverlap) { "chunkSize must exceed chunkOverlap" }
    }

    // 主入口：清洗文本后先按段落切，超长段落再按句子切，最后合并重叠
    fun split(text: String): List<String> {
        val cleaned = text.replace(Regex("\\s+"), " ").trim()
        if (cleaned.isEmpty()) return emptyList()
        if (cleaned.length <= chunkSize) return listOf(cleaned)

        val paragraphs = splitByParagraphs(cleaned)
        val chunks = mutableListOf<String>()

        for (para in paragraphs) {
            if (para.length <= chunkSize) {
                chunks.add(para)
            } else {
                chunks.addAll(splitBySentences(para))
            }
        }

        return mergeWithOverlap(chunks)
    }

    // 按连续换行分割段落
    private fun splitByParagraphs(text: String): List<String> =
        text.split(Regex("(?<=\\S)[\\r\\n]{2,}(?=\\S)")).filter { it.isNotBlank() }

    // 超长段落按句子切割，在 chunkSize 附近找最佳断点
    private fun splitBySentences(text: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.length > chunkSize) {
            val cut = findBestSplit(remaining, chunkSize)
            result.add(remaining.substring(0, cut).trim())
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }

    // 在文本后 40% 区域内依次寻找句号、逗号、空格作为拆分点
    private fun findBestSplit(text: String, maxLen: Int): Int {
        val searchStart = maxLen * 3 / 5
        // 优先找句号结尾
        val sentenceEnd = Regex("[。！？.!?]\\s*")
        val sentenceMatch = sentenceEnd.find(text, searchStart)
        if (sentenceMatch != null && sentenceMatch.range.last < maxLen) {
            return sentenceMatch.range.last + 1
        }
        // 其次找逗号/分号
        val clauseEnd = Regex("[，,；;]\\s*")
        val clauseMatch = clauseEnd.find(text, searchStart)
        if (clauseMatch != null && clauseMatch.range.last < maxLen) {
            return clauseMatch.range.last + 1
        }
        // 最后按空格切分
        val spaceIdx = text.lastIndexOf(' ', maxLen)
        if (spaceIdx > searchStart) return spaceIdx + 1

        return maxLen
    }

    // 相邻 chunk 尾部附加重叠文本，保持上下文连贯
    private fun mergeWithOverlap(chunks: List<String>): List<String> {
        if (chunks.size <= 1 || chunkOverlap <= 0) return chunks
        val result = mutableListOf(chunks[0])
        for (i in 1 until chunks.size) {
            val prev = result.last()
            if (prev.length <= chunkOverlap) {
                result.add(chunks[i])
                continue
            }
            val overlap = prev.takeLast(chunkOverlap)
            result.add(overlap + chunks[i])
        }
        return result
    }
}
