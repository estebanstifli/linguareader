"""Exporta un wav2vec2 CTC de Hugging Face a ONNX (fp32, fp16 e int8) para el alineador.

    python tools/export_wav2vec2_onnx.py facebook/wav2vec2-base-100h .models/aligners/w2v2-base-100h

Genera onnx/model.onnx (fp32), model_fp16.onnx, model_int8.onnx (cuantización dinámica solo de
MatMul: ORT CPU no soporta ConvInteger) y vocab.json (símbolo → id; "<pad>" es el blanco CTC).
"""
import json, sys, pathlib
import numpy as np, torch
from transformers import Wav2Vec2ForCTC, Wav2Vec2Processor

repo, out = sys.argv[1], pathlib.Path(sys.argv[2])
(out / "onnx").mkdir(parents=True, exist_ok=True)
model = Wav2Vec2ForCTC.from_pretrained(repo).eval()
vocab = Wav2Vec2Processor.from_pretrained(repo).tokenizer.get_vocab()
json.dump(vocab, open(out / "vocab.json", "w"), ensure_ascii=False)

class Wrap(torch.nn.Module):
    def __init__(s, m): super().__init__(); s.m = m
    def forward(s, x): return s.m(x).logits

x = torch.randn(1, 16000 * 3)
fp32 = out / "onnx/model.onnx"
torch.onnx.export(Wrap(model), (x,), str(fp32), input_names=["input_values"], output_names=["logits"],
                  dynamic_axes={"input_values": {0: "b", 1: "n"}, "logits": {0: "b", 1: "f"}},
                  opset_version=17, dynamo=False)

import onnx
from onnxruntime.quantization import quantize_dynamic, QuantType
quantize_dynamic(str(fp32), str(out / "onnx/model_int8.onnx"), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
try:
    from onnxruntime.transformers.float16 import convert_float_to_float16
    m16 = convert_float_to_float16(onnx.load(str(fp32)), keep_io_types=True)
    onnx.save(m16, str(out / "onnx/model_fp16.onnx"))
except Exception as e:
    print("fp16 falló:", e)
for p in sorted((out / "onnx").glob("*.onnx")):
    print(p.name, p.stat().st_size // 1_000_000, "MB")
