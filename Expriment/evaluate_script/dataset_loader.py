import os
import sys
import csv
import json
import ast
import requests
from abc import ABC, abstractmethod
from typing import List, Dict, Any

# 项目根目录
def _get_project_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PROJECT_ROOT = _get_project_root()
DATASET_ROOT = os.path.join(PROJECT_ROOT, "dataset")
CACHE_DIR = os.path.join(PROJECT_ROOT, "evaluate_result", "cache")


def ensure_dir(path: str):
    os.makedirs(path, exist_ok=True)


def download_image(url: str, cache_dir: str = CACHE_DIR) -> str:
    """下载图片到缓存目录，返回本地路径"""
    ensure_dir(cache_dir)
    # 从 URL 提取文件名，去掉查询参数
    basename = os.path.basename(url.split('?')[0])
    if not basename or '.' not in basename:
        basename = "img.jpg"
    local_path = os.path.join(cache_dir, basename)
    if os.path.exists(local_path):
        return local_path
    try:
        r = requests.get(url, timeout=60)
        r.raise_for_status()
        with open(local_path, 'wb') as f:
            f.write(r.content)
        return local_path
    except Exception as e:
        print(f"[WARN] Failed to download {url}: {e}")
        return url


class BaseDataset(ABC):
    name: str = ""

    @abstractmethod
    def load(self, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
        """
        返回统一格式的样本列表，每条样本包含：
        - dataset: 数据集名称
        - id: 样本唯一标识
        - image: 图片本地路径或 URL
        - prompt: 输入模型的文本提示
        - ground_truth: 标准答案（str 或 list）
        - metadata: 额外元信息 dict
        """
        pass

    def get_image_path(self, sample: Dict[str, Any]) -> str:
        """将 sample 中的 image 转为本地路径（如需下载会自动下载）"""
        img = sample.get("image")
        if img and os.path.isfile(img):
            return img
        if img and (img.startswith("http://") or img.startswith("https://")):
            return download_image(img)
        return img


class CCOCRDataset(BaseDataset):
    name = "CC-OCR"

    def load(self, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
        path = os.path.join(DATASET_ROOT, "CC-OCR", "data.csv")
        samples = []
        with open(path, 'r', encoding='utf-8-sig') as f:
            reader = csv.DictReader(f)
            for row in reader:
                samples.append({
                    "dataset": self.name,
                    "id": row.get("index", ""),
                    "image": row.get("image", ""),
                    "prompt": row.get("question", ""),
                    "ground_truth": row.get("answer", ""),
                    "metadata": {"image_name": row.get("image_name", "")}
                })
                if num_samples and len(samples) >= num_samples:
                    break
        return samples


class COCO2017Dataset(BaseDataset):
    name = "COCO2017"

    def load(self, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
        path = os.path.join(DATASET_ROOT, "coco2017", "validation_data.csv")
        samples = []
        with open(path, 'r', encoding='utf-8-sig') as f:
            reader = csv.DictReader(f)
            for row in reader:
                captions = json.loads(row.get("captions", "[]"))
                samples.append({
                    "dataset": self.name,
                    "id": row.get("image_id", ""),
                    "image": row.get("coco_url", ""),
                    "prompt": "Please describe this image in detail.",
                    "ground_truth": captions,
                    "metadata": {"file_name": row.get("file_name", "")}
                })
                if num_samples and len(samples) >= num_samples:
                    break
        return samples


class OKVQADataset(BaseDataset):
    name = "OK-VQA"

    def load(self, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
        q_path = os.path.join(DATASET_ROOT, "OK-VQA", "OpenEnded_mscoco_val2014_questions.json")
        a_path = os.path.join(DATASET_ROOT, "OK-VQA", "mscoco_val2014_annotations.json")

        with open(q_path, 'r', encoding='utf-8') as f:
            questions = {q["question_id"]: q for q in json.load(f)["questions"]}
        with open(a_path, 'r', encoding='utf-8') as f:
            annotations = {a["question_id"]: a for a in json.load(f)["annotations"]}

        samples = []
        for qid, q in questions.items():
            anno = annotations.get(qid, {})
            image_id = q["image_id"]
            image_name = f"COCO_val2014_{image_id:012d}.jpg"
            image_path = os.path.join(DATASET_ROOT, "coco_photo", "val2014", image_name)
            if not os.path.isfile(image_path):
                continue
            samples.append({
                "dataset": self.name,
                "id": qid,
                "image": image_path,
                "prompt": q["question"],
                "ground_truth": [ans["answer"] for ans in anno.get("answers", [])],
                "metadata": {"image_id": image_id}
            })
            if num_samples and len(samples) >= num_samples:
                break
        return samples


class VQAv2Dataset(BaseDataset):
    name = "VQAv2"

    def load(self, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
        q_path = os.path.join(DATASET_ROOT, "VQAv2", "v2_OpenEnded_mscoco_val2014_questions.json")
        a_path = os.path.join(DATASET_ROOT, "VQAv2", "v2_mscoco_val2014_annotations.json")

        with open(q_path, 'r', encoding='utf-8') as f:
            questions = {q["question_id"]: q for q in json.load(f)["questions"]}
        with open(a_path, 'r', encoding='utf-8') as f:
            annotations = {a["question_id"]: a for a in json.load(f)["annotations"]}

        samples = []
        for qid, q in questions.items():
            anno = annotations.get(qid, {})
            image_id = q["image_id"]
            image_name = f"COCO_val2014_{image_id:012d}.jpg"
            image_path = os.path.join(DATASET_ROOT, "coco_photo", "val2014", image_name)
            if not os.path.isfile(image_path):
                continue
            samples.append({
                "dataset": self.name,
                "id": qid,
                "image": image_path,
                "prompt": q["question"],
                "ground_truth": [ans["answer"] for ans in anno.get("answers", [])],
                "metadata": {"image_id": image_id}
            })
            if num_samples and len(samples) >= num_samples:
                break
        return samples


class POPEDataset(BaseDataset):
    name = "POPE"

    def load(self, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
        strategy = kwargs.get("strategy", "random")
        path = os.path.join(DATASET_ROOT, "POPE", "output", "coco", f"coco_pope_{strategy}.json")
        samples = []
        with open(path, 'r', encoding='utf-8') as f:
            for line in f:
                item = json.loads(line)
                image_name = item["image"]
                image_path = os.path.join(DATASET_ROOT, "coco_photo", "val2014", image_name)
                if not os.path.isfile(image_path):
                    continue
                samples.append({
                    "dataset": self.name,
                    "id": item["question_id"],
                    "image": image_path,
                    "prompt": item["text"],
                    "ground_truth": item["label"],  # yes / no
                    "metadata": {"strategy": strategy, "image_name": image_name}
                })
                if num_samples and len(samples) >= num_samples:
                    break
        return samples


class SA1BLongTextCaptionDataset(BaseDataset):
    name = "SA1B-LongTextCaption"

    def load(self, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
        path = os.path.join(DATASET_ROOT, "SA1B-LongTextCaption", "data.csv")
        # SA1B 的 cap_seg 字段可能非常大，需要提升 csv 字段大小限制
        max_int = sys.maxsize
        while True:
            try:
                csv.field_size_limit(max_int)
                break
            except OverflowError:
                max_int = max_int // 10
        samples = []
        with open(path, 'r', encoding='utf-8-sig') as f:
            reader = csv.reader(f)
            try:
                next(reader)  # skip header
            except StopIteration:
                return samples
            for row in reader:
                if len(row) < 3:
                    continue
                url = row[0]
                try:
                    cap_seg = ast.literal_eval(row[2])
                    if not isinstance(cap_seg, dict):
                        continue
                    global_cap = cap_seg.get("global_caption", "")
                    local_caps = cap_seg.get("local_caption", [])
                except Exception as e:
                    # 如果解析失败则跳过该行
                    continue
                samples.append({
                    "dataset": self.name,
                    "id": len(samples),
                    "image": url,
                    "prompt": "Please provide a detailed description of this image.",
                    "ground_truth": global_cap,
                    "metadata": {"local_captions": local_caps}
                })
                if num_samples and len(samples) >= num_samples:
                    break
        return samples


# 注册表
DATASET_REGISTRY = {
    "cc-ocr": CCOCRDataset,
    "coco2017": COCO2017Dataset,
    "ok-vqa": OKVQADataset,
    "vqav2": VQAv2Dataset,
    "pope": POPEDataset,
    "sa1b-longtextcaption": SA1BLongTextCaptionDataset,
}


def load_dataset(name: str, num_samples: int = None, **kwargs) -> List[Dict[str, Any]]:
    """
    按名称加载数据集。
    """
    name_lower = name.lower()
    if name_lower not in DATASET_REGISTRY:
        raise ValueError(
            f"Unknown dataset: {name}. Available: {list(DATASET_REGISTRY.keys())}"
        )
    dataset = DATASET_REGISTRY[name_lower]()
    return dataset.load(num_samples=num_samples, **kwargs)
