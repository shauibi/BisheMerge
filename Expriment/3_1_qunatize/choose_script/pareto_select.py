#!/usr/bin/env python3
"""帕累托前沿模型选择脚本。

从 accuracy / speed / memory 三个维度出发，
找出所有"不被任何其他模型支配"的模型（帕累托最优解），
并按综合加权得分排序输出。
"""

import csv
import os

# ── 数据 ──────────────────────────────────────────────

# 评估得分（来自 results.csv）
_SCORES = {}
_RESULTS_PATH = os.path.join(os.path.dirname(__file__), "..", "results.csv")
with open(_RESULTS_PATH) as f:
    for row in csv.DictReader(f):
        _SCORES[row["model_name"]] = {
            k: float(v) for k, v in row.items() if k != "model_name"
        }

# 速度 & 内存（来自 可用性.md / 时间和内存.xlsx 汇总表）
_SPEED_MEM = {
    "fp16":              {"prefill": 28.14, "decode": 3.49,  "mem_gb": 16.90},
    "fp16_eagle":        {"prefill": 14.33, "decode": 4.98,  "mem_gb": 17.28},
    "omniw4a16":         {"prefill": 50.44, "decode": 22.62, "mem_gb":  2.96},
    "omniw4a16_eagle":   {"prefill": 56.86, "decode": 31.89, "mem_gb":  2.93},
    "omniw8a8":          {"prefill": 38.61, "decode": 11.75, "mem_gb":  5.25},
    "omniw8a8_eagle":    {"prefill": 39.93, "decode": 18.77, "mem_gb":  5.25},
    "smoothw4a16":       {"prefill": 46.13, "decode": 19.26, "mem_gb":  2.93},
    "smoothw4a16_eagle": {"prefill": 52.91, "decode": 37.10, "mem_gb":  2.93},
}

# 参与评分的核心数据集（排除所有模型都得 1.0 的 POPE）
_SCORE_DATASETS = ["cc-ocr", "coco2017", "ok-vqa", "vqav2", "sa1b-longtextcaption"]


# ── 辅助函数 ──────────────────────────────────────────

def avg_score(name: str) -> float:
    """计算核心数据集平均得分（越高越好）。"""
    scores = _SCORES[name]
    return sum(scores[d] for d in _SCORE_DATASETS) / len(_SCORE_DATASETS)


def decode_speed(name: str) -> float:
    """decode 速度 (tokens/s)，越高越好。"""
    return _SPEED_MEM[name]["decode"]


def memory(name: str) -> float:
    """内存占用 (GB)，越低越好。"""
    return _SPEED_MEM[name]["mem_gb"]


def dominates(a: str, b: str) -> bool:
    """如果模型 a 在所有维度上都不差于 b 且至少一维严格更好，则 a 支配 b。"""
    better = False
    # 准确率：越高越好
    if avg_score(a) < avg_score(b):
        return False
    if avg_score(a) > avg_score(b):
        better = True
    # 速度：越高越好
    if decode_speed(a) < decode_speed(b):
        return False
    if decode_speed(a) > decode_speed(b):
        better = True
    # 内存：越低越好
    if memory(a) > memory(b):
        return False
    if memory(a) < memory(b):
        better = True
    return better


# ── 主流程 ────────────────────────────────────────────

def main():
    models = sorted(_SCORES.keys())

    # 1. 寻找帕累托前沿（不被任何其他模型支配的模型）
    pareto_frontier = []
    for a in models:
        dominated = any(dominates(b, a) for b in models if b != a)
        if not dominated:
            pareto_frontier.append(a)

    print("=" * 70)
    print("帕累托前沿分析 — 模型选择")
    print("=" * 70)
    print(f"  总候选模型:     {len(models)}")
    print(f"  帕累托最优解:   {len(pareto_frontier)}")
    print(f"  优化目标:       accuracy ↑, decode speed ↑, memory ↓")
    print()

    # 2. 列出 "被淘汰" 的模型及其被谁淘汰
    print("-" * 70)
    print("被淘汰的模型")
    print("-" * 70)
    dominated_models = [m for m in models if m not in pareto_frontier]
    for m in dominated_models:
        dominators = [b for b in models if dominates(b, m)]
        print(f"  {m:<25} 被淘汰，支配者: {', '.join(dominators)}")
    print()

    # 3. 帕累托最优模型详细信息
    print("-" * 70)
    print("帕累托最优模型")
    print("-" * 70)

    # 按综合加权得分排序（仅帕累托最优）
    max_avg = max(avg_score(m) for m in pareto_frontier)
    max_dec = max(decode_speed(m) for m in pareto_frontier)
    max_mem = max(memory(m) for m in pareto_frontier)

    scored = []
    for m in pareto_frontier:
        s_norm = avg_score(m) / max_avg
        d_norm = decode_speed(m) / max_dec
        m_norm = 1.0 - memory(m) / max_mem
        # 50% 效果, 30% 速度, 20% 内存
        composite = 0.5 * s_norm + 0.3 * d_norm + 0.2 * m_norm
        scored.append((composite, m))

    scored.sort(reverse=True)

    header = f"  {'Rank':<5} {'Model':<22} {'Avg Score':>10} {'Decode':>8} {'Mem(GB)':>8} {'Composite':>10}"
    print(header)
    print("  " + "-" * (len(header) - 2))

    for rank, (comp, m) in enumerate(scored, 1):
        print(f"  {rank:<5} {m:<22} {avg_score(m):10.4f} {decode_speed(m):7.1f}t/s {memory(m):7.2f} {comp:10.4f}")

    print()

    # 4. 最终选择（取综合得分最高的一个）
    best_comp, best_model = scored[0]
    print("=" * 70)
    print(f"  最终选择: {best_model}")
    print(f"    - 平均得分:           {avg_score(best_model):.4f}")
    print(f"    - decode 速度:        {decode_speed(best_model):.1f} tokens/s")
    print(f"    - 内存:               {memory(best_model):.2f} GB")
    print(f"    - 综合加权得分:       {best_comp:.4f}")
    print("=" * 70)


if __name__ == "__main__":
    main()
