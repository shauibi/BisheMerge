// 聊天数据仓库，封装 ChatDao 的增删改查操作供 ViewModel 调用
package com.llmapp.data.repository

import com.llmapp.data.database.ChatDao
import com.llmapp.data.database.MessageEntity
import com.llmapp.data.database.SessionEntity
import kotlinx.coroutines.flow.Flow

class ChatRepository(private val chatDao: ChatDao) {

    // 获取所有会话（Flow 响应式）
    fun getAllSessions(): Flow<List<SessionEntity>> = chatDao.getAllSessions()

    // 获取指定会话的消息列表
    fun getMessagesBySession(sessionId: Long): Flow<List<MessageEntity>> =
        chatDao.getMessagesBySession(sessionId)

    // 创建新会话，返回会话 ID
    suspend fun createSession(title: String): Long {
        val session = SessionEntity(title = title)
        return chatDao.insertSession(session)
    }

    // 插入消息，返回消息 ID
    suspend fun insertMessage(message: MessageEntity): Long {
        return chatDao.insertMessage(message)
    }

    // 更新消息内容（流式响应场景使用）
    suspend fun updateMessageContent(messageId: Long, newContent: String) {
        chatDao.updateMessageContent(messageId, newContent)
    }

    // 更新整个消息实体
    suspend fun updateMessage(message: MessageEntity) {
        chatDao.updateMessage(message)
    }

    // 删除指定消息
    suspend fun deleteMessage(messageId: Long) {
        chatDao.deleteMessageById(messageId)
    }

    // 删除指定会话
    suspend fun deleteSession(sessionId: Long) {
        chatDao.deleteSessionById(sessionId)
    }

    // 更新会话标题
    suspend fun updateSessionTitle(sessionId: Long, newTitle: String) {
        chatDao.updateSessionTitle(sessionId, newTitle)
    }
}
