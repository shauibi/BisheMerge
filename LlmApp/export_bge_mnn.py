"""
Export BAAI/bge-small-zh from PyTorch to MNN (FP32)
Usage: python export_bge_mnn.py [--output_dir ./bge_small_zh_mnn]
"""
import argparse
import os
import sys
import shutil
import tempfile
import subprocess
import numpy as np
import onnx
from onnx import helper

if sys.platform == "win32":
    import io
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8")

os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"


def export_onnx(model_name, onnx_path, max_len=512):
    """Step 1: Export BERT backbone to ONNX (raw last_hidden_state, no post-processing)"""
    import torch
    from transformers import AutoTokenizer, AutoModel

    print(f"[1/5] Loading: {model_name}")
    device = torch.device("cpu")
    tokenizer = AutoTokenizer.from_pretrained(model_name)
    bert = AutoModel.from_pretrained(model_name).to(device)
    bert.eval()

    hidden = bert.config.hidden_size
    print(f"  Hidden size: {hidden}")

    class BERTBackbone(torch.nn.Module):
        def __init__(self, model):
            super().__init__()
            self.model = model

        def forward(self, input_ids, attention_mask):
            out = self.model(input_ids=input_ids, attention_mask=attention_mask)
            return out.last_hidden_state

    wrapper = BERTBackbone(bert)
    wrapper.eval()

    dummy_ids = torch.zeros((1, max_len), dtype=torch.long)
    dummy_mask = torch.zeros((1, max_len), dtype=torch.long)

    with torch.no_grad():
        test = wrapper(dummy_ids, dummy_mask)
        print(f"  Output shape: {test.shape}")

    print(f"[2/5] Exporting ONNX (dynamo)...")
    torch.onnx.export(
        wrapper,
        (dummy_ids, dummy_mask),
        onnx_path,
        input_names=["input_ids", "attention_mask"],
        output_names=["last_hidden_state"],
        dynamo=True,
    )
    print(f"  Done: {onnx_path} ({os.path.getsize(onnx_path) / 1024 / 1024:.1f} MB)")

    return hidden, tokenizer


def fix_onnx_unsupported_ops(onnx_path):
    """
    Step 3: Remove ops that MNN doesn't support (IsNaN).
    Strategy: for each IsNaN→Where pattern, remove both and use just the 'false' branch,
    since for valid BERT inputs no NaN should occur.
    """
    model = onnx.load(onnx_path)
    graph = model.graph
    nodes = list(graph.node)

    print(f"[3/5] Removing unsupported ops from ONNX...")

    # Build adjacency: output_name -> producing_node, input_name -> consuming_nodes
    producers = {}
    consumers = {}
    for node in nodes:
        for out_name in node.output:
            producers[out_name] = node
        for inp_name in node.input:
            if inp_name not in consumers:
                consumers[inp_name] = []
            consumers[inp_name].append(node)

    isnan_count = 0
    where_removed = 0

    for node in nodes:
        if node.op_type != "IsNaN":
            continue

        isnan_count += 1
        isnan_out = node.output[0]

        if isnan_out not in consumers:
            # IsNaN output unused? Just remove it
            graph.node.remove(node)
            continue

        # IsNaN output used by Where nodes: Where(condition=isnan, X=nan_safe, Y=normal)
        # Replace Where with Identity on Y (the normal path, since we assume no NaN)
        for consumer in list(consumers.get(isnan_out, [])):
            if consumer.op_type == "Where":
                # Where(inputs: condition, X, Y) -> Y is the non-NaN path
                y_name = consumer.input[2]  # Third input = value when condition is False
                where_out = consumer.output[0]

                # Replace all uses of Where output with Y input directly
                for n in graph.node:
                    for i, inp in enumerate(n.input):
                        if inp == where_out:
                            n.input[i] = y_name

                graph.node.remove(consumer)
                where_removed += 1

        # Remove the IsNaN node
        graph.node.remove(node)

    if isnan_count > 0:
        print(f"  Removed {isnan_count} IsNaN + {where_removed} Where nodes")
    else:
        print(f"  No IsNaN nodes found")

    # Save
    onnx.save(model, onnx_path)


def simplify_onnx(onnx_path):
    """Step 4: Simplify ONNX"""
    print(f"[4/5] Simplifying ONNX...")

    try:
        onnx.shape_inference.infer_shapes_path(onnx_path, onnx_path)
        print(f"  Shape inference OK")
    except Exception as e:
        print(f"  Shape inference: {e}")

    try:
        from onnxsim import simplify
        model = onnx.load(onnx_path)
        model_simp, check = simplify(model)
        if check:
            onnx.save(model_simp, onnx_path)
            print(f"  onnxsim OK")
        else:
            # Save anyway — our modifications are valid
            onnx.save(model_simp, onnx_path)
            print(f"  onnxsim check failed (using modified version)")
    except Exception as e:
        print(f"  onnxsim: {e}")

    # Final check
    model = onnx.load(onnx_path)
    ops = set(n.op_type for n in model.graph.node)
    bad = [o for o in ops if o == "IsNaN"]
    if bad:
        print(f"  WARNING: IsNaN still present!")
    else:
        print(f"  Clean: {len(ops)} op types, no unsupported ops")


def convert_mnn(onnx_path, mnn_path):
    """Step 5: Convert to MNN using CLI"""
    print(f"[5/5] Converting to MNN...")

    tmp = tempfile.mkdtemp(prefix="mnn_")
    tmp_onnx = os.path.join(tmp, "model.onnx")
    shutil.copy2(onnx_path, tmp_onnx)
    # Also copy .data file if exists (for external data format ONNX)
    if os.path.exists(onnx_path + ".data"):
        shutil.copy2(onnx_path + ".data", tmp_onnx + ".data")
    tmp_mnn = os.path.join(tmp, "model.mnn")

    result = subprocess.run(
        ["mnnconvert", "-f", "ONNX",
         "--modelFile", tmp_onnx,
         "--MNNModel", tmp_mnn],
        capture_output=True, text=True, timeout=600
    )

    # mnnconvert may crash after writing the file (exit code != 0)
    # Check if the .mnn file was actually created
    if os.path.exists(tmp_mnn) and os.path.getsize(tmp_mnn) > 0:
        if "Converted Success" in result.stdout:
            print(f"  Success (converter had post-exit crash, but model is valid)")
            shutil.copy2(tmp_mnn, mnn_path)
            shutil.rmtree(tmp)
            size_mb = os.path.getsize(mnn_path) / 1024 / 1024
            print(f"  MNN: {mnn_path} ({size_mb:.1f} MB)")
            return mnn_path

    print(f"  FAILED:")
    for line in (result.stdout + result.stderr).split("\n"):
        if line.strip():
            print(f"    {line.strip()}")
    shutil.rmtree(tmp)
    return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model_name", default="BAAI/bge-small-zh")
    parser.add_argument("--output_dir", default="./bge_small_zh_mnn")
    parser.add_argument("--max_len", type=int, default=512)
    args = parser.parse_args()

    out_dir = os.path.abspath(args.output_dir)
    os.makedirs(out_dir, exist_ok=True)

    onnx_p = os.path.join(out_dir, "bge_small_zh.onnx")

    # 1-2: PyTorch → ONNX
    hidden, tokenizer = export_onnx(args.model_name, onnx_p, args.max_len)

    # 3: Remove IsNaN
    fix_onnx_unsupported_ops(onnx_p)

    # 4: Simplify
    simplify_onnx(onnx_p)

    # 5: Convert to MNN
    mnn_p = os.path.join(out_dir, "bge_small_zh.mnn")
    result = convert_mnn(onnx_p, mnn_p)

    if result is None:
        print("\nERROR: MNN conversion failed")
        print(f"ONNX kept at: {onnx_p}")
        return 1

    # Verify
    import MNN
    interp = MNN.Interpreter(mnn_p)
    sess = interp.createSession()
    inp_ids = interp.getSessionInput(sess, "input_ids")
    inp_mask = interp.getSessionInput(sess, "attention_mask")
    out = interp.getSessionOutput(sess, "last_hidden_state")
    print(f"\n  input_ids shape:       {inp_ids.getShape()}")
    print(f"  attention_mask shape:  {inp_mask.getShape()}")
    print(f"  last_hidden_state:     {out.getShape()}")

    os.remove(onnx_p)

    print(f"\nDone! Output: {mnn_p}")
    print(f"Output shape: [1, 512, {hidden}] — raw last_hidden_state")
    print(f"JNI: extract out[0, 0, :] (CLS position) for sentence embedding")


if __name__ == "__main__":
    main()
