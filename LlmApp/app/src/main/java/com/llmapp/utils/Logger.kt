// 日志工具单例，统一封装 Android Log 输出，标签固定为 "LLMApp"
package com.llmapp.utils

import android.util.Log

// 提供简洁的 d/e/i/w 四级日志接口，统一 TAG 便于过滤
object Logger {

    private const val TAG = "LLMApp"

    // 输出 DEBUG 级别日志
    fun d(message: String, throwable: Throwable? = null) {
        Log.d(TAG, message, throwable)
    }

    // 输出 ERROR 级别日志
    fun e(message: String, throwable: Throwable? = null) {
        Log.e(TAG, message, throwable)
    }

    // 输出 INFO 级别日志
    fun i(message: String, throwable: Throwable? = null) {
        Log.i(TAG, message, throwable)
    }

    // 输出 WARN 级别日志
    fun w(message: String, throwable: Throwable? = null) {
        Log.w(TAG, message, throwable)
    }
}
