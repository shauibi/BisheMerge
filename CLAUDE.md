# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

本仓库是毕业设计"端侧多模态大模型"的工作空间，包含两个子项目，各自有独立 CLAUDE.md：

- **`LlmApp/`** — Android 端侧 LLM 聊天应用（MNN 推理 + RAG + Vosk 语音）。详见 `LlmApp/CLAUDE.md`
- **`Expriment/`** — 模型量化、多模态 benchmark 评估、性能/RAG/ASR 实验。详见 `Expriment/CLAUDE.md`

`LlmApp/` 有自己的 git 仓库；根目录 `.gitignore` 忽略二进制产物、模型文件、数据集等。

## 跨项目约束

以下约束同时影响两个子项目：

- **MNN 引擎共享**：LlmApp 通过 JNI 调用 `libMNN.so`，Expriment 的评估框架通过 MNN Python 包调用。MNN 源码位于仓库根目录的兄弟目录（LlmApp 的 CMakeLists.txt 以 `../../../../../MNN` 引用），Expriment 模型的 `config.json` 由 MNN 工具链导出。
- **模型格式一致**：LLM 模型目录结构（`config.json`、`llm.mnn` + `.weight`、`tokenizer.txt`/`.mtok`）和 Embedding 模型目录结构（`config.json` 含 `llm_model` 键、`.mnn`、`tokenizer.txt`）在两个项目中保持一致。
- **量化方案选择结论**：Expriment 中经过熵权法 + 帕累托前沿筛选，**`smoothw4a16_eagle` 为综合最优**。LlmApp 部署时应优先使用该量化方案。
- **smoothw8a8 不可用**：该量化方案在实验中输出异常，两个项目都不应使用。
