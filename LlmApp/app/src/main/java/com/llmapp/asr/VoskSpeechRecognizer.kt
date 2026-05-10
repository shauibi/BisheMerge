package com.llmapp.asr

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.llmapp.utils.Logger
import kotlinx.coroutines.*
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

// 离线语音识别封装，基于 Vosk 引擎，支持流式中间结果和最终识别
class VoskSpeechRecognizer(private val context: Context) {

    enum class State { IDLE, INITIALIZING, READY, RECORDING, ERROR }

    @Volatile
    var state: State = State.IDLE
        private set

    // 中间识别结果回调
    var onPartialResult: ((String) -> Unit)? = null
    // 最终识别结果回调
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    @Volatile private var isRecording = false

    private val modelDirName = "vosk-model-small-cn-0.22"
    private val sampleRate = 16000

    private fun setState(newState: State) {
        state = newState
        onStateChanged?.invoke(newState)
    }

    // 初始化 Vosk 识别器（异步加载模型到内存）
    fun initialize() {
        if (state == State.READY || state == State.RECORDING) return
        setState(State.INITIALIZING)

        Thread {
            try {
                val modelPath = getModelPath()
                    ?: run {
                        postError("Voice model not found. Place vosk-model in assets.")
                        return@Thread
                    }

                model = Model(modelPath)
                recognizer = Recognizer(model, sampleRate.toFloat())
                setState(State.READY)
                Logger.d("Vosk model loaded from: $modelPath")
            } catch (e: Exception) {
                Logger.e("Failed to initialize Vosk", e)
                postError("Failed to load voice model: ${e.message}")
            }
        }.start()
    }

    // 开始录音识别
    fun startListening() {
        if (state != State.READY) {
            onError?.invoke("Recognizer not ready (state=$state)")
            return
        }

        val rec = recognizer ?: return

        // 计算合适的 AudioRecord 缓冲区大小
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2
            )
        } catch (e: Exception) {
            postError("Failed to create AudioRecord: ${e.message}")
            return
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            postError("AudioRecord initialization failed")
            audioRecord?.release()
            audioRecord = null
            return
        }

        isRecording = true
        setState(State.RECORDING)

        // 后台线程持续读取音频数据并送入 Vosk 识别
        recordThread = Thread {
            try {
                audioRecord?.startRecording()
                val buffer = ShortArray(4096)
                var lastPartialCheck = System.currentTimeMillis()

                while (isRecording) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: break
                    if (bytesRead < 0) break

                    if (bytesRead > 0) {
                        rec.acceptWaveForm(buffer, bytesRead)
                    }

                    // 每 100ms 获取一次中间识别结果
                    val now = System.currentTimeMillis()
                    if (now - lastPartialCheck > 100) {
                        lastPartialCheck = now
                        val partial = rec.partialResult
                        if (partial.isNotEmpty()) {
                            parsePartialJson(partial)
                        }
                    }
                }
            } catch (e: Exception) {
                Logger.e("Recording error", e)
                postError("Recording error: ${e.message}")
            } finally {
                stopAudioRecord()
            }
        }.apply { start() }
    }

    // 停止录音并返回最终识别文本
    fun stopListening(): String? {
        isRecording = false
        recordThread?.join(2000)
        recordThread = null
        stopAudioRecord()

        val rec = recognizer ?: return null

        // 传入空波形信号触发 Vosk 结束识别
        rec.acceptWaveForm(ShortArray(0), 0)

        val finalJson = rec.finalResult
        Logger.d("Vosk finalResult: $finalJson")
        rec.reset()

        setState(State.READY)

        if (finalJson.isNotEmpty()) {
            return try {
                JSONObject(finalJson).optString("text", "")
            } catch (e: Exception) {
                Logger.e("Failed to parse finalResult", e)
                ""
            }
        }
        return ""
    }

    // 释放所有资源
    fun release() {
        isRecording = false
        recordThread?.interrupt()
        recordThread = null
        stopAudioRecord()
        recognizer?.close()
        recognizer = null
        model?.close()
        model = null
        setState(State.IDLE)
    }

    private fun stopAudioRecord() {
        try {
            audioRecord?.stop()
        } catch (_: Exception) {}
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    private fun parsePartialJson(json: String) {
        try {
            val obj = JSONObject(json)
            val text = obj.optString("partial", "")
            if (text.isNotEmpty()) {
                onPartialResult?.invoke(text)
            }
        } catch (_: Exception) {}
    }

    private fun postError(msg: String) {
        setState(State.ERROR)
        onError?.invoke(msg)
    }

    // 从 assets 复制模型到内部存储，返回模型目录路径
    private fun getModelPath(): String? {
        val targetDir = File(context.filesDir, modelDirName)

        // 已存在且非空则直接返回
        if (targetDir.exists() && targetDir.isDirectory && targetDir.listFiles()?.isNotEmpty() == true) {
            return targetDir.absolutePath
        }

        // 从 assets 目录复制模型文件
        return try {
            copyAssetDir(modelDirName, targetDir)
            targetDir.absolutePath
        } catch (e: Exception) {
            Logger.e("Failed to copy model from assets", e)
            null
        }
    }

    // 递归复制 assets 目录到目标路径
    private fun copyAssetDir(assetPath: String, targetDir: File) {
        val files = context.assets.list(assetPath) ?: return
        if (files.isEmpty()) {
            // 单个文件处理
            targetDir.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                FileOutputStream(targetDir).use { output ->
                    input.copyTo(output)
                }
            }
            return
        }

        targetDir.mkdirs()
        for (file in files) {
            copyAssetDir("$assetPath/$file", File(targetDir, file))
        }
    }
}
