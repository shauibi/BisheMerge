#!/usr/bin/env python3
"""熵权法 + 帕累托前沿模型选择。

使用熵权法自动确定各数据集权重（区分度越高的数据集权重越大），
再结合帕累托前沿筛选最优模型。
"""

import csv
import math
import os
from itertools import accumulate

# ── 数据 ──────────────────────────────────────────────

_RESULTS_PATH = os.path.join(os.path.dirname(__file__), "..", "results.csv")
with open(_RESULTS_PATH) as f:
    reader = csv.DictReader(f)
    _MODELS = []
    _SCORES = {}
    for row in reader:
        name = row["model_name"]
        _MODELS.append(name)
        _SCORES[name] = {k: float(v) for k, v in row.items() if k != "model_name"}

_DATASETS = ["cc-ocr", "coco2017", "ok-vqa", "vqav2", "pope", "sa1b-longtextcaption"]

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


# ── 辅助 ──────────────────────────────────────────────

def decode_speed(name: str) -> float:
    return _SPEED_MEM[name]["decode"]


def memory(name: str) -> float:
    return _SPEED_MEM[name]["mem_gb"]


def dominates(a: str, b: str, avg_a: float, avg_b: float) -> bool:
    """a 是否支配 b（accurary + speed + memory 三维）。"""
    better = False
    if avg_a < avg_b:
        return False
    if avg_a > avg_b:
        better = True
    if decode_speed(a) < decode_speed(b):
        return False
    if decode_speed(a) > decode_speed(b):
        better = True
    if memory(a) > memory(b):
        return False
    if memory(a) < memory(b):
        better = True
    return better


# ── 熵权法 ────────────────────────────────────────────

def entropy_weights() -> tuple[dict, dict]:
    """
    返回 (weights, entropy_detail)。
    权重按数据集区分度自动分配：区分度越高（熵越低），权重越大。
    """
    n_models = len(_MODELS)
    m_datasets = len(_DATASETS)

    # Step 1: 构建评分矩阵 M[dataset][model]
    M = {}
    for d in _DATASETS:
        M[d] = [_SCORES[m][d] for m in _MODELS]

    # Step 2: 对所有数据集的评分相加得到总和矩阵
    #         归一化: p[i][j] = x[i][j] / sum(x[i][j] + 1e-9)
    #         对所有 j

    # Step 3: Min-Max 归一化（正向指标，所有分越高越好）
    M_norm = {}
    for d in _DATASETS:
        vals = M[d]
        vmin, vmax = min(vals), max(vals)
        if vmax == vmin:
            M_norm[d] = [0.5 for _ in vals]  # 全相同则给均匀值
        else:
            M_norm[d] = [(v - vmin) / (vmax - vmin + 1e-9) + 0.0001 for v in vals]

    # Step 4: 计算概率 p[i][j] = x'[i][j] / sum(x'[i][j])
    P = {}
    for d in _DATASETS:
        norm_vals = M_norm[d]
        total = sum(norm_vals)
        P[d] = [v / total for v in norm_vals]

    # Step 5: 计算每个数据集的熵
    k = 1.0 / math.log(n_models)  # 归一化因子
    entropy = {}
    for d in _DATASETS:
        e = 0.0
        for p in P[d]:
            if p > 0:
                e -= p * math.log(p)
        entropy[d] = e * k

    # Step 6: 计算权重 w = (1 - e) / sum(1 - e)
    divergence = {d: 1.0 - entropy[d] for d in _DATASETS}
    total_div = sum(divergence.values())
    weights = {d: divergence[d] / total_div for d in _DATASETS}

    detail = {
        "entropy": entropy,
        "divergence": divergence,
    }
    return weights, detail


# ── 主流程 ────────────────────────────────────────────

def main():
    weights, detail = entropy_weights()

    # 熵权法加权得分
    weighted_scores = {}
    for m in _MODELS:
        weighted_scores[m] = sum(_SCORES[m][d] * weights[d] for d in _DATASETS)

    models = _MODELS[:]

    # 帕累托前沿
    pareto = []
    for a in models:
        dom = any(dominates(b, a, weighted_scores[b], weighted_scores[a]) for b in models if b != a)
        if not dom:
            pareto.append(a)

    # 输出
    print("=" * 70)
    print("熵权法 + 帕累托前沿 模型选择")
    print("=" * 70)

    # 熵权法细节
    print()
    print("─" * 70)
    print("数据集权重（熵权法）")
    print("─" * 70)
    header = f"  {'Dataset':<25} {'Entropy':>8} {'Divergence':>12} {'Weight':>10}"
    print(header)
    print("  " + "-" * (len(header) - 2))
    for d in _DATASETS:
        print(f"  {d:<25} {detail['entropy'][d]:8.4f} {detail['divergence'][d]:12.4f} {weights[d]:10.4f}")
    print()

    # 模型加权得分
    print("─" * 70)
    print("熵权加权得分 vs 等权平均得分")
    print("─" * 70)
    header = f"  {'Model':<22} {'CC-OCR':>7} {'COCO17':>7} {'OK-VQA':>7} {'VQAv2':>6} {'POPE':>6} {'SA1B':>7} {'加权':>8} {'等权':>8} {'变化':>8}"
    print(header)
    print("  " + "-" * (len(header) - 2))
    for m in models:
        ws = weighted_scores[m]
        eq = sum(_SCORES[m][d] for d in _DATASETS) / len(_DATASETS)
        diff = ws - eq
        sign = "+" if diff > 0 else ""
        print(f"  {m:<22} {_SCORES[m]['cc-ocr']:7.4f} {_SCORES[m]['coco2017']:7.4f} {_SCORES[m]['ok-vqa']:7.4f} {_SCORES[m]['vqav2']:6.4f} {_SCORES[m]['pope']:6.1f} {_SCORES[m]['sa1b-longtextcaption']:7.4f} {ws:8.4f} {eq:8.4f} {sign}{diff:7.4f}")
    print()

    # 被淘汰
    dominated_models = [m for m in models if m not in pareto]
    if dominated_models:
        print("─" * 70)
        print("被淘汰的模型")
        print("─" * 70)
        for m in dominated_models:
            doms = [b for b in models if dominates(b, m, weighted_scores[b], weighted_scores[m])]
            print(f"  {m:<25} 支配者: {', '.join(doms)}")
        print()

    # 帕累托最优
    print("─" * 70)
    print("帕累托最优模型（按加权综合得分排序）")
    print("─" * 70)

    max_ws = max(weighted_scores[m] for m in pareto)
    max_dec = max(decode_speed(m) for m in pareto)
    max_mem_gb = max(memory(m) for m in pareto)

    scored = []
    for m in pareto:
        ws_norm = weighted_scores[m] / max_ws
        d_norm = decode_speed(m) / max_dec
        m_norm = 1.0 - memory(m) / max_mem_gb
        composite = 0.5 * ws_norm + 0.3 * d_norm + 0.2 * m_norm
        scored.append((composite, m))

    scored.sort(reverse=True)

    header = f"  {'Rank':<5} {'Model':<22} {'加权得分':>10} {'Decode':>8} {'Mem(GB)':>8} {'综合':>10}"
    print(header)
    print("  " + "-" * (len(header) - 2))
    for rank, (comp, m) in enumerate(scored, 1):
        print(f"  {rank:<5} {m:<22} {weighted_scores[m]:10.4f} {decode_speed(m):7.1f}t/s {memory(m):7.2f} {comp:10.4f}")
    print()

    # 最终选择
    best_comp, best_model = scored[0]
    print("=" * 70)
    print(f"  最终选择: {best_model}")
    print(f"    - 熵权加权得分:       {weighted_scores[best_model]:.4f}")
    print(f"    - decode 速度:        {decode_speed(best_model):.1f} tokens/s")
    print(f"    - 内存:               {memory(best_model):.2f} GB")
    print(f"    - 综合加权得分:       {best_comp:.4f}")
    print("=" * 70)


if __name__ == "__main__":
    main()
