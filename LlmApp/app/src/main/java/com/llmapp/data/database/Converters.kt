// Room 类型转换器，实现 FloatArray 与 ByteArray 的相互转换（小端序）
package com.llmapp.data.database

import androidx.room.TypeConverter
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Converters {
    // FloatArray 转 ByteArray，用于持久化 embedding 向量
    @TypeConverter
    fun fromFloatArrayToByteArray(floats: FloatArray?): ByteArray? {
        if (floats == null) return null
        return ByteBuffer.allocate(floats.size * 4).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            asFloatBuffer().put(floats)
        }.array()
    }

    // ByteArray 还原为 FloatArray
    @TypeConverter
    fun fromByteArrayToFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null) return null
        return ByteBuffer.wrap(bytes).apply {
            order(ByteOrder.LITTLE_ENDIAN)
        }.let { buf ->
            FloatArray(bytes.size / 4) { buf.getFloat() }
        }
    }
}
