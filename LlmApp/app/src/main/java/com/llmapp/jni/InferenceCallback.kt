// LLM 推理回调接口，定义 JNI 层向 Java 层传递结果的协议
package com.llmapp.jni

// 流式推理回调：接收逐 token 输出、完成通知和错误信息
interface InferenceCallback {

    // 收到新生成的 token 时回调
    fun onToken(token: String)

    // 推理完成时回调，返回完整文本
    fun onComplete(fullText: String)

    // 推理出错时回调
    fun onError(error: String)
}
