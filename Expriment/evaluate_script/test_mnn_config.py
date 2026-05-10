#!/usr/bin/env python3
"""测试通过 set_config 设置 max_new_tokens 是否能限制生成长度"""

import MNN.llm as llm
import MNN.cv as cv

MODEL_PATH = "/home/aispeech/remote-projects/workspace/Bishe/MNN/transformers/llm/export/model_w4a16_qwen3vl_4B/"
IMG_PATH = "/home/aispeech/remote-projects/workspace/Bishe/dataset/coco_photo/val2014/COCO_val2014_000000310196.jpg"

model = llm.create(MODEL_PATH)
model.load()

print("Setting max_new_tokens=8...")
ret = model.set_config({"max_new_tokens": 8})
print(f"set_config returned: {ret}")

img = cv.imread(IMG_PATH)
prompt = {
    'text': model.apply_chat_template('<img>image_0</img>Is there a person in the image?'),
    'images': [{'data': img, 'height': 420, 'width': 420}]
}

import time
t0 = time.time()
result = model.response(prompt, stream=False)
t1 = time.time()
print(f"Result: {repr(result)}")
print(f"Time: {t1-t0:.2f}s")
