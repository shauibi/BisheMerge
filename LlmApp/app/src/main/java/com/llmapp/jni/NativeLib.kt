// JNI 原生库接口，封装 LLM 推理和 Embedding 计算的 Native 方法
package com.llmapp.jni

// 单例对象，加载 libllm_infer.so 并声明所有 JNI external 函数
object NativeLib {

    init {
        System.loadLibrary("llm_infer")
    }

    // 创建 LLM 推理会话，返回会话指针
    external fun createSession(modelPath: String): Long

    // 销毁 LLM 推理会话，释放资源
    external fun destroySession(ptr: Long)

    // 加载 LLM 模型（config.json + tokenizer + 权重）
    external fun loadModel(ptr: Long): Boolean

    // 流式推理：通过回调逐 token 返回生成结果
    external fun inferenceStream(
        ptr: Long,
        prompt: String,
        imagePath: String?,
        callback: InferenceCallback
    )

    // 同步测试推理，返回完整结果
    external fun runTest(ptr: Long, input: String): String

    // 设置模型路径（不重建会话）
    external fun setModelPath(ptr: Long, path: String): Boolean

    // 清除对话历史
    external fun clearHistory(ptr: Long)

    // 获取当前对话轮数（一问一答为一轮）
    external fun getHistoryTurnCount(ptr: Long): Int

    // 裁剪对话历史，仅保留最近 maxTurns 轮
    external fun trimHistory(ptr: Long, maxTurns: Int)

    // 获取原生库版本号
    external fun getNativeVersion(): String

    // 获取最近一次推理耗时统计
    external fun getLastTiming(): String

    // 获取首 token 延迟（微秒）
    external fun getLastTTFT(): Long

    // 获取最近一次推理的输出 token 数
    external fun getLastOutputTokens(): Int

    // ── Embedding / RAG ──────────────────────────────────────────

    // 创建 Embedding 推理会话，返回会话指针
    external fun createEmbeddingSession(modelPath: String): Long

    // 销毁 Embedding 会话
    external fun destroyEmbeddingSession(ptr: Long)

    // 加载 Embedding 模型
    external fun loadEmbeddingModel(ptr: Long): Boolean

    // 获取 Embedding 向量维度
    external fun getEmbeddingDim(ptr: Long): Int

    // 计算文本的 Embedding 向量
    external fun computeEmbedding(ptr: Long, text: String): FloatArray
}
