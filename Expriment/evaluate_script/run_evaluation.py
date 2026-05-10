#!/usr/bin/env python3
"""
统一多模态模型评估入口脚本。

用法示例：
    # 评估所有数据集（需要 MNN 已安装）
    python evaluate_script/run_evaluation.py --model_name qwen3vl_2B

    # 仅评估 POPE（popular 策略）
    python evaluate_script/run_evaluation.py --model_name qwen3vl_2B \
        --datasets pope --pope_strategy popular

    # 快速测试（每个数据集只取 10 条）
    python evaluate_script/run_evaluation.py --model_name qwen3vl_2B \
        --num_samples 10

    # 仅做评估（加载已有预测结果，不运行推理）
    python evaluate_script/run_evaluation.py --model_name qwen3vl_2B \
        --pred_dir evaluate_result/preds
"""

import os
import sys
import json
import argparse
import csv
from typing import List, Dict

# 将 evaluate_script 所在目录加入路径，以便导入同级模块
_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from dataset_loader import load_dataset, DATASET_REGISTRY, download_image
from evaluator import UnifiedEvaluator

try:
    from model_wrapper import MNNVLMWrapper
    MNN_AVAILABLE = True
except ImportError:
    MNN_AVAILABLE = False


def _pred_filename(dataset_name: str, pope_strategy: str = "random") -> str:
    """根据数据集名称生成预测结果文件名"""
    if dataset_name.lower() == "pope":
        return f"{dataset_name}_{pope_strategy}_predictions.json"
    return f"{dataset_name}_predictions.json"


def run_inference(model, dataset_name: str, samples: List[Dict], pred_dir: str, pope_strategy: str = "random"):
    """对样本列表运行模型推理，并保存预测结果"""
    pred_path = os.path.join(pred_dir, _pred_filename(dataset_name, pope_strategy))
    predictions = []

    for i, sample in enumerate(samples):
        img = sample.get("image", "")
        prompt = sample.get("prompt", "")

        # 如果是 URL，先下载到本地
        if img.startswith("http://") or img.startswith("https://"):
            img_path = download_image(img)
        else:
            img_path = img

        print(f"[{dataset_name}] {i + 1}/{len(samples)}: {prompt[:60]}...")
        try:
            pred = model.predict(img_path, prompt)
        except Exception as e:
            print(f"[ERROR] Inference failed: {e}")
            pred = ""

        predictions.append({
            "id": sample["id"],
            "prediction": pred,
            "ground_truth": sample["ground_truth"],
            "prompt": prompt
        })

        # 每 10 条保存一次 checkpoint
        if (i + 1) % 10 == 0:
            with open(pred_path, 'w', encoding='utf-8') as f:
                json.dump(predictions, f, ensure_ascii=False, indent=2)

    with open(pred_path, 'w', encoding='utf-8') as f:
        json.dump(predictions, f, ensure_ascii=False, indent=2)

    print(f"[INFO] Predictions saved to {pred_path}")
    return predictions


def load_predictions(dataset_name: str, pred_dir: str, pope_strategy: str = "random") -> List[Dict]:
    """加载已有的预测结果，如果不存在返回 None"""
    pred_path = os.path.join(pred_dir, _pred_filename(dataset_name, pope_strategy))
    if not os.path.exists(pred_path):
        return None
    with open(pred_path, 'r', encoding='utf-8') as f:
        return json.load(f)


def main():
    parser = argparse.ArgumentParser(description="Unified Multimodal Model Evaluation")
    parser.add_argument("--model_name", type=str, required=True,
                        help="模型名称，将作为 CSV 第一列输出")
    parser.add_argument("--model_path", type=str,
                        default="MNN/transformers/llm/export/model_w4a16_qwen3vl_4B",
                        help="MNN 模型配置目录路径（需包含 config.json）")
    parser.add_argument("--datasets", nargs="+", default=["all"],
                        help="要评估的数据集名称，空格分隔；默认 all")
    parser.add_argument("--pred_dir", type=str, default="evaluate_result/preds",
                        help="预测结果保存/加载目录")
    parser.add_argument("--output_csv", type=str, default="evaluate_result/results.csv",
                        help="评估结果 CSV 输出路径")
    parser.add_argument("--num_samples", type=int, default=None,
                        help="每个数据集最多加载的样本数（用于快速测试）")
    parser.add_argument("--pope_strategy", type=str, default="random",
                        choices=["random", "popular", "adversarial"],
                        help="POPE 数据集的负采样策略")
    parser.add_argument("--force", action="store_true",
                        help="强制重新运行推理，忽略已有的预测结果")
    args = parser.parse_args()

    os.makedirs(args.pred_dir, exist_ok=True)
    output_dir = os.path.dirname(args.output_csv)
    if output_dir:
        os.makedirs(output_dir, exist_ok=True)

    # 确定要评估的数据集列表
    if args.datasets == ["all"]:
        dataset_names = list(DATASET_REGISTRY.keys())
    else:
        dataset_names = [d.lower() for d in args.datasets]

    # 初始化模型（如果 MNN 可用）
    model = None
    if MNN_AVAILABLE:
        model_path = os.path.abspath(args.model_path)
        # MNN 内部路径拼接有时缺少斜杠，确保目录路径以分隔符结尾
        if not model_path.endswith(os.sep):
            model_path += os.sep
        print(f"[INFO] Loading MNN model from {model_path}")
        try:
            model = MNNVLMWrapper(model_path)
        except Exception as e:
            print(f"[ERROR] Failed to load MNN model: {e}")
            model = None
    else:
        print("[INFO] MNN not available. Will try to load existing predictions.")

    evaluator = UnifiedEvaluator()
    results = {"model_name": args.model_name}

    for ds_name in dataset_names:
        print(f"\n========== Evaluating {ds_name} ==========")
        kwargs = {}
        if ds_name == "pope":
            kwargs["strategy"] = args.pope_strategy

        # 加载数据集
        try:
            samples = load_dataset(ds_name, num_samples=args.num_samples, **kwargs)
        except Exception as e:
            print(f"[ERROR] Failed to load dataset {ds_name}: {e}")
            continue

        if not samples:
            print(f"[WARN] Dataset {ds_name} is empty.")
            continue

        # 尝试加载已有预测
        pred_data = load_predictions(ds_name, args.pred_dir, args.pope_strategy)

        if args.force:
            pred_data = None

        if pred_data is None:
            if model is None:
                print(f"[SKIP] No existing predictions for {ds_name} and MNN is not available.")
                continue
            print(f"[INFO] Running inference on {len(samples)} samples...")
            pred_data = run_inference(model, ds_name, samples, args.pred_dir, args.pope_strategy)

        # 对齐 predictions 与 ground_truths
        predictions = [p["prediction"] for p in pred_data]
        ground_truths = [s["ground_truth"] for s in samples]

        if len(predictions) != len(ground_truths):
            print(f"[WARN] Prediction/GT length mismatch: {len(predictions)} vs {len(ground_truths)}")
            min_len = min(len(predictions), len(ground_truths))
            predictions = predictions[:min_len]
            ground_truths = ground_truths[:min_len]

        # 评估
        score = evaluator.evaluate(ds_name, predictions, ground_truths)
        results[ds_name] = round(score, 4)
        print(f"[RESULT] {ds_name}: {score:.4f}")

    # 写入 CSV
    fieldnames = ["model_name"] + dataset_names
    file_exists = os.path.exists(args.output_csv)
    with open(args.output_csv, 'a', newline='', encoding='utf-8') as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        if not file_exists:
            writer.writeheader()
        # 确保缺失字段留空
        row_to_write = {k: results.get(k, "") for k in fieldnames}
        writer.writerow(row_to_write)

    print(f"\n[INFO] Results saved to {args.output_csv}")
    print("Summary:", results)


if __name__ == "__main__":
    main()
