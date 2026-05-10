#!/usr/bin/env python3
"""测试将图片尺寸固定为 420x420 后，模型输出是否稳定"""

import sys
import os
import time

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from model_wrapper import MNNVLMWrapper

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_2B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

def main():
    wrapper = MNNVLMWrapper(MODEL_PATH)
    prompts = [
        "Is there a person in the image?",
        "Is there a snowboard in the image?",
        "Is there a car in the image?",
    ]
    for text in prompts:
        print(f"Prompt: {text}")
        t0 = time.time()
        result = wrapper.predict(IMG_PATH, text)
        t1 = time.time()
        print(f"Result: {repr(result[:200])}")
        print(f"Time: {t1-t0:.2f}s\n")

if __name__ == "__main__":
    main()
