#!/usr/bin/env python3
"""测试使用 model.generate() 接口是否更稳定"""

import sys
import os
import time

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_2B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

def run_generate(text):
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
    
    ids = model.tokenizer_encode(prompt)
    print(f"Input ids length: {len(ids)}")
    
    t0 = time.time()
    output_ids = model.generate(ids)
    t1 = time.time()
    print(f"Output ids length: {len(output_ids)}")
    
    # 解码输出
    result = ""
    for tid in output_ids:
        result += model.tokenizer_decode(tid)
    
    print(f"Result: {repr(result[:200])}")
    print(f"Time: {t1-t0:.2f}s\n")
    return result

if __name__ == "__main__":
    for text in [
        "Is there a person in the image?",
        "Is there a snowboard in the image?",
        "Is there a car in the image?",
    ]:
        print(f"Prompt: {text}")
        run_generate(text)
