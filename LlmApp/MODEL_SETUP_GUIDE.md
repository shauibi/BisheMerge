# LLMApp 模型部署指南

## 概览

LLMApp 依赖三个模型资源，缺一不可：

| 模型 | 用途 | 大小参考 | 加载方式 |
|------|------|----------|----------|
| LLM 推理模型 | 对话生成 | 1~8 GB | 设置页导入 |
| Vosk 语音模型 | 离线语音识别 | ~66 MB | 自动从 APK 解压 |
| Embedding 模型 | RAG 文档检索 | ~400 MB | 知识库页导入 |

---

## 1. LLM 推理模型

### 1.1 模型格式

使用阿里 MNN 格式的 LLM 模型，目录结构：

```
模型名称/
├── config.json          # 模型配置（必需）
├── llm.mnn              # 模型结构（必需）
├── llm.mnn.weight       # 模型权重（必需）
├── tokenizer.mtok       # 分词器（与 tokenizer.txt 二选一即可）
├── tokenizer.txt        # 分词器文本
└── visual.mnn           # 视觉模型（可选，多模态模型才需要）
```

### 1.2 获取模型

从 MNN 官方或第三方获取已转换的 MNN 格式模型。常见来源：

- [MNN Model Zoo](https://github.com/alibaba/MNN) — 官方模型库
- 自行用 MNN 转换工具将 HuggingFace/GGUF 模型转为 MNN 格式

### 1.3 导入步骤

1. 将模型目录完整复制到手机存储（如 `/sdcard/models/qwen2-1.5b/`）
2. 打开 LLMApp → **设置** 标签页
3. 点击 **Add Model Directory**，用系统文件选择器选取模型目录
4. 应用自动将目录复制到内部存储
5. 在模型列表中点击选中目标模型
6. 顶部状态卡片显示模型名称 + `[Loaded]` 即加载成功

### 1.4 验证

切换回聊天页，输入消息发送，应能正常得到回复。

---

## 2. 语音识别模型（Vosk）

### 2.1 模型格式

Vosk 离线语音识别模型，目录结构：

```
vosk-model-small-cn-0.22/
├── am/          # 声学模型文件
├── conf/        # 配置文件
├── graph/       # WFST 解码图
├── ivector/     # i-vector 提取器
└── README       # 模型说明
```

### 2.2 获取模型

从 [Vosk Models](https://alphacephei.com/vosk/models) 下载中文小模型 `vosk-model-small-cn-0.22.zip`，解压。

**当前状态**：项目已将模型放在 `app/src/main/assets/` 目录下，会自动打进 APK（约 66MB）。首次使用时自动解压到应用私有目录。

### 2.3 如需瘦身 APK（可选）

如果觉得 APK 太大，可将模型从 assets 中移除，改为用户手动导入：

1. 删除 `app/src/main/assets/vosk-model-small-cn-0.22/` 目录
2. 修改 `VoskSpeechRecognizer.kt` 中的 `getModelPath()` 方法，从 DataStore 读取用户选择的路径，类似 LLM 模型的导入方式

### 2.4 验证

在聊天页长按麦克风按钮，状态显示 "Listening..." 并看到实时转写文字即正常。

---

## 3. Embedding 模型（RAG）

### 3.1 模型格式

MNN Interpreter 格式的 BERT 类 Embedding 模型，目录结构：

```
embedding模型目录/
├── config.json          # 含 llm_model 和 tokenizer_file 字段
├── bge_small_zh.mnn     # BERT 模型（文件名以 config.json 为准）
└── tokenizer.txt        # WordPiece 词表（一行一个 token）
```

### 3.2 config.json 示例

```json
{
  "llm_model": "bge_small_zh.mnn",
  "tokenizer_file": "tokenizer.txt"
}
```

### 3.3 推荐模型

推荐使用 BGE-small-zh（BAAI General Embedding），维度 512，适合端侧部署：

- 从 HuggingFace [BAAI/bge-small-zh](https://huggingface.co/BAAI/bge-small-zh) 下载
- 用 MNN 转换工具转为 `.mnn` 格式

### 3.4 导入步骤

1. 将模型文件夹复制到手机存储
2. 打开 LLMApp → **知识库** 标签页
3. 点击顶部的 Embedding 模型状态栏，选择模型目录
4. 出现绿色指示灯（Loaded）即加载成功

### 3.5 验证

1. 导入一个文本文档到知识库
2. 显示 "Ready" 表示导入并向量化完成
3. 聊天页点击 RAG chip 开启 RAG
4. 发送与文档内容相关的问题，回复下方出现 "Sources (N)" 来源卡片即正常

---

## 4. 快速检查清单

| 检查项 | 验证方法 |
|--------|----------|
| LLM 模型已加载 | 设置页顶部显示模型名 + Loaded |
| Vosk 已就绪 | 聊天页长按麦克风不报错 |
| Embedding 模型已加载 | 知识库页顶部指示灯为绿色 |
| RAG 可用 | 开启 RAG chip，发送问题后出现 Sources 卡片 |

---

## 5. 常见问题

**Q: 模型加载失败**
- 确认 `config.json` 文件存在于模型目录中
- 查看 logcat 日志（tag: `LLMApp`），搜索 `DIAG` 关键字
- 确认手机存储空间充足（模型权重会占用内存）

**Q: Vosk 报 "Voice model not found"**
- APK 安装完整吗？assets 中的 vosk 模型是否被删除
- 进入设置 → 应用 → LLMApp → 清除数据，重新启动

**Q: RAG 检索无结果**
- 确认 Embedding 模型已加载
- 确认知识库中有已导入且状态为 Ready 的文档
- 检查提问是否与文档内容相关
