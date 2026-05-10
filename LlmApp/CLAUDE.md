# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 构建与开发

```bash
# 构建 debug APK
./gradlew assembleDebug

# 清理构建
./gradlew clean

# 构建时输出详细堆栈（排查 native 编译错误时使用）
./gradlew assembleDebug --stacktrace

# 运行单元测试
./gradlew test

# 运行单个测试类
./gradlew test --tests "com.llmapp.data.rag.TextSplitterTest"
```

这是一个 Android 项目，建议使用 Android Studio 打开以获得完整 IDE 支持。

## 架构总览

**LLMApp** 是一个基于阿里 [MNN](https://github.com/alibaba/MNN) 推理框架的端侧 LLM 聊天应用（包名 `com.llmapp`），在 arm64-v8a 设备上本地运行量化 LLM 模型，并支持 RAG（检索增强生成）和语音输入。

### 分层结构

```
UI 层 (Compose, Material 3)
    ├── MainActivity.kt — ViewPager2 四页容器（聊天/模板/知识库/设置）
    ├── ChatScreen.kt — 流式 Markdown 渲染 + RAG FilterChip
    ├── KnowledgeScreen.kt — 知识库管理（文档导入/删除、embedding 模型状态）
    ├── TemplateScreen.kt — 提示词模板选择
    └── SettingsScreen.kt — 模型目录管理
ViewModel 层
    ├── ChatViewModel.kt — 会话/消息状态管理、流式推理编排、RAG 检索注入
    ├── KnowledgeViewModel.kt — 文档列表、embedding 模型加载
    ├── PromptTemplateViewModel.kt — 模板管理
    └── SettingsViewModel.kt — 模型发现、目录管理
数据层
    ├── ChatRepository.kt — 封装 ChatDao（sessions + messages）
    ├── RagRepository.kt — 文档导入流水线（解析→切片→向量化→入库）、向量搜索
    ├── ModelRepository.kt — DataStore 偏好（模型路径、embedding 路径）
    ├── TemplateRepository.kt — JSON 模板文件读写
    └── AppDatabase.kt — Room DB v3（sessions/messages/documents/chunks）
JNI 桥接层
    ├── NativeLib.kt — Kotlin external fun 声明（LLM 10 个 + Embedding 5 个函数）
    └── llm_infer_jni.cpp — C++17 JNI 实现（LlmSession + EmbeddingSession）
Native 引擎 (MNN)
    └── 外部 MNN 库，CMakeLists.txt 引用 ../../../../../MNN（仓库根目录兄弟目录）
        提供 MNN::Transformer::Llm（LLM 推理）
        提供 MNN::Interpreter（BERT embedding 推理）
```

### Application 单例

`LLMApplication`（继承 `Application`）持有全局唯一的两个会话指针和状态流：

- `sessionPtr: Long` — LLM 推理会话
- `embeddingSessionPtr: Long` — Embedding 模型会话
- `isModelLoaded: StateFlow<Boolean>` / `isEmbeddingModelLoaded: StateFlow<Boolean>` / `isModelLoading: StateFlow<Boolean>`
- 模板选中事件流 `templateSelected`、模型切换事件流 `modelReloadEvents`
- 启动时自动加载上次选中的模型和 embedding 模型路径
- ViewModel 通过 `(application as LLMApplication)` 引用

### LLM 推理流程（JNI）

1. `NativeLib.createSession(path)` → 分配 `LlmSession`
2. `NativeLib.loadModel(ptr)` → `MNN::Transformer::Llm::createLLM(config.json)` → `llm->load()`
3. `NativeLib.inferenceStream(ptr, prompt, imagePath, callback)` → detached `std::thread` 调用 `llm->response()`，`Utf8StreamProcessor` 做 UTF-8 解码，约 30ms 批量通过 JNI 回调 `onToken`/`onComplete`/`onError`
4. `NativeLib.runTest(ptr, input)` → 同步 `llm->response()`
5. `NativeLib.destroySession(ptr)` → `Llm::destroy()` + 释放
6. 对话历史管理：`clearHistory`、`getHistoryTurnCount`、`trimHistory`

JNI 层在 `JNI_OnLoad` 中缓存全局 `JavaVM*` 用于线程挂载。

### Embedding 推理流程（JNI + RAG）

使用 **MNN Interpreter API**（非 Llm API），BERT 模型结构不同：

1. `NativeLib.createEmbeddingSession(path)` → 分配 `EmbeddingSession`
2. `NativeLib.loadEmbeddingModel(ptr)` → `Interpreter::createFromFile(mnn)` → `createSession({input_ids, attention_mask} → {sentence_embedding})`，零填充 [CLS]=101 [SEP]=102 跑 dummy forward 确定 dim=512
3. `NativeLib.computeEmbedding(ptr, text)` → `Tokenizer::encode()` → 填充到 512 → `runSession()` → 读 CLS token 位置 512 维向量
4. `NativeLib.destroyEmbeddingSession(ptr)` → `releaseSession()` + `delete tokenizer`

**约束**：模型 shape 固定 `[1, 512, 1, 1]`（NCHW），不可 resize，短序列零填充。`tokenizer.cpp` 从 MNN 源码直接编译（不在 libMNN.so 中）。

### RAG 检索流程

```
用户发消息（RAG FilterChip 选中）
  → ChatViewModel.sendMessage()
    → RagRepository.searchSimilar(query, topK=3)
      → NativeLib.computeEmbedding(query) → FloatArray(512)
      → getAllChunks() → 逐 chunk 字节→浮点→余弦相似度→排序取 top-3
    → buildRagPrompt(query, context) — 英文指令 + Context + Question
  → NativeLib.inferenceStream(finalPrompt) → LLM 流式生成
```

文档导入：`SAF URI → 内部存储复制 → DocumentParserFactory → parse() → TextSplitter.split() → 逐 chunk computeEmbedding → FloatArray→ByteArray（小端序） → Room 入库`

**Scoped Storage**：C++ `std::ifstream` 无法读取 `/storage/emulated/0/`，必须用 SAF `ContentResolver.openInputStream()` 复制到 `/data/data/com.llmapp/files/` 后传路径给 JNI。

### 语音输入

`VoskSpeechRecognizer`（Vosk 0.3.47）— ChatFragment 中按住麦克风按钮触发，`onPartialResult` 实时显示，松开后 `stopListening()` 获取最终识别文本并填入输入框。Vosk 模型（vosk-model-small-cn-0.22, ~66MB）放在 `app/src/main/assets/`，首次使用自动解压到应用私有目录。

### 数据模型

- **sessions**: id, title, createTime
- **messages**: id, sessionId (FK), role, content, timestamp, imagePath
- **documents**: id, fileName, fileType, fileSize, importTime, chunkCount, status
- **chunks**: id, documentId (FK CASCADE), chunkIndex, content, embedding (BLOB, 小端序 FloatArray), tokenCount
- **DataStore**: `model_directories` (Set\<String\>), `selected_model_path` (String), `embedding_model_path` (String)

### 模型文件格式

LLM 模型目录：`config.json`、`llm.mnn` + `llm.mnn.weight`、`tokenizer.mtok` 或 `tokenizer.txt`、可选的 `visual.mnn`

Embedding 模型目录：`config.json`（含 `embedding_model` 键指向 .mnn 文件名）、`bge_small_zh.mnn`、`tokenizer.txt`（BERT WordPiece vocab，一行一个 token）

## 重要约束

- **仅构建 arm64-v8a ABI**
- **NDK 27.2.12479018、CMake 3.22.1** — 不要随意升级，可能破坏 native 编译
- **KSP 版本必须严格对齐 Kotlin 版本**：当前 Kotlin 2.0.21 → KSP 2.0.21-1.0.28
- **MNN 源码树必须在仓库根目录的兄弟目录**：`CMakeLists.txt` 中 `MNN_ROOT` 路径为 `../../../../../MNN`（从 `app/src/main/cpp` 向上 5 级到项目根，再 `../MNN`）
- **`libMNN.so` 必须放在 `app/src/main/jniLibs/arm64-v8a/`**
- **LLM 和 Embedding 会话都是全局单实例**：不要创建多个
- **`CMakeLists.txt` 从 MNN 源码直接编译 `tokenizer.cpp`**（`${MNN_ROOT}/transformers/llm/engine/src/tokenizer.cpp`），不在 `libMNN.so` 中
- **SettingsFragment 已改为 ComposeView**，不再使用 XML binding

## 关键文件索引

| 文件 | 说明 |
|------|------|
| `app/src/main/java/com/llmapp/LLMApplication.kt` | Application 单例，持有会话指针和仓库引用 |
| `app/src/main/java/com/llmapp/jni/NativeLib.kt` | Kotlin JNI external 声明（15 个函数） |
| `app/src/main/cpp/llm_infer_jni.cpp` | C++17 JNI 实现（LlmSession + EmbeddingSession） |
| `app/src/main/cpp/CMakeLists.txt` | CMake 构建配置 |
| `app/src/main/java/com/llmapp/data/AppDatabase.kt` | Room 数据库（4 张表） |
| `app/src/main/java/com/llmapp/data/RagRepository.kt` | RAG 流水线（导入/分块/向量化/搜索） |
| `app/src/main/java/com/llmapp/ui/chat/ChatViewModel.kt` | 聊天核心 ViewModel |
| `app/src/main/java/com/llmapp/ui/main/MainActivity.kt` | 入口 Activity（ViewPager2） |
| `app/src/main/java/com/llmapp/asr/VoskSpeechRecognizer.kt` | Vosk 离线语音识别 |
| `app/src/main/cpp/llm/llm.hpp` | MNN Llm API 头文件 |
| `app/src/main/cpp/llm/llmconfig.hpp` | MNN LlmConfig 头文件 |

## 测试

单元测试位于 `app/src/test/java/com/llmapp/`，使用 JUnit 4 + Mockito + kotlinx-coroutines-test：

- `ChatViewModelTest.kt` — ViewModel 逻辑测试
- `RagRepositoryTest.kt` — RAG 仓库测试
- `TextSplitterTest.kt` — 文本切片测试
- `MarkdownBufferTest.kt` — Markdown 缓冲解析测试

```bash
./gradlew test                                    # 全部单元测试
./gradlew test --tests "ClassName"                # 单个测试类
```

## 根目录辅助脚本

- `check_onnx.py` — ONNX 模型格式校验
- `export_bge_mnn.py` — 将 BGE embedding 模型导出为 MNN 格式
- `test_embedding_consistency.py` — 验证 embedding 推理一致性
