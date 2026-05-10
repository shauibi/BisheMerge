#!/usr/bin/env python3
"""测试使用手动 generate_init + forward 循环是否能避免 token 重复"""

import sys
import os
import time

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_2B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

def run_manual(text, max_new_tokens=16):
    import MNN.llm as llm
    import MNN.cv as cv
    import MNN.numpy as np
    
    model = llm.create(MODEL_PATH)
    model.load()
    
    img = cv.imread(IMG_PATH)
    h, w = (img.shape[0], img.shape[1]) if hasattr(img, 'shape') else (420, 420)
    
    prompt_text = f'<img>image_0</img>{text}'
    prompt_text = model.apply_chat_template(prompt_text)
    prompt = {
        'text': prompt_text,
        'images': [{'data': img, 'height': h, 'width': w}]
    }
    
    ids = model.tokenizer_encode(prompt)
    model.generate_init()
    logits = model.forward(ids)
    token = np.argmax(logits)
    model.context.current_token = token
    
    result = model.tokenizer_decode(token)
    for i in range(max_new_tokens - 1):
        logits = model.forward(token)
        token = np.argmax(logits)
        model.context.current_token = token
        if model.stoped():
            break
        word = model.tokenizer_decode(token)
        result += word
    
    return result

if __name__ == "__main__":
    for text in [
        "Is there a person in the image?",
        "Is there a snowboard in the image?",
        "Is there a car in the image?",
    ]:
        print(f"Prompt: {text}")
        t0 = time.time()
        result = run_manual(text, max_new_tokens=16)
        t1 = time.time()
        print(f"Result: {repr(result)}")
        print(f"Time: {t1-t0:.2f}s\n")
