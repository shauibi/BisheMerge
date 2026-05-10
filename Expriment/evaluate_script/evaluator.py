import os
import re
import string
from typing import List, Union

from rouge import Rouge
from nltk.translate.bleu_score import sentence_bleu, SmoothingFunction

_rouge = Rouge()

# 英文停用词表
_STOPWORDS = set([
    "a", "an", "the", "is", "are", "was", "were", "be", "been", "being",
    "have", "has", "had", "do", "does", "did", "will", "would", "could",
    "should", "may", "might", "must", "shall", "can", "need", "dare",
    "ought", "used", "to", "of", "in", "for", "on", "with", "at", "by",
    "from", "as", "into", "through", "during", "before", "after", "above",
    "below", "between", "under", "and", "but", "or", "yet", "so", "if",
    "because", "although", "though", "while", "where", "when", "that",
    "which", "who", "whom", "whose", "what", "this", "these", "those",
    "i", "me", "my", "myself", "we", "our", "ours", "ourselves", "you",
    "your", "yours", "yourself", "yourselves", "he", "him", "his", "himself",
    "she", "her", "hers", "herself", "it", "its", "itself", "they", "them",
    "their", "theirs", "themselves", "am", "are", "is", "was", "were",
    "being", "having", "doing", "until", "while", "up", "down", "out",
    "off", "over", "under", "again", "further", "then", "once", "here",
    "there", "why", "how", "all", "any", "both", "each", "few", "more",
    "most", "other", "some", "such", "no", "nor", "not", "only", "own",
    "same", "than", "too", "very", "s", "t", "just", "don", "now", "doesn",
    "didn", "wasn", "weren", "haven", "hasn", "hadn", "won", "wouldn",
    "shouldn", "isn", "aren", "couldn", "mightn", "mustn", "needn",
    "daren", "oughtn", "usedn", "ain", "ma", "o", "y"
])


def normalize_text(text: str) -> str:
    """小写、去首尾空格、去标点"""
    text = str(text).lower().strip()
    text = text.translate(str.maketrans('', '', string.punctuation))
    return text


def tokenize(text: str) -> List[str]:
    """分词并过滤空串"""
    return [w for w in normalize_text(text).split() if w]


def compute_rouge_l(pred: str, gt: str) -> float:
    """计算 ROUGE-L F1 分数"""
    try:
        scores = _rouge.get_scores(str(pred), str(gt))
        return scores[0]["rouge-l"]["f"]
    except Exception:
        return 0.0


def compute_bleu1(pred: str, gt: Union[str, List[str]]) -> float:
    """计算 BLEU-1 分数"""
    if isinstance(gt, str):
        gt = [gt]
    refs = [tokenize(g) for g in gt]
    hyp = tokenize(pred)
    if not hyp:
        return 0.0
    smoothing = SmoothingFunction().method1
    try:
        return sentence_bleu(refs, hyp, weights=(1.0, 0, 0, 0), smoothing_function=smoothing)
    except Exception:
        return 0.0


def compute_char_accuracy(pred: str, gt: str) -> float:
    """
    字符级准确率。
    使用最长公共子序列（LCS）长度 / GT 长度来衡量字符匹配程度。
    """
    pred = str(pred)
    gt = str(gt)
    if len(gt) == 0:
        return 1.0 if len(pred) == 0 else 0.0

    m, n = len(pred), len(gt)
    # 对于过长文本使用简化策略防止内存爆炸
    if m * n > 10_000_000:
        matches = sum(1 for a, b in zip(pred, gt) if a == b)
        return matches / max(len(gt), len(pred))

    # 滚动数组 DP 计算 LCS
    dp = [[0] * (n + 1) for _ in range(2)]
    for i in range(1, m + 1):
        cur = i & 1
        prev = 1 - cur
        for j in range(1, n + 1):
            if pred[i - 1] == gt[j - 1]:
                dp[cur][j] = dp[prev][j - 1] + 1
            else:
                dp[cur][j] = max(dp[prev][j], dp[cur][j - 1])
    lcs_len = dp[m & 1][n]
    return lcs_len / len(gt)


def compute_keyword_hit_rate(pred: str, gt_answers: List[str]) -> float:
    """
    关键词命中率：从 GT 答案中提取非停用词，计算预测中命中的比例。
    """
    keywords = set()
    for ans in gt_answers:
        for w in tokenize(ans):
            if w not in _STOPWORDS and len(w) > 1:
                keywords.add(w)
    if not keywords:
        return 0.0
    pred_words = set(tokenize(pred))
    hits = sum(1 for kw in keywords if kw in pred_words)
    return hits / len(keywords)


def compute_accuracy(pred: str, gt: str) -> float:
    """
    POPE 准确率。将预测文本归一化为 yes/no 后与标签对比。
    """
    pred_norm = normalize_text(pred)
    gt_norm = normalize_text(gt)
    # 若预测包含 no / not，则判定为 no
    pred_label = "no" if ("no" in pred_norm.split() or "not" in pred_norm.split()) else "yes"
    return 1.0 if pred_label == gt_norm else 0.0


class UnifiedEvaluator:
    """
    统一评估器，根据数据集名称自动选择评估指标。
    """

    def evaluate(self, dataset_name: str, predictions: List[str], ground_truths: List) -> float:
        """
        对单个数据集的一组预测进行评估，返回平均分。
        """
        name = dataset_name.lower().replace("-", "").replace("_", "")

        if name in ("ccocr", "ccocr"):
            return self._eval_ccocr(predictions, ground_truths)
        elif name in ("coco2017", "coco"):
            return self._eval_coco2017(predictions, ground_truths)
        elif name in ("okvqa", "okvqa"):
            return self._eval_okvqa(predictions, ground_truths)
        elif name in ("vqav2", "vqav2"):
            return self._eval_vqav2(predictions, ground_truths)
        elif name in ("pope", "pope"):
            return self._eval_pope(predictions, ground_truths)
        elif name in ("sa1blongtextcaption", "sa1b", "sa1blongtextcaption"):
            return self._eval_sa1b(predictions, ground_truths)
        else:
            raise ValueError(f"Unsupported dataset for evaluation: {dataset_name}")

    def _eval_ccocr(self, preds, gts):
        scores = [compute_char_accuracy(p, g) for p, g in zip(preds, gts)]
        return sum(scores) / len(scores) if scores else 0.0

    def _eval_coco2017(self, preds, gts):
        # gts 是 captions 列表的列表（每张图 5 个 caption）
        scores = [compute_bleu1(p, g) for p, g in zip(preds, gts)]
        return sum(scores) / len(scores) if scores else 0.0

    def _eval_okvqa(self, preds, gts):
        # gts 是 answers 列表的列表（每个问题 10 个答案）
        scores = [compute_keyword_hit_rate(p, g) for p, g in zip(preds, gts)]
        return sum(scores) / len(scores) if scores else 0.0

    def _eval_vqav2(self, preds, gts):
        # gts 是 answers 列表的列表，取与预测 ROUGE-L 最高的那个答案
        scores = []
        for p, g_list in zip(preds, gts):
            best = max([compute_rouge_l(p, g) for g in g_list])
            scores.append(best)
        return sum(scores) / len(scores) if scores else 0.0

    def _eval_pope(self, preds, gts):
        scores = [compute_accuracy(p, g) for p, g in zip(preds, gts)]
        return sum(scores) / len(scores) if scores else 0.0

    def _translate_zh_to_en(self, text: str) -> str:
        import hashlib
        import random
        import urllib.request
        import urllib.parse
        import json

        appid = os.environ.get("BAIDU_APPID", "")
        secret = os.environ.get("BAIDU_SECRET", "")
        if not appid or not secret:
            raise RuntimeError("BAIDU_APPID or BAIDU_SECRET env var not set")

        salt = str(random.randint(32768, 65536))
        sign_str = appid + text + salt + secret
        sign = hashlib.md5(sign_str.encode("utf-8")).hexdigest()

        params = {
            "q": text,
            "from": "zh",
            "to": "en",
            "appid": appid,
            "salt": salt,
            "sign": sign,
        }
        url = "https://fanyi-api.baidu.com/api/trans/vip/translate?" + urllib.parse.urlencode(params)
        with urllib.request.urlopen(url, timeout=10) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        if "error_code" in data:
            raise RuntimeError(f"Baidu translate error {data['error_code']}: {data.get('error_msg', '')}")
        return data["trans_result"][0]["dst"]

    def _eval_sa1b(self, preds, gts):
        gts_en = []
        for g in gts:
            gt_str = str(g)
            if any('一' <= ch <= '鿿' for ch in gt_str):
                try:
                    gts_en.append(self._translate_zh_to_en(gt_str))
                except Exception:
                    gts_en.append(gt_str)
            else:
                gts_en.append(gt_str)
        gts = gts_en

        scores = [compute_rouge_l(p, g) for p, g in zip(preds, gts)]
        return sum(scores) / len(scores) if scores else 0.0
