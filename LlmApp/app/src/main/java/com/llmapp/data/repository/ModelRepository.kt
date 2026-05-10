// 模型配置仓库，通过 DataStore 持久化模型目录和路径选择
package com.llmapp.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings_preferences")

class ModelRepository(context: Context) {
    private val dataStore = context.dataStore

    companion object {
        private val MODEL_DIRECTORIES_KEY = stringSetPreferencesKey("model_directories")
        private val SELECTED_MODEL_PATH_KEY = stringPreferencesKey("selected_model_path")
        private val EMBEDDING_MODEL_PATH_KEY = stringPreferencesKey("embedding_model_path")
    }

    // 获取所有已添加的模型目录
    fun getModelDirectories(): Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[MODEL_DIRECTORIES_KEY] ?: emptySet()
    }

    // 添加模型目录
    suspend fun addModelDirectory(path: String) {
        dataStore.edit { prefs ->
            val current = prefs[MODEL_DIRECTORIES_KEY] ?: emptySet()
            prefs[MODEL_DIRECTORIES_KEY] = current + path
        }
    }

    // 移除模型目录
    suspend fun removeModelDirectory(path: String) {
        dataStore.edit { prefs ->
            val current = prefs[MODEL_DIRECTORIES_KEY] ?: emptySet()
            prefs[MODEL_DIRECTORIES_KEY] = current - path
        }
    }

    // 获取当前选中的 LLM 模型路径
    fun getSelectedModelPath(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[SELECTED_MODEL_PATH_KEY]
    }

    // 保存选中的 LLM 模型路径
    suspend fun saveSelectedModelPath(path: String) {
        dataStore.edit { prefs ->
            prefs[SELECTED_MODEL_PATH_KEY] = path
        }
    }

    // 获取 embedding 模型路径
    fun getEmbeddingModelPath(): Flow<String?> = dataStore.data.map { prefs ->
        prefs[EMBEDDING_MODEL_PATH_KEY]
    }

    // 保存 embedding 模型路径
    suspend fun saveEmbeddingModelPath(path: String) {
        dataStore.edit { prefs ->
            prefs[EMBEDDING_MODEL_PATH_KEY] = path
        }
    }
}
