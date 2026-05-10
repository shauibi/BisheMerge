#!/usr/bin/env python3
"""测试使用 apply_chat_template 是否能改善模型输出"""

import sys
import os
import time

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_2B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

def run_with_template(text):
    import MNN.llm as llm
    import MNN.cv as cv
    
    model = llm.create(MODEL_PATH)
    model.load()
    
    img = cv.imread(IMG_PATH)
    raw_text = f'<img>image_0</img>{text}'
    formatted_text = model.apply_chat_template(raw_text)
    print(f"Formatted text: {repr(formatted_text[:120])}")
    
    prompt = {
        'text': formatted_text,
        'images': [{'data': img, 'height': 420, 'width': 420}]
    }
    
    t0 = time.time()
    result = model.response(prompt, stream=False)
    t1 = time.time()
    print(f"Result: {repr(result[:200])}")
    print(f"Time: {t1-t0:.2f}s\n")

if __name__ == "__main__":
    for text in [
        "Is there a person in the image?",
        "Is there a snowboard in the image?",
        "Is there a car in the image?",
    ]:
        print(f"Prompt: {text}")
        run_with_template(text)
