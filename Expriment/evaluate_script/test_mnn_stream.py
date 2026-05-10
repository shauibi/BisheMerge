#!/usr/bin/env python3
"""测试 MNN 模型使用 stream=True 连续推理时是否稳定"""

import sys
import os
import time

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_2B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

def main():
    import MNN.llm as llm
    import MNN.cv as cv

    print("Loading model...")
    model = llm.create(MODEL_PATH)
    model.load()
    print("Model loaded.\n")

    prompts = [
        "Is there a snowboard in the image?",
        "Is there a car in the image?",
        "Is there a person in the image?",
    ]

    for i, text in enumerate(prompts):
        img = cv.imread(IMG_PATH)
        h, w = (img.shape[0], img.shape[1]) if hasattr(img, 'shape') else (420, 420)
        prompt = {
            'text': f'<img>image_0</img>{text}',
            'images': [{'data': img, 'height': h, 'width': w}]
        }
        print(f"--- Sample {i+1}: {text} ---")
        t0 = time.time()
        result = model.response(prompt, stream=False)
        t1 = time.time()
        print(f"stream=False Result: {repr(result[:100])}")
        print(f"Time: {t1-t0:.2f}s")
        
        # 尝试 reset
        model.reset()
        model.generate_init()
        print("After reset + generate_init")
        
        # 再次用同样的prompt测试
        t0 = time.time()
        result2 = model.response(prompt, stream=False)
        t1 = time.time()
        print(f"After reset Result: {repr(result2[:100])}")
        print(f"Time: {t1-t0:.2f}s\n")

if __name__ == "__main__":
    main()
