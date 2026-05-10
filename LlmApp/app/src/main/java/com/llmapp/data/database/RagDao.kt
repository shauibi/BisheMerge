// RAG 数据访问对象，提供文档和分块的 Room DAO 接口
package com.llmapp.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RagDao {
    // 获取所有文档，按导入时间降序
    @Query("SELECT * FROM documents ORDER BY importTime DESC")
    fun getAllDocuments(): Flow<List<DocumentEntity>>

    // 获取指定文档下的所有分块
    @Query("SELECT * FROM chunks WHERE documentId = :docId ORDER BY chunkIndex ASC")
    suspend fun getChunksByDocument(docId: Long): List<ChunkEntity>

    // 获取全部分块（用于向量搜索时全量比对）
    @Query("SELECT * FROM chunks")
    suspend fun getAllChunks(): List<ChunkEntity>

    @Insert
    suspend fun insertDocument(doc: DocumentEntity): Long

    @Update
    suspend fun updateDocument(doc: DocumentEntity)

    @Query("DELETE FROM documents WHERE id = :docId")
    suspend fun deleteDocumentById(docId: Long)

    @Query("DELETE FROM chunks WHERE documentId = :docId")
    suspend fun deleteChunksByDocument(docId: Long)

    // 批量插入分块
    @Insert
    suspend fun insertChunks(chunks: List<ChunkEntity>)

    // 更新文档导入状态和分块数量
    @Query("UPDATE documents SET status = :status, chunkCount = :count WHERE id = :docId")
    suspend fun updateDocumentStatus(docId: Long, status: String, count: Int)

    @Query("SELECT * FROM documents WHERE id = :docId")
    suspend fun getDocumentById(docId: Long): DocumentEntity?
}
