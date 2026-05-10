#!/usr/bin/env python3
"""极简 MNN 推理测试脚本，用于验证模型能否正常加载和推理。"""

import sys
import os
import time

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_2B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000262148.jpg"

def main():
    print("[1/5] Importing MNN modules...")
    import MNN.llm as llm
    import MNN.cv as cv
    print("[1/5] OK")

    print(f"[2/5] Creating model from {MODEL_PATH}...")
    model = llm.create(MODEL_PATH)
    print("[2/5] OK")

    print("[3/5] Loading model weights...")
    t0 = time.time()
    model.load()
    t1 = time.time()
    print(f"[3/5] OK (load time: {t1-t0:.2f}s)")

    print(f"[4/5] Loading image {IMG_PATH}...")
    img = cv.imread(IMG_PATH)
    h, w = (img.shape[0], img.shape[1]) if hasattr(img, 'shape') else (420, 420)
    print(f"[4/5] OK (h={h}, w={w})")

    prompt = {
        'text': '<img>image_0</img>Is there a person in the image?',
        'images': [{'data': img, 'height': h, 'width': w}]
    }

    print("[5/5] Running inference (max 60s)...")
    t0 = time.time()
    result = model.response(prompt, stream=False)
    t1 = time.time()
    print(f"[5/5] OK (inference time: {t1-t0:.2f}s)")
    print("=" * 60)
    print("RESULT:", result)
    print("=" * 60)

if __name__ == "__main__":
    main()
