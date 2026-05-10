"""Check ONNX model for unsupported ops"""
import onnx, glob, os

# Find the ONNX file
for d in glob.glob(os.path.expandvars("C:/Users/wanwanwan/AppData/Local/Temp/mnn_convert_*")):
    p = os.path.join(d, "bge_small_zh.onnx")
    if os.path.exists(p):
        print(f"Found: {p}")
        m = onnx.load(p)
        ops = sorted(set(n.op_type for n in m.graph.node))
        print(f"Total ops: {len(ops)}")
        print(f"Ops: {ops}")
        bad = [o for o in ops if o == "IsNaN"]
        if bad:
            print(f"HAS IsNaN: removing it...")
            break
else:
    print("No ONNX found in temp dirs")
