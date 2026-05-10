package com.llmapp.data.rag

import org.junit.Assert.*
import org.junit.Test

// 测试 RagRepository 中的工具方法（纯 Kotlin，无需 Android 环境）
class RagRepositoryTest {

    @Test
    fun `cosineSimilarity identical vectors returns 1`() {
        val vec = floatArrayOf(1f, 2f, 3f)
        val result = RagRepository.cosineSimilarity(vec, vec)
        assertEquals(1f, result, 0.0001f)
    }

    @Test
    fun `cosineSimilarity orthogonal vectors returns 0`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0f, 1f)
        val result = RagRepository.cosineSimilarity(a, b)
        assertEquals(0f, result, 0.0001f)
    }

    @Test
    fun `cosineSimilarity opposite vectors returns -1`() {
        val a = floatArrayOf(1f, 1f)
        val b = floatArrayOf(-1f, -1f)
        val result = RagRepository.cosineSimilarity(a, b)
        assertEquals(-1f, result, 0.0001f)
    }

    @Test
    fun `cosineSimilarity zero vector returns 0`() {
        val a = floatArrayOf(0f, 0f, 0f)
        val b = floatArrayOf(1f, 2f, 3f)
        val result = RagRepository.cosineSimilarity(a, b)
        assertEquals(0f, result, 0.0001f)
    }

    @Test
    fun `floatArray to bytes roundtrip`() {
        val original = floatArrayOf(0.1f, -0.5f, 3.14f, 0f, 1f)
        val bytes = RagRepository.floatArrayToBytes(original)
        val restored = RagRepository.bytesToFloatArray(bytes)
        assertNotNull(restored)
        assertArrayEquals(original, restored!!, 0.0001f)
    }

    @Test
    fun `bytesToFloatArray invalid length returns null`() {
        val bytes = byteArrayOf(1, 2, 3) // 3 bytes not divisible by 4
        assertNull(RagRepository.bytesToFloatArray(bytes))
    }

    @Test
    fun `bytesToFloatArray empty returns empty`() {
        val result = RagRepository.bytesToFloatArray(byteArrayOf())
        assertNotNull(result)
        assertEquals(0, result!!.size)
    }

    @Test
    fun `floatArrayToBytes produces little-endian`() {
        val floats = floatArrayOf(1f)
        val bytes = RagRepository.floatArrayToBytes(floats)
        assertEquals(4, bytes.size)
        // IEEE 754: 1.0f = 0x3F800000, little-endian: 00 00 80 3F
        assertEquals(0, bytes[0].toInt() and 0xFF)
        assertEquals(0, bytes[1].toInt() and 0xFF)
        assertEquals(0x80.toByte(), bytes[2])
        assertEquals(0x3F, bytes[3].toInt() and 0xFF)
    }
}
