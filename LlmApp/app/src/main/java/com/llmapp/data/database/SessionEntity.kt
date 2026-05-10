// 会话实体，对应 sessions 表
package com.llmapp.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,               // 会话标题（通常取自首条消息）
    val createTime: Long = System.currentTimeMillis()
)
