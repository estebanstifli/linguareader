"""Build a tiny ONNX timing graph that reuses verified source weights by byte offset.

Requires the pinned dependencies in tools/requirements-supertonic-graph.txt.
Source files are never rewritten. Only the graph is shipped in the application;
no learned weights are redistributed in the APK. Regenerate when the pinned
model revision changes. Generated artifacts stay with the local model cache.
"""
import argparse
import base64
import gzip
import hashlib
import json
from pathlib import Path
import onnx
import numpy as np
from onnx import numpy_helper

ROOT=Path(__file__).resolve().parents[1]
REV='aafc6e32416a594460b32413efc49d7fe4ce6d46'
EXPECTED='883ac868ea0275ef0e991524dc64f16b3c0376efd7c320af6b53f5b780d7c61c'

def varint(value):
    out=bytearray()
    while value>=128: out.append((value&127)|128); value>>=7
    out.append(value); return bytes(out)

def field(number,payload): return varint((number<<3)|2)+varint(len(payload))+payload

def fields(blob,start=0,end=None):
    end=len(blob) if end is None else end
    offset=start
    def take():
        nonlocal offset
        value=0; shift=0
        while True:
            b=blob[offset]; offset+=1; value|=(b&127)<<shift
            if not b&128:return value
            shift+=7
    while offset<end:
        begin=offset; key=take(); wire=key&7
        if wire==0: payload=offset; take()
        elif wire==1: payload=offset; offset+=8
        elif wire==5: payload=offset; offset+=4
        elif wire==2: length=take(); payload=offset; offset+=length
        else: raise ValueError(f'Unsupported wire type {wire}')
        assert offset<=end
        yield key>>3,wire,begin,payload,offset

def build(source):
    original=source.read_bytes(); assert hashlib.sha256(original).hexdigest()==EXPECTED
    model=[]; raw_bytes=0; external_count=0
    for num,wire,begin,payload,end in fields(original):
        if num!=7: model.append(original[begin:end]); continue
        graph=[]
        for gn,gw,gb,gp,ge in fields(original,payload,end):
            if gn!=5: graph.append(original[gb:ge]); continue
            tensor=onnx.TensorProto(); tensor.ParseFromString(original[gp:ge])
            for tn,tw,tb,tp,te in fields(original,gp,ge):
                # ORT shape inference reads small shape/axes constants from raw_data.
                # Externalize only learned, sizeable FLOAT weights; keep control tensors inline.
                if tn==9 and tensor.data_type==onnx.TensorProto.FLOAT and (te-tp>=1024 or tensor.name.startswith('vector_estimator.tts.')):
                    tensor.ClearField('raw_data'); tensor.data_location=onnx.TensorProto.EXTERNAL
                    for key,value in [('location',source.name),('offset',str(tp)),('length',str(te-tp))]:
                        entry=tensor.external_data.add(); entry.key=key; entry.value=value
                    raw_bytes+=te-tp; external_count+=1
            graph.append(field(5,tensor.SerializeToString()))
        src='/vector_estimator/vector_field/main_blocks.21/attn/Softmax_output_0'
        nodes=[
            onnx.helper.make_node('Gather',[src,'timing_heads'],['timing_selected'],axis=0),
            onnx.helper.make_node('Gather',['timing_selected','timing_batch'],['timing_conditional'],axis=1),
            onnx.helper.make_node('ReduceMean',['timing_conditional','timing_axes'],['timing_attention'],keepdims=0),
        ]
        graph += [field(1,node.SerializeToString()) for node in nodes]
        for name,array in [('timing_heads',np.array([2,7],np.int64)),('timing_batch',np.array(0,np.int64)),('timing_axes',np.array([0],np.int64))]:
            graph.append(field(5,numpy_helper.from_array(array,name).SerializeToString()))
        graph.append(field(12,onnx.helper.make_tensor_value_info('timing_attention',onnx.TensorProto.FLOAT,[None,None]).SerializeToString()))
        model.append(field(7,b''.join(graph)))
    return b''.join(model),dict(external_tensors=external_count,raw_bytes_reused=raw_bytes)

def main():
    p=argparse.ArgumentParser()
    p.add_argument('--source',type=Path,default=ROOT/f'.models/supertonic-3/{REV}/onnx/vector_estimator.onnx')
    p.add_argument('--output',type=Path)
    p.add_argument('--manifest',type=Path,default=ROOT/f'.models/supertonic-3/{REV}/timing-graph-manifest.json')
    p.add_argument('--kotlin',action='store_true')
    a=p.parse_args()
    graph,details=build(a.source)
    output=a.output or a.source.with_name('vector_estimator.timing.onnx')
    output.write_bytes(graph)
    onnx.checker.check_model(str(output))
    gz=gzip.compress(graph,mtime=0)
    details.update(source_sha256=EXPECTED,graph_sha256=hashlib.sha256(graph).hexdigest(),graph_bytes=len(graph),gzip_bytes=len(gz),source_revision=REV,
        weights_changed=False,outputs=['denoised_latent','timing_attention'],attention='block21 conditional batch0 mean heads2/7')
    if a.kotlin:
        # Separate constants stay below the JVM class-file UTF-8 constant limit.
        encoded=base64.b64encode(gz).decode()
        chunks=[encoded[i:i+8000] for i in range(0,len(encoded),8000)]
        text='''package com.linguareader.engine.supertonic

import okio.Buffer
import okio.ByteString.Companion.decodeBase64
import okio.GzipSource
import okio.buffer

/** Generated by tools/build_supertonic_timing_graph.py; graph only, no learned weights. */
internal object SupertonicTimingGraph {
    const val FILE = "onnx/vector_estimator.timing.onnx"
    const val SHA256 = "'''+details['graph_sha256']+'''"
    const val SIZE = '''+str(len(graph))+'''L

    fun bytes(): ByteArray {
        val compressed = listOf(
'''+''.join('            "'+chunk+'",\n' for chunk in chunks)+'''        ).joinToString("").decodeBase64() ?: error("Invalid embedded timing graph")
        return GzipSource(Buffer().write(compressed)).buffer().use { it.readByteArray() }
    }
}
'''
        target=ROOT/'engine/supertonic/src/commonMain/kotlin/com/linguareader/engine/supertonic/SupertonicTimingGraph.kt'
        target.write_text(text,encoding='utf-8')
    a.manifest.parent.mkdir(parents=True,exist_ok=True)
    a.manifest.write_text(json.dumps(details,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(details,indent=2))

if __name__=='__main__':main()
