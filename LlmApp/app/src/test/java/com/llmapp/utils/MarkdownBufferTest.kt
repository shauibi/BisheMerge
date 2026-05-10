package com.llmapp.utils

import org.junit.Assert.*
import org.junit.Test

// 测试 MarkdownBuffer 的流式渲染安全清理
class MarkdownBufferTest {

    private val buffer = MarkdownBuffer()

    @Test
    fun `normal text passes through unchanged`() {
        val text = "Hello, this is a normal response."
        assertEquals(text, buffer.getDisplayText(text))
    }

    @Test
    fun `unclosed code block is truncated`() {
        val text = "Here is code:\n```kotlin\nfun hello() {\n    println(\"hi\")"
        val result = buffer.getDisplayText(text)
        assertFalse(result.contains("```kotlin"))
        assertTrue(result.contains("Here is code"))
    }

    @Test
    fun `closed code block is preserved`() {
        val text = "```kotlin\nfun hello() = \"hi\"\n```\n\nDone."
        val result = buffer.getDisplayText(text)
        assertTrue(result.contains("```kotlin"))
        assertTrue(result.contains("```"))
    }

    @Test
    fun `unclosed bold is truncated`() {
        val text = "This is **bold text"
        val result = buffer.getDisplayText(text)
        assertFalse(result.contains("**bold"))
        assertTrue(result.contains("This is "))
    }

    @Test
    fun `closed bold is preserved`() {
        val text = "This is **bold text** here."
        val result = buffer.getDisplayText(text)
        assertTrue(result.contains("**bold text**"))
    }

    @Test
    fun `unclosed inline code is truncated`() {
        val text = "Use `var x = 1 to declare"
        val result = buffer.getDisplayText(text)
        assertFalse(result.contains("`"))
        assertTrue(result.contains("Use "))
    }

    @Test
    fun `multiple unclosed markdown elements`() {
        val text = "## Title\n\nHere is **bold and `code"
        val result = buffer.getDisplayText(text)
        // heading preserved, code truncated, bold may or may not be
        assertTrue(result.contains("## Title"))
        assertFalse(result.contains("`code"))
    }

    @Test
    fun `empty text returns empty`() {
        assertEquals("", buffer.getDisplayText(""))
    }

    @Test
    fun `unclosed link bracket is truncated`() {
        val text = "Click [here to learn more"
        val result = buffer.getDisplayText(text)
        assertFalse(result.contains("[here"))
        assertTrue(result.contains("Click "))
    }

    @Test
    fun `closed link is preserved`() {
        val text = "Click [here](https://example.com) for more."
        val result = buffer.getDisplayText(text)
        assertTrue(result.contains("[here](https://example.com)"))
    }
}
