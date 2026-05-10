import torch
from PIL import Image
from transformers import AutoModelForImageTextToText, AutoProcessor
local_model_path = "/models/workspace/Bishe/origin_model/QWEN3VL-4B"
# 1. 加载模型和处理器 (关键：使用 AutoModelForImageTextToText)
model = AutoModelForImageTextToText.from_pretrained(
    local_model_path, # 推荐使用 8B 或 4B 版本测试
    dtype=torch.float16,  # 或 "auto"，或 torch.float16
    device_map="cuda:0"
)
import os
# 修复 CUDA 驱动兼容性：移除包含旧版本 libcuda.so 的路径
if 'LD_LIBRARY_PATH' in os.environ:
    paths = os.environ['LD_LIBRARY_PATH'].split(':')
    filtered = [p for p in paths if p not in ('/home/usr/local/cuda-12.4/compat', '/usr/local/cuda/compat/lib')]
    os.environ['LD_LIBRARY_PATH'] = ':'.join(filtered)

# 加载处理器，用于处理文本和多模态输入
processor = AutoProcessor.from_pretrained(local_model_path)

# 2. 加载图片
# 请将此路径替换为你的本地图片路径
image = Image.open("/models/workspace/Bishe/R-C.jpeg")

# 3. 定义消息 (messages)
# 消息格式遵循 OpenAI 的 Chat Completion API 风格，便于理解和使用[reference:6]
messages = [
    {
        "role": "user",
        "content": [
            {"type": "image"},  # 图像占位符
            {"type": "text", "text": "请描述这张图片的内容，并指出其中的主要物体。"}
        ]
    }
]

# 4. 应用聊天模板
# 这一步会将消息列表格式化为模型能理解的文本字符串
prompt = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)

# 5. 通过处理器编码输入 (关键步骤)
# 将文本和图像一起编码，生成模型需要的 inputs 字典
inputs = processor(text=prompt, images=[image], return_tensors="pt").to(model.device)

# 6. 模型生成 (推理)
with torch.no_grad():
    generated_ids = model.generate(**inputs, max_new_tokens=512, do_sample=False)

# 7. 解码并打印结果
# 截取模型生成的部分，去除输入部分
generated_ids_trimmed = [out_ids[len(in_ids):] for in_ids, out_ids in zip(inputs.input_ids, generated_ids)]
output_text = processor.batch_decode(generated_ids_trimmed, skip_special_tokens=True)[0]
print(output_text)