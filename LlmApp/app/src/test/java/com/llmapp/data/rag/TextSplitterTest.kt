package com.llmapp.data.rag

import org.junit.Assert.*
import org.junit.Test

// 测试文本切片器（纯 Kotlin，无需 Android 环境）
class TextSplitterTest {

    private val splitter = TextSplitter()

    @Test
    fun `short text returns single chunk`() {
        val text = "Hello world"
        val chunks = splitter.split(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0])
    }

    @Test
    fun `empty text returns empty list`() {
        val chunks = splitter.split("")
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `whitespace only returns empty or very small`() {
        val chunks = splitter.split("   \n  ")
        // Should return at most 1 chunk, likely empty
        assertTrue(chunks.isEmpty() || chunks.size == 1)
    }

    @Test
    fun `long text split into multiple chunks`() {
        val sb = StringBuilder()
        repeat(500) { sb.append("This is sentence number $it. ") }
        val chunks = splitter.split(sb.toString())
        assertTrue("Expected > 1 chunk, got ${chunks.size}", chunks.size > 1)
        // Each chunk should be non-empty and below the max size
        for (chunk in chunks) {
            assertTrue(chunk.isNotBlank())
            assertTrue("Chunk too long: ${chunk.length}", chunk.length <= 1024)
        }
    }

    @Test
    fun `chunks preserve text content`() {
        val sb = StringBuilder()
        val sentences = (1..200).map { "Sentence-$it: The quick brown fox jumps over the lazy dog." }
        sentences.forEach { sb.append("$it ") }
        val original = sb.toString()
        val chunks = splitter.split(original)

        // Verify all chunks concatenated contain the key sentences
        val combined = chunks.joinToString("")
        for (s in sentences.take(10)) {
            assertTrue("Missing: $s", combined.contains(s))
        }
    }

    @Test
    fun `chunk size does not exceed max`() {
        val sb = StringBuilder()
        repeat(300) { sb.append("Long text for testing the chunk size limit. ") }
        val chunks = splitter.split(sb.toString())
        for (chunk in chunks) {
            assertTrue("Chunk size ${chunk.length} exceeds limit", chunk.length <= 1024)
        }
    }
}
