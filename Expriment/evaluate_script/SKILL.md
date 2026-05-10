# MNN 多模态模型评估测试 SKILL

## 项目概述

本项目提供了一套统一的多模态大模型（VLM）评估框架，支持通过 MNN Python API 加载模型，在多个基准数据集上进行推理与评估。

## 环境信息

- **操作系统**: Linux
- **Python**: 3.x
- **核心依赖**: `MNN` (llm/cv 模块)、`rouge`、`nltk`、`requests`、`Pillow`、`googletrans`
- **数据集根目录**: `/models/workspace/Bishe/dataset`
- **模型根目录**: `/models/workspace/Bishe/mnn_model`
- **结果输出目录**: `/models/workspace/Bishe/evaluate_result`

## 代码结构

```
evaluate_script/
├── dataset_loader.py      # 数据集加载器（6 个数据集）
├── model_wrapper.py       # MNN VLM 封装（MNNVLMWrapper）
├── evaluator.py           # 统一评估器（UnifiedEvaluator）
├── run_evaluation.py      # 评估入口脚本
├── test_components.py     # 组件单元测试
└── test_*.py              # 各类 MNN 推理实验脚本
```

## 支持的数据集

| 数据集 | 评估指标 | 说明 |
|--------|----------|------|
| CC-OCR | 字符准确率 (LCS) | OCR 文本识别 |
| COCO2017 | BLEU-1 | 图像描述生成 |
| OK-VQA | 关键词命中率 | 开放域视觉问答 |
| VQAv2 | ROUGE-L (最佳匹配) | 视觉问答 |
| POPE | 准确率 (yes/no) | 幻觉检测 |
| SA1B-LongTextCaption | ROUGE-L | 长文本图像描述（GT 为中文，评估时自动翻译为英文） |

## 可用模型清单

### ✅ 量化模型（W4A16）— 推理速度可接受

| 模型路径 | 类型 | 状态 |
|----------|------|------|
| `/models/workspace/Bishe/mnn_model/single/w4a16/net/` | Single / W4A16 / Net | ✅ 可用 |
| `/models/workspace/Bishe/mnn_model/single/w4a16/omni/` | Single / W4A16 / Omni | ✅ 可用 |
| `/models/workspace/Bishe/mnn_model/single/w4a16/smooth/` | Single / W4A16 / Smooth | ✅ 可用 |
| `/models/workspace/Bishe/mnn_model/eagle/w4a16/net/` | Eagle / W4A16 / Net | ✅ 可用 |
| `/models/workspace/Bishe/mnn_model/eagle/w4a16/omni_pre0/` | Eagle / W4A16 / Omni | ✅ 可用 |
| `/models/workspace/Bishe/mnn_model/eagle/w4a16/smooth_pre0/` | Eagle / W4A16 / Smooth | ✅ 可用 |

### ⚠️ FP 模型 — 推理极慢（单条 >120s）

| 模型路径 | 类型 | 状态 |
|----------|------|------|
| `/models/workspace/Bishe/mnn_model/single/fp/` | Single / FP | ⚠️ 可用但极慢 |
| `/models/workspace/Bishe/mnn_model/eagle/fp/` | Eagle / FP | ⚠️ 可用但极慢 |

### 待补充的预设空目录

以下目录已预留结构，但当前为空或只有 ONNX 中间文件，后续会补充 MNN 模型文件：

| 空目录路径 | 说明 |
|-----------|------|
| `/models/workspace/Bishe/mnn_model/single/w8a8/net-hqq/` | Single / W8A8 / Net-HQQ（待补充） |
| `/models/workspace/Bishe/mnn_model/single/w8a8/omni_pre0/` ~ `pre_24/` | Single / W8A8 / Omni 多阶段（待补充） |
| `/models/workspace/Bishe/mnn_model/single/w8a8/smooth_pre0/` ~ `pre_24/` | Single / W8A8 / Smooth 多阶段（待补充） |
| `/models/workspace/Bishe/mnn_model/eagle/w8a8/net-hqq/` | Eagle / W8A8 / Net-HQQ（待补充） |
| `/models/workspace/Bishe/mnn_model/eagle/w8a8/omni_pre0/` ~ `pre_24/` | Eagle / W8A8 / Omni 多阶段（待补充） |
| `/models/workspace/Bishe/mnn_model/eagle/w8a8/smooth_pre0/` ~ `pre_24/` | Eagle / W8A8 / Smooth 多阶段（待补充） |
| `/models/workspace/Bishe/mnn_model/single/w4a16/omni_pre12/` / `pre_24/` | Single / W4A16 / Omni 后期阶段（只有 ONNX） |
| `/models/workspace/Bishe/mnn_model/single/w4a16/smooth_pre12/` / `pre_24/` | Single / W4A16 / Smooth 后期阶段（只有 ONNX） |
| `/models/workspace/Bishe/mnn_model/eagle/w4a16/omni_pre12/` | Eagle / W4A16 / Omni 后期阶段（只有 ONNX） |

## 测试结果

### 组件单元测试

```bash
$ python3 test_components.py
```

| 测试项 | 结果 |
|--------|------|
| CC-OCR 加载 | ✅ 2 条样本 |
| COCO2017 加载 | ✅ 2 条样本 |
| OK-VQA 加载 | ✅ 2 条样本 |
| VQAv2 加载 | ✅ 2 条样本 |
| POPE 加载 | ✅ 2 条样本 |
| SA1B-LongTextCaption 加载 | ✅ 2 条样本 |
| CC-OCR 精确匹配 | 1.0000 |
| CC-OCR 1 字符差异 | 0.9091 |
| POPE yes 正确 | 1.0000 |
| POPE yes 错误 | 0.0000 |
| COCO2017 BLEU-1 | 1.0000 |
| OK-VQA 关键词命中 | 0.3333 |
| VQAv2 ROUGE-L 最佳 | 0.5000 |
| SA1B ROUGE-L | 0.6667 |

### OK-VQA 模型对比（20 条样本）

> **注意**: 使用 `--force` 参数确保每个模型独立推理，避免复用已有预测结果。

| 模型 | OK-VQA | 排名 |
|------|--------|------|
| `single_w4a16_smooth` | **0.4286** | 🥇 |
| `eagle_w4a16_smooth_pre0` | **0.3918** | 🥈 |
| `eagle_w4a16_net` | **0.3869** | 🥉 |
| `eagle_w4a16_omni_pre0` | **0.3777** | 4 |
| `single_w4a16_net` | **0.3427** | 5 |
| `single_w4a16_omni` | **0.235** | 6 |
| `single_fp` | — | ⚠️ 推理超时 |
| `eagle_fp` | — | ⚠️ 推理超时 |

**分析**:
- **single_w4a16_smooth 表现最佳**（0.4286）
- Eagle 系列整体优于 Single 系列（同类型对比）
- `omni` 类型在 Single 和 Eagle 中表现都相对较差
- FP 模型因推理速度极慢（单条 >120s），无法完成 20 条样本测试
- **全数据集测试（10 条样本）显示**: `eagle_w4a16_smooth_pre0` 在 OK-VQA 上达到 **0.4962**，为所有模型中最高

### 全数据集评估（量化模型，10 条样本）

| 模型 | CC-OCR | OK-VQA | VQAv2 | COCO2017 | SA1B | POPE |
|------|--------|--------|-------|----------|------|------|
| `eagle_w4a16_smooth_pre0` | 0.3387 | **0.4962** | 0.0336 | 0.0651 | 0.1841 | **0.9000** |
| `eagle_w4a16_net` | **0.4604** | 0.4786 | 0.0329 | 0.0542 | **0.1892** | 0.8000 |
| `single_w4a16_smooth` | 0.4279 | 0.3752 | 0.0432 | 0.0600 | 0.1813 | **0.9000** |
| `single_w4a16_net` | 0.4497 | 0.3802 | **0.0439** | **0.0657** | 0.1573 | **0.9000** |

> **结果文件**: `/models/workspace/Bishe/evaluate_result/results_<model>.csv`

**分析**:
- **OK-VQA**: `eagle_w4a16_smooth_pre0` 表现最佳（0.4962），Eagle 系列整体优于 Single
- **CC-OCR**: `eagle_w4a16_net` 最佳（0.4604）
- **POPE**: `single_w4a16_net` / `single_w4a16_smooth` / `eagle_w4a16_smooth_pre0` 并列 0.9
- **VQAv2 / COCO2017**: Single 系列略优于 Eagle
- Eagle 系列在需要外部知识的任务（OK-VQA）上优势明显

## 快速测试

### 1. 组件测试（无需模型）

```bash
cd /models/workspace/Bishe/evaluate_script
python3 test_components.py
```

### 2. 单模型单数据集快速评估

```bash
cd /models/workspace/Bishe/evaluate_script
python3 run_evaluation.py \
    --model_name single_w4a16_net \
    --model_path /models/workspace/Bishe/mnn_model/single/w4a16/net/ \
    --datasets ok-vqa \
    --num_samples 20 \
    --pred_dir /models/workspace/Bishe/evaluate_result/preds/single_w4a16_net \
    --output_csv /models/workspace/Bishe/evaluate_result/results.csv \
    --force
```

### 3. 全量评估（所有数据集，量化模型推荐 20 条）

```bash
cd /models/workspace/Bishe/evaluate_script
python3 run_evaluation.py \
    --model_name my_model \
    --model_path /models/workspace/Bishe/mnn_model/single/w4a16/net/ \
    --datasets all \
    --num_samples 20 \
    --pred_dir /models/workspace/Bishe/evaluate_result/preds/my_model \
    --output_csv /models/workspace/Bishe/evaluate_result/results.csv \
    --force
```

## 评估结果输出

- **CSV 汇总**: `/models/workspace/Bishe/evaluate_result/results_<model>.csv`
- **中间预测**: `/models/workspace/Bishe/evaluate_result/preds/<model>/`（JSON 格式，每 10 条自动保存 checkpoint）

## 注意事项

1. **模型路径格式**: MNN 内部路径拼接有时缺少斜杠，脚本已自动在目录路径末尾补充分隔符。
2. **图片尺寸**: `model_wrapper.py` 中统一将图片 resize 为 420x420 后传入模型。
3. **重复截断**: `model_wrapper.py` 包含后处理逻辑，可自动截断模型输出中的连续重复 token。
4. **数据集依赖**: COCO 相关数据集（OK-VQA、VQAv2、POPE）需要 `coco_photo/val2014/` 图片文件；若不存在需先解压 `val2014.zip`。
5. **Tokenizer 自动修复**: 部分导出模型使用 `tokenizer.mtok` 而非 MNN 运行时要求的 `tokenizer.txt`。`model_wrapper.py` 已内置 `_ensure_tokenizer()` 函数，会在加载模型前自动检测并复制缺失的 `tokenizer.txt`，无需手动干预。
6. **SA1B 中文 GT 翻译**: `evaluator.py` 在评估 SA1B-LongTextCaption 时，会自动检测中文 GT 并通过 `googletrans` 翻译为英文后再计算 ROUGE-L。
7. **避免预测复用**: 测试多个模型时，**必须使用 `--force` 参数**，或为每个模型指定独立的 `--pred_dir`，否则后续模型会复用第一个模型的预测结果，导致分数完全相同。
8. **FP 模型推理极慢**: FP（全精度）模型推理速度极慢（单条 >120 秒），建议仅测试量化模型（W4A16）。如需测试 FP 模型，建议将 `--num_samples` 降至 5 条以下。

## 扩展开发

- 新增数据集：在 `dataset_loader.py` 中继承 `BaseDataset` 并实现 `load()` 方法，然后注册到 `DATASET_REGISTRY`。
- 新增评估指标：在 `evaluator.py` 的 `UnifiedEvaluator` 中添加对应的 `_eval_xxx` 方法。
- 更换模型后端：修改 `model_wrapper.py` 中的 `MNNVLMWrapper`，保持 `predict(image_path, text)` 接口一致即可。
