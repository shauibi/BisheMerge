#!/usr/bin/env python3
"""直接复刻极简测试脚本，但使用 POPE 的 prompt 进行对比"""

import sys
import os
import time

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_2B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

def run_once(text):
    import MNN.llm as llm
    import MNN.cv as cv
    model = llm.create(MODEL_PATH)
    model.load()
    img = cv.imread(IMG_PATH)
    h, w = (img.shape[0], img.shape[1]) if hasattr(img, 'shape') else (420, 420)
    prompt = {
        'text': f'<img>image_0</img>{text}',
        'images': [{'data': img, 'height': h, 'width': w}]
    }
    t0 = time.time()
    result = model.response(prompt, stream=False)
    t1 = time.time()
    print(f"Prompt: {text}")
    print(f"Result: {repr(result[:200])}")
    print(f"Time: {t1-t0:.2f}s\n")
    return result

if __name__ == "__main__":
    # 测试1：person（极简测试用过的）
    run_once("Is there a person in the image?")
    
    # 测试2：snowboard（POPE 第1条）
    run_once("Is there a snowboard in the image?")
    
    # 测试3：car（POPE 第2条）
    run_once("Is there a car in the image?")
