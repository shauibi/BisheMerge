#!/usr/bin/env python3
"""精确模拟 model_wrapper.py 的调用方式，测试 4B 模型连续推理稳定性"""

import sys
import os

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from model_wrapper import MNNVLMWrapper

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_4B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

wrapper = MNNVLMWrapper(MODEL_PATH)

for text in [
    "Is there a snowboard in the image?",
    "Is there a car in the image?",
    "Is there a person in the image?",
]:
    result = wrapper.predict(IMG_PATH, text)
    print(f"{text}: {repr(result[:120])}")
