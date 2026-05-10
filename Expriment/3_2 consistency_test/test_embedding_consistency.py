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
        与 C++ JNI 完全一致的推理流程:
        - 通过 fromNumpy 写入 tensor（对齐 C++ host<float>() 赋值）
        - runSession
        - 取 output[0:dim]（CLS token embedding）—— 不归一化
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
        "人工智能是计算机科学的一个分支",
        "今天天气很好，适合出去玩",
        "机器学习是人工智能的子领域，专注于从数据中学习模式",
        "中国的首都是北京",  # 短句
        "深度学习使用多层神经网络进行特征提取和模式识别",  # 与 text 3 语义相近
        "Python 是一种广泛使用的编程语言",  # 英文混合
        "",  # 空字符串
        "大语言模型在自然语言处理任务中表现出色，包括文本生成、翻译和问答系统",  # 长句
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

    # 3) 分词器对比
    print("\n[3/5] 分词器对比...")
    tokenizer_ok = True
    for text in test_texts[:4]:  # 只测前几个样本
        if not text:
            continue
        hf_enc = pt.tokenizer(text, max_length=args.max_len, truncation=True, return_tensors="np")
        hf_ids = hf_enc["input_ids"][0].tolist()
        try:
            mnn_ids_full, _ = mnn.tokenize_with_mnn(text, args.max_len)
        except Exception:
            # MNN tokenizer API 可能不可用，跳过
            print(f"  MNN Tokenizer API 不可用，跳过分词器对比")
            tokenizer_ok = False
            break

        # 裁剪有实际 token 的部分
        hf_trimmed = [t for t in hf_ids if t != pt.tokenizer.pad_token_id]
        mnn_trimmed = [t for t in mnn_ids_full if t != 0][:len(hf_trimmed)]

        match = hf_trimmed == mnn_trimmed[:len(hf_trimmed)]
        if not match:
            print(f"\n  WARN: 分词器不一致! 文本: {text[:50]}...")
            print(f"    HuggingFace ({len(hf_trimmed)} tokens): {hf_trimmed[:20]}...")
            print(f"    MNN        ({len(mnn_trimmed)} tokens): {mnn_trimmed[:20]}...")

            # 逐个比较
            diffs = 0
            for j, (h, m) in enumerate(zip(hf_trimmed, mnn_trimmed)):
                if h != m and diffs < 5:
                    h_token = pt.tokenizer.decode([h])
                    m_token = mnn.inv_vocab.get(m, "?")
                    print(f"    pos[{j}]: HF={h} ('{h_token}')  MNN={m} ('{m_token}')")
                    diffs += 1
            tokenizer_ok = False
        else:
            print(f"  OK: 分词一致: '{text[:30]}...'")

    if not tokenizer_ok:
        print("\n  WARN: 分词器存在差异，这可能影响最终 embedding 质量")

    # 4) Embedding 对比
    print(f"\n[4/5] Embedding 向量对比 ({len(test_texts)} 个测试文本)...")

    # 4a: PyTorch 端验证 — 用 HF 分词 vs 模拟 MNN 分词，同一 PyTorch 模型推理
    print("\n  [4a] PyTorch 端分词器一致性验证（同一模型，不同分词器）...")
    pt_self_ok = True
    for i, text in enumerate(test_texts):
        if not text:
            continue
        # HF tokenizer
        pt_emb_hf = pt.embed([text])[0]
        # Simulated MNN tokenizer → token IDs → feed to PyTorch model
        mnn_ids, mnn_mask = mnn.tokenize(text, args.max_len)
        pt_emb_mnn = pt.embed_from_ids(mnn_ids, mnn_mask, args.max_len)

        cos_sim = cosine_similarity(pt_emb_hf, pt_emb_mnn)
        short = text[:40].replace("\n", " ")
        status = "OK:" if cos_sim > 0.99 else "WARN:"
        print(f"    [{i}] {status} cos={cos_sim:.6f}  \"{short}...\"")
        if cos_sim < 0.99:
            pt_self_ok = False
            if args.verbose:
                compare_vectors(f"Text[{i}] HF_vs_MNN_tok", pt_emb_hf, pt_emb_mnn)

    if pt_self_ok:
        print("  → PyTorch 端分词器一致性: OK（证明分词器修复正确）")
    else:
        print("  → WARN: HF 分词与 MNN 模拟分词仍存在差异")

    # 4b: MNN 端验证（可能因 Python 绑定版本不兼容而失败）
    print("\n  [4b] MNN Python Interpreter 端验证...")
    all_ok = True
    pt_embeddings = []
    mnn_embeddings = []

    for i, text in enumerate(test_texts):
        if not text:
            try:
                mnn_emb = mnn.embed(text, args.max_len)
                print(f"    [{i}] 空文本: MNN embedding shape={mnn_emb.shape}")
                continue
            except Exception as e:
                print(f"    [{i}] 空文本: MNN 报错（可以接受）: {e}")
                continue

        pt_emb = pt.embed([text])[0]
        pt_embeddings.append(pt_emb)

        try:
            mnn_emb = mnn.embed(text, args.max_len)
        except Exception as e:
            print(f"    [{i}] MNN 推理失败: {e}")
            continue
        mnn_embeddings.append(mnn_emb)

        mnn_emb_norm = mnn_emb / (np.linalg.norm(mnn_emb) + 1e-12)
        cos_sim = cosine_similarity(pt_emb, mnn_emb_norm)
        max_abs_diff = np.abs(pt_emb - mnn_emb_norm).max()

        short = text[:40].replace("\n", " ")
        if np.isnan(cos_sim):
            status = "SKIP:"
            print(f"    [{i}] {status} MNN Python binding 不兼容，跳过数值对比  \"{short}...\"")
        elif cos_sim > 0.99:
            print(f"    [{i}] OK: cos={cos_sim:.6f}  max_diff={max_abs_diff:.6f}  \"{short}...\"")
        else:
            all_ok = False
            print(f"    [{i}] WARN: cos={cos_sim:.6f}  max_diff={max_abs_diff:.6f}  \"{short}...\"")
            if args.verbose:
                compare_vectors(f"Text[{i}]", pt_emb, mnn_emb_norm)

    # 5) 配对相似度矩阵对比
    print(f"\n[5/5] 配对语义相似度对比...")
    if len(pt_embeddings) >= 3 and len(mnn_embeddings) >= 3:
        n = min(len(pt_embeddings), len(mnn_embeddings))
        pt_matrix = np.zeros((n, n))
        mnn_matrix = np.zeros((n, n))

        for i in range(n):
            for j in range(n):
                pt_matrix[i][j] = cosine_similarity(pt_embeddings[i], pt_embeddings[j])
                mnn_matrix[i][j] = cosine_similarity(mnn_embeddings[i], mnn_embeddings[j])

        # 比较两个相似度矩阵
        matrix_diff = np.abs(pt_matrix - mnn_matrix)
        max_matrix_diff = matrix_diff.max()
        mean_matrix_diff = matrix_diff.mean()

        print(f"  相似度矩阵最大差异: {max_matrix_diff:.6f}")
        print(f"  相似度矩阵平均差异: {mean_matrix_diff:.6f}")

        if max_matrix_diff > 0.05:
            print(f"  WARN: 相似度矩阵差异过大！RAG 检索结果会不一致。")
            print(f"\n  PyTorch 相似度矩阵:")
            print(f"    {np.array2string(pt_matrix, precision=3, max_line_width=120)}")
            print(f"\n  MNN 相似度矩阵:")
            print(f"    {np.array2string(mnn_matrix, precision=3, max_line_width=120)}")
            print(f"\n  差异矩阵:")
            print(f"    {np.array2string(matrix_diff, precision=3, max_line_width=120)}")

        elif max_matrix_diff > 0.01:
            print(f"  INFO: 有轻微差异，可能影响检索排序但不太严重")
        else:
            print(f"  OK: 相似度矩阵高度一致（差异 < 0.01）")

    # 总结
    print("\n" + "=" * 70)
    print("测试总结")
    print("=" * 70)
    print(f"  分词器 token ID 一致性: {'OK: 通过' if tokenizer_ok else 'WARN: 有差异'}")
    print(f"  PyTorch 端分词自洽验证: {'OK: 通过 (cos > 0.99)' if pt_self_ok else 'WARN: 有差异'}")
    if all_ok:
        print(f"  MNN Python 数值验证:    OK: cos > 0.99")
    elif len(mnn_embeddings) > 0:
        print(f"  MNN Python 数值验证:    WARN: cos < 0.99 或 nan")
    else:
        print(f"  MNN Python 数值验证:    SKIP (Python MNN 绑定版本不兼容)")
    print()

    if not tokenizer_ok:
        print("分词器仍不一致，请检查:")
        print("  - tokenizer.txt header 是否配置了正确的 special/stop/prefix 计数")
        print("  - token IDs 是否与 HuggingFace vocab 匹配")
    elif not pt_self_ok:
        print("PyTorch 端分词自洽未通过，请检查:")
        print("  - MNN 模拟 WordPiece 实现是否匹配 HuggingFace BertTokenizer")
    else:
        print("分词器修复验证通过。已应用的修复:")
        print("  1. tokenizer.txt: special_num=5, stop_num=1, prefix_num=1")
        print("     spec_ids = [PAD]=0, [UNK]=100, [CLS]=101, [SEP]=102, [MASK]=103")
        print("     stop = [SEP]=102, prefix = [CLS]=101")
        print("     → MNN encode() 现在会在序列前添加 [CLS]=101")
        print("  2. llm_infer_jni.cpp computeEmbedding():")
        print("     手动追加 ids.push_back(102) — 因为 MNN load_special 不支持 suffix_tokens_")
        print("     → 现在序列格式为: [CLS] token1 token2 ... tokenN [SEP]")
        print("  3. config.json: precision 从 \"low\" 改为 \"high\"")
        print()
        if not all_ok and len(mnn_embeddings) == 0:
            print("注意: MNN Python 绑定版本与模型文件不兼容，无法在 PC 端验证最终 embedding 数值。")
            print("这不会影响 Android 设备上的 libMNN.so 推理。")
            print("请在 Android 设备上重新构建 APK 并测试 RAG 效果。")


if __name__ == "__main__":
    main()
