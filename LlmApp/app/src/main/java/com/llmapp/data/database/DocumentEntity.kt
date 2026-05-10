// 文档实体，对应 documents 表，记录导入的知识库文档
package com.llmapp.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val fileType: String,            // txt / md / pdf / docx
    val fileSize: Long = 0,
    val importTime: Long = System.currentTimeMillis(),
    val chunkCount: Int = 0,
    val status: String = STATUS_IMPORTING  // importing / ready / error
) {
    companion object {
        const val STATUS_IMPORTING = "importing"
        const val STATUS_READY = "ready"
        const val STATUS_ERROR = "error"
    }
}
