import os
import re


def _truncate_repeated_text(text: str) -> str:
    """检测并截断模型输出中的连续重复模式。"""
    if not text:
        return text
    text = str(text)

    # 1. 紧密相连重复：如 'NoNoNoNo', 'BasedBasedBased'
    for repeat_len in range(2, 30):
        pattern = r'(.{' + str(repeat_len) + r'}?)\1{4,}'
        match = re.search(pattern, text)
        if match:
            base = match.group(1)
            end_pos = match.start() + len(base)
            return text[:end_pos].strip()

    # 2. 空格分隔的词级重复：如 '2. 2. 2. 2.'
    for pattern_len in range(1, 20):
        pattern = r'(\S{' + str(pattern_len) + r',})(?:\s+\1){3,}'
        match = re.search(pattern, text)
        if match:
            return text[:match.start()].strip()

    # 3. 单字符重复：如 'nnnnnnnnnnnnnnn'
    match = re.search(r'(.)\1{15,}', text)
    if match:
        return text[:match.start()].strip()

    # 4. 行级重复（完全相同的行连续出现 3 次）
    lines = text.split('\n')
    if len(lines) >= 3:
        for i in range(len(lines) - 2):
            if lines[i].strip() and lines[i] == lines[i + 1] == lines[i + 2]:
                return '\n'.join(lines[:i]).strip()

    return text.strip()


class MNNVLMWrapper:
    """
    基于 MNN Python API 的多模态大模型封装。
    4B 模型在复用实例时表现稳定，因此默认只加载一次模型，
    每次 predict 前调用 reset() 清理状态。
    同时包含输出后处理，截断明显的 token 重复。
    """

    def __init__(self, model_path: str):
        try:
            import MNN.llm as llm
            import MNN.cv as cv
            self._llm = llm
            self._cv = cv
        except ImportError as e:
            raise ImportError(
                "MNN Python package is not installed. "
                "Please build and install it from MNN/pymnn/pip_package/:\n"
                "  cd MNN/pymnn/pip_package && python3 build_deps.py && python3 setup.py install\n"
                f"Original error: {e}"
            )

        if not os.path.exists(model_path):
            raise FileNotFoundError(f"Model config not found: {model_path}")

        self.model_path = model_path

        # MNN 内部硬编码只读 tokenizer.txt，若不存在则从同目录 .mtok 复制一份
        txt_path = os.path.join(model_path, "tokenizer.txt")
        if not os.path.exists(txt_path):
            mtok_path = os.path.join(model_path, "tokenizer.mtok")
            if os.path.exists(mtok_path):
                import shutil
                shutil.copy2(mtok_path, txt_path)

        print(f"[MNNVLMWrapper] Loading model from {model_path}...")
        self.model = self._llm.create(model_path)
        self.model.set_config({"max_new_tokens": 128})
        self.model.load()
        print(f"[MNNVLMWrapper] Model loaded.")

    def predict(self, image_path: str, text: str, max_new_tokens: int = 128) -> str:
        """
        对单张图片 + 文本进行推理，返回清理后的模型生成字符串。
        支持本地路径和 HTTP(S) URL，URL 会自动下载到缓存目录。
        """
        self.model.reset()
        img_path = image_path
        # 如果是 URL，先下载到本地
        if image_path.startswith("http://") or image_path.startswith("https://"):
            from dataset_loader import download_image
            img_path = download_image(image_path)

        if not os.path.exists(img_path):
            print(f"[WARN] Image not found: {img_path}")
            return ""

        try:
            img = self._cv.imread(img_path)
            # 应用对话模板，构造符合 Qwen3-VL 格式的 prompt
            raw_text = f'<img>image_0</img>{text}'
            formatted_text = self.model.apply_chat_template(raw_text)
            prompt = {
                'text': formatted_text,
                'images': [
                    {
                        'data': img,
                        'height': 420,
                        'width': 420
                    }
                ]
            }

            result = self.model.response(prompt, stream=False)
            if isinstance(result, str):
                result = result.strip()
            else:
                result = str(result).strip()

            # 后处理：截断重复 token
            result = _truncate_repeated_text(result)
            return result
        except Exception as e:
            print(f"[ERROR] Inference failed: {e}")
            return ""
