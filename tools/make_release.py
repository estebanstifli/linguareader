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
         licenseUrl="https://huggingface.co/facebook/wav2vec2-base-100h", srcDir="w2v2-base-100h"),
    dict(id="w2v2-fr", language="fr", name="wav2vec2 large 7K FR (francés)", source="bofenghuang/asr-wav2vec2-ctc-french",
         sourceRevision="46bbc5088ec8a5921a501a2458fe27c789be0167", license="Apache-2.0",
         licenseUrl="https://huggingface.co/bofenghuang/asr-wav2vec2-ctc-french", srcDir="w2v2-fr"),
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
