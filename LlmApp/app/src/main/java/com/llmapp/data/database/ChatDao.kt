// 聊天数据访问对象，提供会话和消息的 Room DAO 接口
package com.llmapp.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // 获取所有会话，按创建时间降序
    @Query("SELECT * FROM sessions ORDER BY createTime DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    // 获取指定会话下的所有消息，按时间升序
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getMessagesBySession(sessionId: Long): Flow<List<MessageEntity>>

    // 根据 ID 查询单条消息
    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: Long): MessageEntity?

    // 插入新会话，返回自增 ID
    @Insert
    suspend fun insertSession(session: SessionEntity): Long

    // 插入新消息，返回自增 ID
    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    // 更新消息记录
    @Update
    suspend fun updateMessage(message: MessageEntity)

    // 更新指定消息的内容（用于流式响应逐步更新）
    @Query("UPDATE messages SET content = :newContent WHERE id = :messageId")
    suspend fun updateMessageContent(messageId: Long, newContent: String)

    // 根据 ID 删除消息
    @Query("DELETE FROM messages WHERE id = :msgId")
    suspend fun deleteMessageById(msgId: Long)

    // 根据 ID 删除会话
    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSessionById(sessionId: Long)

    // 删除指定会话下的所有消息
    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: Long)

    // 更新会话标题
    @Query("UPDATE sessions SET title = :newTitle WHERE id = :sessionId")
    suspend fun updateSessionTitle(sessionId: Long, newTitle: String)
}
