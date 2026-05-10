// 文档分块实体，对应 chunks 表，外键关联文档，存储切片文本及其向量
package com.llmapp.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "chunks",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE  // 删除文档时级联删除分块
        )
    ],
    indices = [Index("documentId")]
)
data class ChunkEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val documentId: Long,
    val chunkIndex: Int,             // 分块在文档中的顺序
    val content: String,             // 分块文本
    val embedding: ByteArray? = null,// 小端序 FloatArray 的字节序列
    val tokenCount: Int = 0
)
