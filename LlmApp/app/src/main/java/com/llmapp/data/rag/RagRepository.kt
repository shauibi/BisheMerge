// RAG 仓库：文档导入流水线（解析→切片→向量化→入库）和向量相似度搜索
package com.llmapp.data.rag

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.llmapp.LLMApplication
import com.llmapp.data.database.ChunkEntity
import com.llmapp.data.database.DocumentEntity
import com.llmapp.data.database.RagDao
import com.llmapp.jni.NativeLib
import com.llmapp.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

// 分块与余弦相似度得分的配对
data class ChunkWithScore(
    val chunk: ChunkEntity,
    val score: Float
)

// 携带文档名称的检索结果
data class ChunkWithDocInfo(
    val chunkContent: String,
    val score: Float,
    val documentName: String
)

class RagRepository(
    private val ragDao: RagDao,
    private val app: LLMApplication
) {
    private val splitter = TextSplitter()
    private val parserFactory = DocumentParserFactory()

    // 获取所有文档列表（Flow 响应式）
    fun getAllDocuments(): Flow<List<DocumentEntity>> = ragDao.getAllDocuments()

    // 导入文档：复制到内部存储 -> 解析 -> 切片 -> embedding -> 入库
    suspend fun importDocument(uri: Uri, context: Context): Result<Long> = withContext(Dispatchers.IO) {
        try {
            // 判断文件类型
            val fileName = getFileName(uri, context) ?: "unknown"
            val extension = fileName.substringAfterLast('.', "").lowercase()
            val fileType = when (extension) {
                "txt" -> "txt"
                "md" -> "md"
                "pdf" -> "pdf"
                "docx" -> "docx"
                else -> return@withContext Result.failure(
                    IllegalArgumentException("Unsupported file type: $extension")
                )
            }

            // SAF URI 无法直接被 C++ 读取，先复制到内部存储
            val internalDir = java.io.File(context.filesDir, "documents")
            internalDir.mkdirs()
            val localFile = java.io.File(internalDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                localFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(Exception("Cannot open file"))

            // 插入文档记录
            val doc = DocumentEntity(
                fileName = fileName,
                fileType = fileType,
                fileSize = localFile.length(),
                status = DocumentEntity.STATUS_IMPORTING
            )
            val docId = ragDao.insertDocument(doc)
            Logger.d("RAG: Document inserted id=$docId, name=$fileName")

            // 解析文档文本
            val parser = parserFactory.createParser(fileName)
            val text = localFile.inputStream().use { parser.parse(it) }
            Logger.d("RAG: Parsed text length=${text.length}")

            // 文本分片
            val chunks = splitter.split(text)
            Logger.d("RAG: Split into ${chunks.size} chunks")

            // 每个分片计算 embedding 并入库
            val chunkEntities = chunks.mapIndexed { index, chunkText ->
                val embedding = computeEmbedding(chunkText)
                ChunkEntity(
                    documentId = docId,
                    chunkIndex = index,
                    content = chunkText,
                    embedding = embedding,
                    tokenCount = chunkText.length
                )
            }

            ragDao.insertChunks(chunkEntities)
            ragDao.updateDocumentStatus(docId, DocumentEntity.STATUS_READY, chunks.size)
            Logger.d("RAG: Document import complete, ${chunks.size} chunks embedded")

            Result.success(docId)
        } catch (e: Exception) {
            Logger.e("RAG: Document import failed", e)
            Result.failure(e)
        }
    }

    // 根据文档 ID 查询文档
    suspend fun getDocumentById(docId: Long): DocumentEntity? {
        return ragDao.getDocumentById(docId)
    }

    // 为检索结果补充文档名称
    suspend fun enrichChunks(results: List<ChunkWithScore>): List<ChunkWithDocInfo> {
        return results.map { item ->
            val doc = ragDao.getDocumentById(item.chunk.documentId)
            ChunkWithDocInfo(
                chunkContent = item.chunk.content,
                score = item.score,
                documentName = doc?.fileName ?: "unknown"
            )
        }
    }

    // 删除文档及其所有分块
    suspend fun deleteDocument(docId: Long) {
        ragDao.deleteChunksByDocument(docId)
        ragDao.deleteDocumentById(docId)
    }

    // 向量相似度搜索：对查询文本编码后与全部分块计算余弦相似度，取 topK
    suspend fun searchSimilar(query: String, topK: Int = 3, minScore: Float = 0.8f): List<ChunkWithScore> = withContext(Dispatchers.IO) {
        val queryEmbeddingBytes = computeEmbedding(query)
        if (queryEmbeddingBytes == null || queryEmbeddingBytes.isEmpty()) {
            Logger.e("RAG: computeEmbedding returned null/empty")
            return@withContext emptyList()
        }
        val queryVec = bytesToFloatArray(queryEmbeddingBytes) ?: return@withContext emptyList()
        Logger.d("RAG: query=\"$query\", vec[0..4]=${queryVec.sliceArray(0..4).joinToString(",") { "%.4f".format(it) }}, norm=%.4f".format(kotlin.math.sqrt(queryVec.fold(0f) { acc, f -> acc + f * f })))

        val chunks = ragDao.getAllChunks()
        if (chunks.isEmpty()) {
            Logger.d("RAG: no chunks in DB")
            return@withContext emptyList()
        }
        Logger.d("RAG: searching ${chunks.size} chunks")

        val allScores = chunks.mapNotNull { chunk ->
            chunk.embedding?.let { emb ->
                val chunkVec = bytesToFloatArray(emb) ?: return@mapNotNull null
                ChunkWithScore(chunk, cosineSimilarity(queryVec, chunkVec))
            }
        }
        .sortedByDescending { it.score }

        val filtered = allScores.filter { it.score >= minScore }
        if (filtered.isNotEmpty()) {
            Logger.d("RAG: top-${minOf(topK, filtered.size)} scores (threshold=$minScore): " +
                filtered.take(topK).joinToString { "%.4f".format(it.score) })
        } else if (allScores.isNotEmpty()) {
            Logger.d("RAG: no chunks above threshold $minScore, best=${"%.4f".format(allScores.first().score)}")
        }

        filtered.take(topK)
    }

    // 调用 JNI 计算文本的 embedding 向量
    private fun computeEmbedding(text: String): ByteArray? {
        val ptr = app.embeddingSessionPtr
        if (ptr == 0L) return null
        return try {
            val floats = NativeLib.computeEmbedding(ptr, text)
            if (floats.isEmpty()) null else floatArrayToBytes(floats)
        } catch (e: Exception) {
            Logger.e("RAG: computeEmbedding failed", e)
            null
        }
    }

    // 通过 ContentResolver 查询文件名，兼容 SAF URI
    private fun getFileName(uri: Uri, context: Context): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                name = cursor.getString(idx)
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment?.substringAfterLast('/')
        }
        return name
    }

    companion object {
        // FloatArray 序列化为小端序 ByteArray
        fun floatArrayToBytes(floats: FloatArray): ByteArray {
            val buf = java.nio.ByteBuffer.allocate(floats.size * 4)
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            buf.asFloatBuffer().put(floats)
            return buf.array()
        }

        // ByteArray 反序列化为 FloatArray
        fun bytesToFloatArray(bytes: ByteArray): FloatArray? {
            if (bytes.size % 4 != 0) return null
            val buf = java.nio.ByteBuffer.wrap(bytes)
            buf.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buf.getFloat() }
        }

        // 计算两个向量的余弦相似度
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
            return if (denom == 0f) 0f else dot / denom
        }
    }
}
