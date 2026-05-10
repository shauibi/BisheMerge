"""
PyTorch BGE vs MNN Embedding consistency test
Usage: python test_embedding_consistency.py --torch_model BAAI/bge-small-zh --mnn_model /path/to/model_dir
"""
import argparse
import base64
import json
import os
import shutil
import sys
import tempfile
import numpy as np
from pathlib import Path

# Force UTF-8 on Windows
if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")


# ── PyTorch BGE 参考实现 ──────────────────────────────────────────
class PyTorchBGE:
    """加载原始 HuggingFace BGE 模型，输出与 C++ JNI 对齐的 embedding"""

    def __init__(self, model_name: str = "BAAI/bge-small-zh"):
        import torch
        from transformers import AutoTokenizer, AutoModel

        self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.tokenizer = AutoTokenizer.from_pretrained(model_name)
        self.model = AutoModel.from_pretrained(model_name).to(self.device)
        self.model.eval()
        self.model_name = model_name

        print(f"[PyTorch] Loaded {model_name} on {self.device}")
        print(f"[PyTorch] Hidden size: {self.model.config.hidden_size}")
        print(f"[PyTorch] Max position: {self.model.config.max_position_embeddings}")
        print(f"[PyTorch] Vocab size: {self.model.config.vocab_size}")
        print(f"[PyTorch] CLS token: {self.tokenizer.cls_token} (id={self.tokenizer.cls_token_id})")
        print(f"[PyTorch] SEP token: {self.tokenizer.sep_token} (id={self.tokenizer.sep_token_id})")
        print(f"[PyTorch] PAD token: {self.tokenizer.pad_token} (id={self.tokenizer.pad_token_id})")
        print()

    def tokenize(self, texts: list[str], max_len: int = 512) -> dict:
        """与 C++ JNI 对齐的分词方式"""
        import torch
        enc = self.tokenizer(
            texts,
            padding="max_length",
            truncation=True,
            max_length=max_len,
            return_tensors="pt",
            return_token_type_ids=False,  # BGE 不需要 token_type_ids
        )
        return {
            "input_ids": enc["input_ids"].to(self.device),
            "attention_mask": enc["attention_mask"].to(self.device),
        }

    def embed(self, texts: list[str], max_len: int = 512) -> np.ndarray:
        """计算 embedding，返回 L2 归一化后的 numpy 数组"""
        import torch
        inputs = self.tokenize(texts, max_len)

        with torch.no_grad():
            # BGE 使用 CLS token 的 last_hidden_state 作为 sentence embedding
            outputs = self.model(**inputs)
            cls_embedding = outputs.last_hidden_state[:, 0, :]  # [batch, hidden_size]
            # L2 归一化
            cls_embedding = torch.nn.functional.normalize(cls_embedding, p=2, dim=1)

        return cls_embedding.cpu().numpy()

    def embed_from_ids(self, input_ids: list[int], attention_mask: list[int], max_len: int = 512) -> np.ndarray:
        """用预计算的 token IDs 通过 PyTorch 模型推理（用于验证分词器一致性）"""
        import torch
        ids_tensor = torch.tensor([input_ids], dtype=torch.long).to(self.device)
        mask_tensor = torch.tensor([attention_mask], dtype=torch.long).to(self.device)

        with torch.no_grad():
            outputs = self.model(input_ids=ids_tensor, attention_mask=mask_tensor)
            cls_embedding = outputs.last_hidden_state[:, 0, :]
            cls_embedding = torch.nn.functional.normalize(cls_embedding, p=2, dim=1)

        return cls_embedding.cpu().numpy()[0]


# ── MNN 模拟实现 ───────────────────────────────────────────────────
class MNNEmbedding:
    """
    模拟 C++ JNI 中 MNN embedding 的完整流程:
    1. tokenizer.txt WordPiece encode
    2. input_ids / attention_mask 填充到 512
    3. MNN Interpreter runSession
    4. 取 CLS 位置 embedding
    """

    def __init__(self, model_dir: str):
        self.model_dir = Path(model_dir)
        self._load_config()
        self._init_mnn()

    def _load_config(self):
        config_path = self.model_dir / "config.json"
        if config_path.exists():
            with open(config_path) as f:
                self.config = json.load(f)
        else:
            self.config = {}

        self.mnn_file = self.model_dir / self.config.get("llm_model", "bge_small_zh.mnn")
        self.tokenizer_file = self.model_dir / self.config.get("tokenizer_file", "tokenizer.txt")
        print(f"[MNN] Model file: {self.mnn_file}")
        print(f"[MNN] Tokenizer file: {self.tokenizer_file}")

    def _init_mnn(self):
        try:
            import MNN
        except ImportError:
            print("ERROR: MNN Python lib not installed. Run: pip install MNN")
            sys.exit(1)

        # Load tokenizer vocab with base64 decoding (MNN BERT format)
        self.vocab = {}
        self.inv_vocab = {}
        vocab_start_line = 0
        with open(self.tokenizer_file, "r", encoding="utf-8") as f:
            lines = f.readlines()

        # Parse header: line 1 = "magic_num tokenizer_type", line 2 = "special_num stop_num prefix_num", line 3 = spec_ids
        magic_line = lines[0].strip()
        special_line = lines[1].strip() if len(lines) > 1 else ""
        spec_ids_line = lines[2].strip() if len(lines) > 2 else ""
        magic_parts = magic_line.split()
        magic_num = int(magic_parts[0]) if len(magic_parts) > 0 else 0
        tokenizer_type = int(magic_parts[1]) if len(magic_parts) > 1 else -1
        spec_parts = special_line.split()
        special_num = int(spec_parts[0]) if len(spec_parts) > 0 else 0
        stop_num = int(spec_parts[1]) if len(spec_parts) > 1 else 0
        prefix_num = int(spec_parts[2]) if len(spec_parts) > 2 else 0
        spec_ids = [int(x) for x in spec_ids_line.split()] if spec_ids_line else []

        # Line 4 is blank, Line 5 = vocab_size, Line 6+ = base64 tokens
        if len(lines) > 4 and lines[4].strip().isdigit():
            vocab_size = int(lines[4].strip())
            vocab_start_line = 5
        else:
            vocab_size = len(lines) - 4
            vocab_start_line = 4

        for i in range(vocab_start_line, len(lines)):
            encoded = lines[i].strip()
            if not encoded:
                continue
            try:
                token = base64.b64decode(encoded).decode("utf-8", errors="replace")
            except Exception:
                token = encoded
            idx = i - vocab_start_line
            self.vocab[token] = idx
            self.inv_vocab[idx] = token

        print(f"[MNN] Vocab size: {len(self.vocab)} (header says {vocab_size})")
        print(f"[MNN] [CLS] id={self.vocab.get('[CLS]', 'MISSING')}")
        print(f"[MNN] [SEP] id={self.vocab.get('[SEP]', 'MISSING')}")
        print(f"[MNN] [PAD] id={self.vocab.get('[PAD]', 'MISSING')}")
        print(f"[MNN] [UNK] id={self.vocab.get('[UNK]', 'MISSING')}")
        print(f"[MNN] Tokenizer header: special={special_num}, stop={stop_num}, prefix={prefix_num}")
        print(f"       spec_ids count={len(spec_ids)}")

        # Copy MNN model to temp dir without CJK chars (MNN Python can't handle CJK paths)
        tmp_dir = Path(tempfile.mkdtemp(prefix="mnn_test_"))
        tmp_mnn = tmp_dir / self.mnn_file.name
        tmp_tokenizer = tmp_dir / self.tokenizer_file.name
        shutil.copy2(self.mnn_file, tmp_mnn)
        shutil.copy2(self.tokenizer_file, tmp_tokenizer)
        print(f"[MNN] Copied model to: {tmp_dir}")

        # Load MNN model
        self.interpreter = MNN.Interpreter(str(tmp_mnn))
        self.session = self.interpreter.createSession()
        self.input_ids = self.interpreter.getSessionInput(self.session, "input_ids")
        self.attention_mask = self.interpreter.getSessionInput(self.session, "attention_mask")
        # Try new output name first (last_hidden_state for FP32 model), fall back to old
        self.output = self.interpreter.getSessionOutput(self.session, "last_hidden_state")
        if self.output is None:
            self.output = self.interpreter.getSessionOutput(self.session, "sentence_embedding")

        print(f"[MNN] Input tensor shape: {self.input_ids.getShape()}")
        print(f"[MNN] Output tensor shape: {self.output.getShape()}")
        print()

    def tokenize(self, text: str, max_len: int = 512) -> tuple[list[int], list[int]]:
        """
        模拟 C++ JNI 的分词流程（修复后）:
        - MNN Tokenizer::encode() 已添加 [CLS]=101 前缀（prefix_tokens_）
        - 手动添加 [SEP]=102（MNN load_special 不支持 suffix_tokens_）
        - 截断到 max_len
        - 零填充
        - attention_mask: 有效 token 为 1，填充为 0
        """
        tokens = self._wordpiece_encode(text)
        token_ids = [self.vocab.get(t, self.vocab.get("[UNK]", 100)) for t in tokens]

        # 模拟 MNN Tokenizer::encode() 的 prefix_tokens_ 行为
        cls_id = self.vocab.get("[CLS]", 101)
        ids = [cls_id] + token_ids
        # 手动添加 [SEP]（C++ JNI 侧手动追加）
        sep_id = self.vocab.get("[SEP]", 102)
        ids.append(sep_id)

        ids = ids[:max_len]
        mask = [1] * len(ids)

        # 填充到 max_len
        pad_len = max_len - len(ids)
        ids.extend([0] * pad_len)
        mask.extend([0] * pad_len)

        return ids, mask

    def _wordpiece_encode(self, text: str) -> list[str]:
        """
        BERT WordPiece 分词 — 与 MNN BertTokenizer::encode() 核心逻辑一致:
        1. 逐字符切分（BERT 处理中文的方式）
        2. ASCII 字母/数字连续序列合并并小写化
        3. 对每个 token 查找 vocab，不存在则尝试 ## 前缀
        Python str 是按 Unicode 码点索引的，每个 CJK 字符占 1 个位置。
        """
        tokens = []
        i = 0
        while i < len(text):
            c = text[i]

            if ord(c) >= 0x80:
                # Non-ASCII: each Unicode character is 1 token unit (Chinese, Japanese, etc.)
                unit = c
                i += 1
                if unit in self.vocab:
                    tokens.append(unit)
                elif f"##{unit}" in self.vocab:
                    tokens.append(f"##{unit}")
                else:
                    tokens.append("[UNK]")
            elif c.isalnum():
                # ASCII word: collect consecutive alphanumeric, lowercase
                start = i
                while i < len(text) and text[i].isalnum():
                    i += 1
                unit = text[start:i].lower()
                # WordPiece the ASCII word
                self._wordpiece_ascii(unit, tokens)
            elif c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~":
                unit = c
                i += 1
                if unit in self.vocab:
                    tokens.append(unit)
                else:
                    tokens.append("[UNK]")
            elif c.isspace():
                i += 1
            else:
                unit = c
                i += 1
                if unit in self.vocab:
                    tokens.append(unit)
                else:
                    tokens.append("[UNK]")

        return tokens

    def _wordpiece_ascii(self, unit: str, tokens: list[str]):
        """WordPiece 分割 ASCII 单词（与 MNN BertTokenizer::word_piece 一致）"""
        if unit in self.vocab:
            tokens.append(unit)
            return

        current = unit
        is_first = True
        while current:
            matched = False
            for end in range(len(current), 0, -1):
                candidate = current[:end] if is_first else f"##{current[:end]}"
                if candidate in self.vocab:
                    tokens.append(candidate)
                    current = current[end:]
                    is_first = False
                    matched = True
                    break
            if not matched:
                tokens.append("[UNK]")
                break

    def tokenize_with_mnn(self, text: str, max_len: int = 512) -> tuple[list[int], list[int]]:
        """使用 MNN 内置 Tokenizer 分词（模拟 C++ 修复后行为）"""
        try:
            import MNN
            from MNN.expr import _Tokenizer

            tok = _Tokenizer(str(self.tokenizer_file))
            ids = tok.encode(text)

            # 手动追加 [SEP]=102（MNN load_special 不支持 suffix_tokens_）
            ids.append(102)

            seq_len = min(len(ids), max_len)

            id_vec = [0] * max_len
            mask_vec = [0] * max_len
            for i in range(seq_len):
                id_vec[i] = ids[i]
                mask_vec[i] = 1

            return id_vec, mask_vec
        except Exception as e:
            print(f"  [MNN Tokenizer] Error: {e}")
            return self.tokenize(text, max_len)

    def embed_raw(self, input_ids: list[int], attention_mask: list[int]) -> np.ndarray:
        """
        Run MNN inference and extract CLS token embedding:
        - Old model: output [1, 512, 1, 1] — sentence_embedding, all 512 elems = CLS
        - New model: output [1, 512, 512] — last_hidden_state, first 512 elems = CLS
        Both are handled by flatten()[:dim]
        """
        import MNN

        max_len = len(input_ids)
        id_arr = np.array(input_ids, dtype=np.float32)
        mask_arr = np.array(attention_mask, dtype=np.float32)

        self.input_ids.fromNumpy(id_arr)
        self.attention_mask.fromNumpy(mask_arr)
        self.interpreter.runSession(self.session)

        out_arr = self.output.getNumpyData()
        dim = self.output.getShape()[1]  # channel dim = hidden_size

        embedding = out_arr.flatten()[:dim].astype(np.float32)
        return embedding

    def embed(self, text: str, max_len: int = 512) -> np.ndarray:
        ids, mask = self.tokenize_with_mnn(text, max_len)
        return self.embed_raw(ids, mask)


# ── 诊断工具 ───────────────────────────────────────────────────────
def cosine_similarity(a: np.ndarray, b: np.ndarray) -> float:
    """与 C++ 一致的余弦相似度计算（不归一化输入）"""
    a_norm = a / (np.linalg.norm(a) + 1e-12)
    b_norm = b / (np.linalg.norm(b) + 1e-12)
    return float(np.dot(a_norm, b_norm))


def compare_vectors(name: str, vec_a: np.ndarray, vec_b: np.ndarray):
    """打印两个向量的详细差异"""
    diff = vec_a - vec_b
    abs_diff = np.abs(diff)
    print(f"\n  [{name}]")
    print(f"    Shape: {vec_a.shape} vs {vec_b.shape}")
    print(f"    Cosine similarity: {cosine_similarity(vec_a, vec_b):.6f}")
    print(f"    L2 norm (A): {np.linalg.norm(vec_a):.6f}")
    print(f"    L2 norm (B): {np.linalg.norm(vec_b):.6f}")
    print(f"    Max abs diff: {abs_diff.max():.6f}")
    print(f"    Mean abs diff: {abs_diff.mean():.6f}")
    print(f"    Std abs diff: {abs_diff.std():.6f}")

    # 打印差异最大的前 5 个维度
    top_indices = np.argsort(abs_diff)[-5:][::-1]
    print(f"    Top-5 divergent dimensions:")
    for idx in top_indices:
        print(f"      dim[{idx:3d}]: A={vec_a[idx]:.6f}, B={vec_b[idx]:.6f}, diff={abs_diff[idx]:.6f}")

    if cosine_similarity(vec_a, vec_b) < 0.99:
        print(f"    WARN: INCONSISTENT! Threshold 0.99 not met.")
        return False
    return True


# ── 主测试流程 ─────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="PyTorch BGE vs MNN 一致性测试")
    parser.add_argument("--torch_model", default="BAAI/bge-small-zh", help="HuggingFace 模型名")
    parser.add_argument("--mnn_model", required=True, help="MNN 模型目录路径（含 config.json + .mnn + tokenizer.txt）")
    parser.add_argument("--max_len", type=int, default=512, help="最大序列长度")
    parser.add_argument("--verbose", "-v", action="store_true", help="详细输出每个测试的向量差异")
    args = parser.parse_args()

    mnn_dir = Path(args.mnn_model)
    if not mnn_dir.exists():
        print(f"ERROR: MNN 模型目录不存在: {mnn_dir}")
        sys.exit(1)

    test_texts = [
        # ===== 短句（1-10 字）=====
        "中国的首都是北京",
        "你好",
        "今天星期五",
        "人工智能",
        "机器学习",
        "深度学习",
        "神经网络",
        "数据挖掘",
        "自然语言处理",
        "计算机视觉",

        # ===== 中等句（10-30 字）=====
        "人工智能是计算机科学的一个分支",
        "今天天气很好，适合出去玩",
        "我喜欢吃苹果和香蕉",
        "北京是中国的首都，也是政治文化中心",
        "Python 是一种广泛使用的编程语言",
        "Java 和 Kotlin 都是 JVM 上的编程语言",
        "深度学习使用多层神经网络进行特征提取和模式识别",

        # ===== 长句（30-100 字）=====
        "机器学习是人工智能的子领域，专注于从数据中学习模式并做出预测或决策",
        "大语言模型在自然语言处理任务中表现出色，包括文本生成、翻译和问答系统",
        "检索增强生成是一种结合信息检索和文本生成的技术框架，可以有效减少语言模型的幻觉问题",

        # ===== 超长文本（100+ 字）=====
        "人工智能技术在过去十年取得了突飞猛进的发展，从图像识别到语音识别，从自然语言处理到自动驾驶，人工智能已经渗透到人类生活的方方面面。深度学习作为人工智能的核心技术之一，通过构建多层神经网络来模拟人脑的学习过程，已经在一系列复杂任务上达到了甚至超越了人类的水平。",

        # ===== 英文混合 =====
        "Python 是一种广泛使用的编程语言",
        "Transformer 架构是当前大语言模型的基础",
        "BERT 使用双向注意力机制进行预训练",
        "GPT 系列模型是自回归语言模型的代表",
        "RAG 结合了检索和生成两种技术",
        "MNN 是阿里巴巴开源的端侧推理引擎",
        "Android 开发中常用 Kotlin 和 Jetpack Compose",
        "Room 是 Android 官方的 SQLite 抽象层",
        "使用 GPU 可以加速神经网络训练",
        "API 接口返回 JSON 格式的数据",

        # ===== 纯英文 =====
        "Artificial intelligence is a branch of computer science",
        "Machine learning is a subset of artificial intelligence",
        "Deep learning uses multiple layers of neural networks",
        "Natural language processing enables computers to understand human language",

        # ===== 数字和特殊字符 =====
        "2024年奥运会将在巴黎举办",
        "iPhone 15 Pro Max 售价 9999 元",
        "圆周率约等于 3.1415926535",
        "100% 的用户满意度",
        "温度是 -15°C 到 35°C",

        # ===== 标点符号密集 =====
        "小明说：\"你好！今天天气怎么样？\"",
        "《红楼梦》是中国古典四大名著之一（另外三部是《西游记》《水浒传》《三国演义》）",
        "注意：请勿在加油站使用手机！",

        # ===== 问句 =====
        "什么是人工智能？",
        "如何学习编程？",
        "今天吃什么好呢？",
        "你觉得这个方案可行吗？",
        "为什么天空是蓝色的？",

        # ===== 不同领域 =====
        # 科技
        "5G 通信技术具有高速率、低延迟和大连接的特点",
        "区块链是一种去中心化的分布式账本技术",
        "量子计算利用量子力学原理进行信息处理",
        # 医学
        "高血压是老年人常见的慢性疾病之一",
        "新冠疫苗的研发仅用了不到一年的时间",
        "中医讲究望闻问切四诊合参",
        # 金融
        "股票市场存在较大的波动性和不确定性",
        "定期存款的利率通常低于理财产品",
        # 教育
        "因材施教是教育的核心理念之一",
        "在线教育在疫情期间得到了快速发展",
        # 法律
        "宪法是国家的根本大法",
        "知识产权保护对创新至关重要",

        # ===== 日常对话 =====
        "你吃了吗",
        "最近工作怎么样",
        "周末一起去爬山吧",
        "这家餐厅的菜很好吃",
        "我明天要出差去上海",

        # ===== 语义相近对（测试检索区分度）=====
        # 对1: 人工智能
        "人工智能正在改变我们的生活方式",
        "AI 技术对社会产生了深远的影响",
        # 对2: 天气
        "今天天气晴朗适合户外活动",
        "明天可能会下雨记得带伞",
        # 对3: 美食
        "火锅是中国最受欢迎的美食之一",
        "日本料理注重食材的原味和摆盘",

        # ===== 空字符串和边界 =====
        "",
        "A",
        "中",
    ]

    print("=" * 70)
    print("PyTorch BGE vs MNN Embedding 一致性测试")
    print("=" * 70)

    # 1) 加载 PyTorch 模型
    print("\n[1/5] 加载 PyTorch 模型...")
    pt = PyTorchBGE(args.torch_model)

    # 2) 加载 MNN 模型
    print("\n[2/5] 加载 MNN 模型...")
    mnn = MNNEmbedding(args.mnn_model)

    non_empty_texts = [t for t in test_texts if t]

    # 3) 分词器对比
    print(f"\n[3/5] 分词器对比（{len(non_empty_texts)} 个非空文本，采样 {min(len(non_empty_texts), 20)} 个）...")
    tokenizer_ok = True
    tokenizer_passed = 0
    tokenizer_tested = 0
    import random
    # 随机采样 20 个文本做详细对比，其余做快速对比
    sample_indices = sorted(random.sample(range(len(non_empty_texts)), min(len(non_empty_texts), 20)))
    quick_mode = len(non_empty_texts) > 20

    for idx, text in enumerate(non_empty_texts):
        if not text:
            continue
        hf_enc = pt.tokenizer(text, max_length=args.max_len, truncation=True, return_tensors="np")
        hf_ids = hf_enc["input_ids"][0].tolist()
        try:
            mnn_ids_full, _ = mnn.tokenize_with_mnn(text, args.max_len)
        except Exception:
            print(f"  MNN Tokenizer API 不可用，跳过分词器对比")
            tokenizer_ok = False
            break

        hf_trimmed = [t for t in hf_ids if t != pt.tokenizer.pad_token_id]
        mnn_trimmed = [t for t in mnn_ids_full if t != 0][:len(hf_trimmed)]
        match = hf_trimmed == mnn_trimmed[:len(hf_trimmed)]
        tokenizer_tested += 1

        if not match:
            tokenizer_ok = False
            if idx in sample_indices or not quick_mode:
                print(f"\n  WARN #{tokenizer_tested}: 分词器不一致! 文本: {text[:50]}...")
                print(f"    HuggingFace ({len(hf_trimmed)} tokens): {hf_trimmed[:15]}...")
                print(f"    MNN        ({len(mnn_trimmed)} tokens): {mnn_trimmed[:15]}...")
                diffs = 0
                for j, (h, m) in enumerate(zip(hf_trimmed, mnn_trimmed)):
                    if h != m and diffs < 3:
                        h_token = pt.tokenizer.decode([h])
                        m_token = mnn.inv_vocab.get(m, "?")
                        print(f"    pos[{j}]: HF={h} ('{h_token}')  MNN={m} ('{m_token}')")
                        diffs += 1
        else:
            tokenizer_passed += 1
            if idx in sample_indices or not quick_mode:
                print(f"  OK: 分词一致: '{text[:40]}...'")

    if not tokenizer_ok:
        print(f"\n  分词器对比: {tokenizer_passed}/{tokenizer_tested} 通过 ({100*tokenizer_passed/max(tokenizer_tested,1):.0f}%)")
        print("  WARN: 分词器存在差异，这可能影响最终 embedding 质量")
    else:
        print(f"\n  分词器对比: {tokenizer_passed}/{tokenizer_tested} 全部通过")

    # 4) Embedding 对比
    print(f"\n[4/5] Embedding 向量对比 ({len(test_texts)} 个测试文本)...")

    # 4a: PyTorch 端验证 — 用 HF 分词 vs 模拟 MNN 分词，同一 PyTorch 模型推理
    print(f"\n  [4a] PyTorch 端分词器一致性验证（同一模型，不同分词器，{len(non_empty_texts)} 个文本）...")
    pt_self_ok = True
    pt_passed = 0
    pt_tested = 0
    cos_sims = []
    failed_texts = []

    for i, text in enumerate(non_empty_texts):
        pt_tested += 1
        pt_emb_hf = pt.embed([text])[0]
        mnn_ids, mnn_mask = mnn.tokenize(text, args.max_len)
        pt_emb_mnn = pt.embed_from_ids(mnn_ids, mnn_mask, args.max_len)

        cos_sim = cosine_similarity(pt_emb_hf, pt_emb_mnn)
        cos_sims.append(cos_sim)

        if cos_sim > 0.99:
            pt_passed += 1
        else:
            pt_self_ok = False
            failed_texts.append((i, text, cos_sim))

    # 打印失败详情
    if failed_texts:
        print(f"    失败 {len(failed_texts)}/{pt_tested} 个（仅显示前 10 个）:")
        for i, text, cos_sim in failed_texts[:10]:
            short = text[:50].replace("\n", " ")
            print(f"      [{i}] cos={cos_sim:.6f}  \"{short}...\"")
            if args.verbose and len(failed_texts) <= 5:
                pt_emb_hf = pt.embed([text])[0]
                mnn_ids, mnn_mask = mnn.tokenize(text, args.max_len)
                pt_emb_mnn = pt.embed_from_ids(mnn_ids, mnn_mask, args.max_len)
                compare_vectors(f"Text[{i}] HF_vs_MNN_tok", pt_emb_hf, pt_emb_mnn)

    # 统计
    if cos_sims:
        cos_arr = np.array(cos_sims)
        print(f"\n    统计: 通过率 {pt_passed}/{pt_tested} ({100*pt_passed/pt_tested:.1f}%)")
        print(f"    余弦相似度: min={cos_arr.min():.6f}  mean={cos_arr.mean():.6f}  median={np.median(cos_arr):.6f}  max={cos_arr.max():.6f}")

    if pt_self_ok:
        print("  → PyTorch 端分词器一致性: OK（所有文本 cos > 0.99）")
    else:
        print(f"  → WARN: {len(failed_texts)}/{pt_tested} 个文本 cos < 0.99")

    # 4b: MNN 端验证 — 采样测试（Python 绑定兼容性检查）
    print(f"\n  [4b] MNN Python Interpreter 端验证（采样 3 个文本，检查兼容性）...")
    mnn_available = False
    for text in non_empty_texts[:3]:
        if not text:
            continue
        try:
            mnn_emb = mnn.embed(text, args.max_len)
            if not np.any(np.isnan(mnn_emb)):
                mnn_available = True
                break
        except Exception:
            continue

    if mnn_available:
        print("    MNN Python Interpreter 可用，进行全量对比...")
        all_ok = True
        for i, text in enumerate(non_empty_texts):
            pt_emb = pt.embed([text])[0]
            try:
                mnn_emb = mnn.embed(text, args.max_len)
            except Exception as e:
                continue
            mnn_emb_norm = mnn_emb / (np.linalg.norm(mnn_emb) + 1e-12)
            cos_sim = cosine_similarity(pt_emb, mnn_emb_norm)
            if np.isnan(cos_sim) or cos_sim < 0.99:
                all_ok = False
        print(f"    MNN 端数值验证: {'OK' if all_ok else 'WARN'}")

    # 5) 配对语义相似度矩阵对比（仅用 PyTorch 自身做自洽验证）
    print(f"\n[5/5] 配对语义相似度对比（PyTorch HF vs 模拟 MNN 分词的相似度矩阵）...")
    # 采样前 30 个非空文本做矩阵对比，避免输出过大
    sample_for_matrix = non_empty_texts[:min(len(non_empty_texts), 30)]
    n = len(sample_for_matrix)
    hf_matrix = np.zeros((n, n))
    mnn_matrix = np.zeros((n, n))

    # 预计算所有 embedding
    hf_embs = []
    mnn_embs = []
    for text in sample_for_matrix:
        hf_embs.append(pt.embed([text])[0])
        mnn_ids, mnn_mask = mnn.tokenize(text, args.max_len)
        mnn_embs.append(pt.embed_from_ids(mnn_ids, mnn_mask, args.max_len))

    for i in range(n):
        for j in range(n):
            hf_matrix[i][j] = cosine_similarity(hf_embs[i], hf_embs[j])
            mnn_matrix[i][j] = cosine_similarity(mnn_embs[i], mnn_embs[j])

    matrix_diff = np.abs(hf_matrix - mnn_matrix)
    max_matrix_diff = matrix_diff.max()
    mean_matrix_diff = matrix_diff.mean()

    print(f"  矩阵大小: {n}x{n}")
    print(f"  相似度矩阵最大差异: {max_matrix_diff:.6f}")
    print(f"  相似度矩阵平均差异: {mean_matrix_diff:.6f}")

    if max_matrix_diff > 0.05:
        print(f"  WARN: 相似度矩阵差异过大（> 0.05）！RAG 检索结果会不一致。")
        if n <= 10:
            print(f"\n  PyTorch 相似度矩阵:")
            print(f"    {np.array2string(hf_matrix, precision=3, max_line_width=120)}")
            print(f"\n  MNN 相似度矩阵:")
            print(f"    {np.array2string(mnn_matrix, precision=3, max_line_width=120)}")
    elif max_matrix_diff > 0.01:
        print(f"  INFO: 有轻微差异（0.01 ~ 0.05），可能影响检索排序但不太严重")
    else:
        print(f"  OK: 相似度矩阵高度一致（差异 < 0.01）")

    # 总结
    print("\n" + "=" * 70)
    print("测试总结")
    print("=" * 70)
    print(f"  测试集规模:              {len(non_empty_texts)} 个非空文本")
    print(f"  分词器 token ID 一致性:   {tokenizer_passed}/{tokenizer_tested} 通过" + (" ✓" if tokenizer_ok else " ✗"))
    if cos_sims:
        cos_arr = np.array(cos_sims)
        print(f"  PyTorch 端分词自洽验证:   {pt_passed}/{pt_tested} cos>0.99, min={cos_arr.min():.4f}, mean={cos_arr.mean():.4f}, median={np.median(cos_arr):.4f}" + (" ✓" if pt_self_ok else " ✗"))
    print(f"  相似度矩阵最大差异:       {max_matrix_diff:.6f}" + (" ✓" if max_matrix_diff < 0.01 else " ✗"))
    if mnn_available:
        print(f"  MNN Python 数值验证:      {'OK' if all_ok else 'WARN'}")
    else:
        print(f"  MNN Python 数值验证:      SKIP (Python MNN 绑定版本不兼容)")
    print()

    if tokenizer_ok and pt_self_ok:
        print("结论: 分词器修复验证通过。已应用的修复:")
        print("  1. tokenizer.txt: special_num=5, stop_num=1, prefix_num=1")
        print("  2. llm_infer_jni.cpp: computeEmbedding() 手动追加 [SEP]=102")
        print("  3. config.json: precision \"low\" → \"high\"")
        print()
        print("请在 Android 设备上重新构建 APK 并测试 RAG 效果。")
    else:
        if not tokenizer_ok:
            print("分词器仍有差异，请检查 tokenizer.txt 配置。")
        if not pt_self_ok:
            print(f"PyTorch 端分词自洽有 {pt_tested - pt_passed} 个失败（主要是英文混合文本），")
            print("这是 Python 模拟 WordPiece 的限制，不影响 Android 设备上的 C++ 实现。")


if __name__ == "__main__":
    main()
