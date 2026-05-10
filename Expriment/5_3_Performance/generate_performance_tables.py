import re
import pandas as pd
from pathlib import Path

def parse_benchmark(txt_path: str, xlsx_path: str = "benchmark_summary.xlsx"):
    """解析 benchmark 报告 txt，生成 Excel 文件"""
    with open(txt_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    # 数据结构
    data = {
        "load_times": [],
        "load_avg": None,
        "baseline_pss": None,
        "loaded_pss": None,
        "delta_pss": None,
        "inference": [],
        "rag_columns": None,
    }

    section = None
    inference_header = None

    for line in lines:
        line = line.strip()
        if not line:
            continue

        # 识别章节
        if line.startswith("--- Load Time (ms) ---"):
            section = "load"
            continue
        elif line.startswith("--- Memory ---"):
            section = "memory"
            continue
        elif line.startswith("--- Inference ---"):
            section = "inference"
            inference_header = None   # 重置
            continue
        elif line.startswith("--- RAG Latency (ms) ---"):
            section = "rag"
            continue

        # 解析 Load Time
        if section == "load":
            if line.startswith("Runs:"):
                # 提取 "6698, 6882, 6074"
                nums = re.findall(r"\d+", line)
                data["load_times"] = [int(n) for n in nums]
            elif line.startswith("Average:"):
                avg = re.search(r"(\d+)", line)
                if avg:
                    data["load_avg"] = int(avg.group(1))

        # 解析 Memory
        elif section == "memory":
            if "Baseline PSS:" in line:
                match = re.search(r"(\d+)", line)
                if match:
                    data["baseline_pss"] = int(match.group(1))
            elif "Loaded PSS:" in line:
                match = re.search(r"(\d+)", line)
                if match:
                    data["loaded_pss"] = int(match.group(1))
            elif "Delta:" in line:
                match = re.search(r"(\d+)", line)
                if match:
                    data["delta_pss"] = int(match.group(1))

        # 解析 Inference
        elif section == "inference":
            # 跳过表头行? 先获取表头
            if inference_header is None:
                # 表头示例：Prompt,TNFT (ms),Output Tokens,Prefill (ms),Decode (ms),Tokens/s
                if "Prompt" in line and "TNFT" in line:
                    inference_header = [col.strip() for col in line.split(",")]
                continue
            else:
                # 数据行（以 10t, 50t 等开头）
                if re.match(r"\d+t,", line):
                    parts = line.split(",")
                    if len(parts) >= 6:
                        row = [part.strip() for part in parts[:6]]
                        data["inference"].append(row)

        # 解析 RAG（仅表头，无数据）
        elif section == "rag":
            if "Chunks" in line and "Embedding" in line:
                data["rag_columns"] = [col.strip() for col in line.split(",")]
            # 忽略可能存在的空数据行

    # 构建 Excel
    with pd.ExcelWriter(xlsx_path, engine="openpyxl") as writer:
        # Sheet 1: Load Time
        if data["load_times"] and data["load_avg"] is not None:
            df_load = pd.DataFrame({
                "Run": [f"Run_{i+1}" for i in range(len(data["load_times"]))],
                "Load_Time_ms": data["load_times"],
            })
            df_load.loc["Average"] = ["Average", data["load_avg"]]
            df_load.to_excel(writer, sheet_name="Load Time", index=False)

        # Sheet 2: Memory
        df_mem = pd.DataFrame({
            "Metric": ["Baseline PSS (KB)", "Loaded PSS (KB)", "Delta (KB)"],
            "Value": [data["baseline_pss"], data["loaded_pss"], data["delta_pss"]]
        })
        df_mem.to_excel(writer, sheet_name="Memory", index=False)

        # Sheet 3: Inference
        if inference_header and data["inference"]:
            df_infer = pd.DataFrame(data["inference"], columns=inference_header)
            # 转换数值列以便分析
            numeric_cols = ["TNFT (ms)", "Output Tokens", "Prefill (ms)", "Decode (ms)", "Tokens/s"]
            for col in numeric_cols:
                if col in df_infer.columns:
                    df_infer[col] = pd.to_numeric(df_infer[col], errors="coerce")
            df_infer.to_excel(writer, sheet_name="Inference", index=False)

        # Sheet 4: RAG (仅结构)
        if data["rag_columns"]:
            df_rag = pd.DataFrame(columns=data["rag_columns"])
            # 添加一行提示
            df_rag.loc[0] = ["(No data in report)"] * len(data["rag_columns"])
            df_rag.to_excel(writer, sheet_name="RAG Latency", index=False)
        else:
            # 创建空表
            pd.DataFrame(["RAG data missing"]).to_excel(writer, sheet_name="RAG Latency", index=False, header=False)

    print(f"✅ Excel 文件已生成：{Path(xlsx_path).absolute()}")

if __name__ == "__main__":
    # 使用示例（请根据实际文件路径修改）
    parse_benchmark("benchmark_NoEagle_1778293757777[1].txt", "benchmark_summary.xlsx")