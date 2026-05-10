// 应用数据库，Room 单例，管理会话、消息、文档、分块四张表
package com.llmapp.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [SessionEntity::class, MessageEntity::class, DocumentEntity::class, ChunkEntity::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun chatDao(): ChatDao
    abstract fun ragDao(): RagDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // 双重检查锁定单例，获取数据库实例
        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "llm_app_database"
                ).fallbackToDestructiveMigration()  // 版本升级时重建（开发阶段适用）
                 .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
