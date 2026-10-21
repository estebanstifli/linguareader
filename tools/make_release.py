"""Prepara los paquetes descargables y sus catálogos (sustituye a make_aligner_pack.py).

Entrada:  modelos exportados con tools/export_wav2vec2_onnx.py (.models/aligners/) y el paquete de voces
          generado con `./gradlew :engine:supertonic:voicePack` (.models/voice-pack/voices.zip).
Salida:   .models/release/*                       -> adjuntos de la release de GitHub (nombre = <id>.<fichero>)
          app/src/main/assets/aligners/catalog.json y packs/voices.json (tamaños y sha256 que verifica la app)

    python tools/make_release.py
    gh release upload packs-v1 .models/release/* --clobber --repo <owner>/<repo>   # o `gh release create`
"""
import hashlib, json, pathlib, shutil

root = pathlib.Path(__file__).resolve().parent.parent
rel = root / ".models/release"
rel.mkdir(parents=True, exist_ok=True)
MODELS = [
    dict(id="w2v2-en", language="en", name="wav2vec2 base 100h (inglés)", source="facebook/wav2vec2-base-100h",
         sourceRevision="9c1fef36b62a428a658e5b022ef9f21b38f47e0b", license="Apache-2.0",
         licenseUrl="https://huggingface.co/facebook/wav2vec2-base-100h", srcDir="w2v2-base-100h", startLeadMs=40),
    # FR y ES: VoxPopuli base (95M, ~120 MB en int8), licencia no comercial. Sustituye al FR large Apache-2.0
    # (bofenghuang/asr-wav2vec2-ctc-french, 355 MB). startLeadMs: adelanto de inicios medido con alignBench (docs/ALIGNMENT.md §9).
    dict(id="vp-fr", language="fr", name="wav2vec2 base VoxPopuli FR (francés)", source="facebook/wav2vec2-base-10k-voxpopuli-ft-fr",
         sourceRevision="e6d7b145359a2a07df759369aea64307f4f6031a", license="CC-BY-NC-4.0",
         licenseUrl="https://huggingface.co/facebook/wav2vec2-base-10k-voxpopuli-ft-fr", srcDir="vp-fr", startLeadMs=80),
    dict(id="vp-es", language="es", name="wav2vec2 base VoxPopuli ES (español)", source="facebook/wav2vec2-base-10k-voxpopuli-ft-es",
         sourceRevision="1f8c1fd6048a71c6c7644224c5a0aa87fb92cc27", license="CC-BY-NC-4.0",
         licenseUrl="https://huggingface.co/facebook/wav2vec2-base-10k-voxpopuli-ft-es", srcDir="vp-es", startLeadMs=60),
]


def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


out = []
for m in MODELS:
    src = root / ".models/aligners" / m.pop("srcDir")
    files = []
    for local, origin in (("model.onnx", src / "onnx/model_int8.onnx"), ("vocab.json", src / "vocab.json")):
        dst = rel / f"{m['id']}.{local}"
        shutil.copy(origin, dst)
        files.append(dict(path=local, sizeBytes=dst.stat().st_size, sha256=sha(dst)))
    m["format"] = "onnx-int8-matmul"
    m["files"] = files
    out.append(m)
assets = root / "app/src/main/assets"
(assets / "aligners").mkdir(parents=True, exist_ok=True)
(assets / "packs").mkdir(parents=True, exist_ok=True)
(assets / "aligners/catalog.json").write_text(json.dumps(dict(version=1, models=out), indent=2, ensure_ascii=False), encoding="utf-8")

zip_ = root / ".models/voice-pack/voices.zip"
shutil.copy(zip_, rel / "voices.zip")
(assets / "packs/voices.json").write_text(
    json.dumps(dict(version=1, asset="voices.zip", sizeBytes=zip_.stat().st_size, sha256=sha(zip_)), indent=2), encoding="utf-8")
print("release:", *sorted(p.name for p in rel.iterdir()), sep="\n  ")
