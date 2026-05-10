# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概述

这是一个毕业设计项目，研究 **Qwen3-VL-4B 视觉语言模型的量化与端侧部署**，使用 **MNN（Mobile Neural Network）** 作为推理引擎。项目实验了多种量化方案（SmoothQuant、OmniQuant；W4A16、W8A8），对比了有无 Eagle 投机解码的性能差异，并在 6 个多模态数据集上进行了系统评估。

## 目录结构

```
evaluate_script/     # 核心评估框架（VLM 推理 + 指标计算）
mnn_model/           # MNN 转换后的模型：fp16、omniw4a16、smoothw4a16 等（有无 _eagle 后缀区分）
origin_model/        # 原始 HuggingFace 模型：Qwen3-VL-4B + Eagle 草稿模型
3_1_qunatize/        # 量化实验 + 基于熵权法和帕累托前沿的模型筛选
3_2 consistency_test/ # PyTorch 与 MNN 的 Embedding 一致性测试（BGE 模型）
5_3_Performance/     # 性能基准测试：加载时间、内存占用、推理速度、RAG 延迟
5_4_RAG/             # 基于中文文档的 RAG 效果评估
5_5_ASR/             # 中文语音识别测试
dataset/             # 评估数据集：CC-OCR、COCO2017、OK-VQA、VQAv2、POPE、SA1B-LongTextCaption、coco_photo
new_MNN/             # MNN 框架源码（MNN + new_new_mnn）
```

## 运行评估

主评估入口是 `evaluate_script/run_evaluation.py`，需要先编译安装 MNN Python 包（位于 `new_MNN/MNN/pymnn/`）。

安装依赖：
```bash
pip install rouge nltk jiwer pandas numpy requests Pillow
```

组件测试（无需模型）：
```bash
python evaluate_script/test_components.py
```

快速评估（每个数据集 10 条样本）：
```bash
python evaluate_script/run_evaluation.py \
    --model_name smoothw4a16 \
    --model_path mnn_model/smoothw4a16/ \
    --datasets all --num_samples 10 --force
```

单数据集评估：
```bash
python evaluate_script/run_evaluation.py \
    --model_name my_model \
    --model_path mnn_model/smoothw4a16/ \
    --datasets ok-vqa --num_samples 20 --force
```

`--force` 强制重新推理；不加该参数时，若 `evaluate_result/preds/` 下已有预测结果则直接复用。结果追加写入 `evaluate_result/results.csv`。

## 数据集与评估指标

| 数据集 | 指标 | GT 格式 |
|--------|------|---------|
| CC-OCR | 字符准确率（LCS） | 单个字符串 |
| COCO2017 | BLEU-1 | 5 个 caption 列表 |
| OK-VQA | 关键词命中率 | 10 个答案列表 |
| VQAv2 | ROUGE-L（最佳匹配） | 答案列表 |
| POPE | Yes/No 准确率 | "yes" 或 "no" |
| SA1B-LongTextCaption | ROUGE-L | 单个 caption（中文 GT 自动翻译为英文） |

## MNN 模型结构

每个模型目录包含：`config.json`、`llm.mnn` / `llm.mnn.weight`、`visual.mnn` / `visual.mnn.weight`、`tokenizer.txt`（或 `.mtok`）、`llm_config.json`。Eagle 版本额外包含 `eagle.mnn` / `eagle.mnn.weight`、`eagle_d2t.mnn`、`eagle_fc.mnn` / `eagle_fc.mnn.weight`。

可用量化变体：`fp16`、`omniw4a16`、`omniw8a8`、`smoothw4a16`、`smoothw8a8`——各自分为有无 `_eagle` 后缀两个版本。**`smoothw8a8` 系列不可用**（量化效果差，模型输出异常）。

## 重要实现细节

- **图像尺寸**：`model_wrapper.py` 硬编码所有输入图像 resize 为 420×420。
- **Tokenizer 修复**：若 `tokenizer.txt` 缺失，`model_wrapper.py` 会自动从同目录的 `tokenizer.mtok` 复制一份。
- **重复截断**：`model_wrapper.py` 包含后处理逻辑，自动截断模型输出中的连续重复 token。
- **SA1B 中文 GT**：`evaluator.py` 自动检测中文 GT，通过百度翻译 API 转为英文后计算 ROUGE-L（需设置环境变量 `BAIDU_APPID` / `BAIDU_SECRET`）。
- **模型路径末尾分隔符**：`run_evaluation.py` 会确保模型路径以分隔符结尾，因为 MNN 内部路径拼接对此敏感。
- **每 10 条自动保存**：推理结果每 10 条 checkpoint 一次，避免长时间运行丢数据。
- **防止预测复用**：评估多个模型时，必须使用不同的 `--pred_dir` 或加 `--force`，否则后续模型会错误复用第一个模型的预测结果。
