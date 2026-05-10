#!/usr/bin/env python3
"""快速测试数据加载器和评估器的基本功能"""

import os
import sys

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from dataset_loader import load_dataset
from evaluator import UnifiedEvaluator


def test_loader(dataset_name, num_samples=2, **kwargs):
    print(f"\n[Test Loader] {dataset_name}")
    try:
        samples = load_dataset(dataset_name, num_samples=num_samples, **kwargs)
        print(f"  Loaded {len(samples)} samples")
        if samples:
            s = samples[0]
            print(f"  Sample keys: {list(s.keys())}")
            print(f"  id={s['id']}, prompt={str(s['prompt'])[:60]}...")
            print(f"  gt_type={type(s['ground_truth'])}, gt_preview={str(s['ground_truth'])[:60]}...")
        return samples
    except Exception as e:
        print(f"  ERROR: {e}")
        import traceback
        traceback.print_exc()
        return []


def test_evaluator():
    print("\n[Test Evaluator]")
    ev = UnifiedEvaluator()

    # CC-OCR: char accuracy
    score = ev.evaluate("CC-OCR", ["hello world"], ["hello world"])
    print(f"  CC-OCR exact match: {score:.4f} (expect ~1.0)")
    score = ev.evaluate("CC-OCR", ["hallo world"], ["hello world"])
    print(f"  CC-OCR 1 char diff: {score:.4f}")

    # POPE: accuracy
    score = ev.evaluate("POPE", ["Yes, there is."], ["yes"])
    print(f"  POPE yes correct: {score:.4f} (expect 1.0)")
    score = ev.evaluate("POPE", ["No, there isn't."], ["yes"])
    print(f"  POPE yes wrong: {score:.4f} (expect 0.0)")

    # COCO2017: BLEU-1
    score = ev.evaluate("COCO2017", ["a cat on the mat"], [["a cat is on the mat", "there is a cat"]])
    print(f"  COCO2017 BLEU-1: {score:.4f}")

    # OK-VQA: keyword hit
    score = ev.evaluate("OK-VQA", ["racing car"], [["race", "racing", "motocross"]])
    print(f"  OK-VQA keyword hit: {score:.4f}")

    # VQAv2: ROUGE-L
    score = ev.evaluate("VQAv2", ["down the street"], [["down", "at table", "skateboard"]])
    print(f"  VQAv2 ROUGE-L best: {score:.4f}")

    # SA1B: ROUGE-L
    score = ev.evaluate("SA1B-LongTextCaption", ["a detailed caption"], ["a detailed caption of the scene"])
    print(f"  SA1B ROUGE-L: {score:.4f}")


if __name__ == "__main__":
    print("=" * 60)
    print("Testing Dataset Loaders")
    print("=" * 60)
    test_loader("CC-OCR", 2)
    test_loader("COCO2017", 2)
    test_loader("OK-VQA", 2)
    test_loader("VQAv2", 2)
    test_loader("POPE", 2, strategy="random")
    test_loader("SA1B-LongTextCaption", 2)

    print("\n" + "=" * 60)
    print("Testing Evaluator")
    print("=" * 60)
    test_evaluator()

    print("\n" + "=" * 60)
    print("All tests completed!")
    print("=" * 60)
