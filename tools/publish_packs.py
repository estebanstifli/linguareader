"""Publica los paquetes descargables en el bucket R2 público (https://files.lingoaudiobook.eu).

  python -I tools/publish_packs.py                 # voces de .models/release/voices.json -> packs-v1/
  python -I tools/publish_packs.py --library       # dist/library/*     -> library-v1/ (bibliotecas EPUB + libraries.json)
  python -I tools/publish_packs.py --narrations    # dist/narrations/*  -> library-v1/ (audio + ASS de narración)
  python -I tools/publish_packs.py --files a.zip b.json --prefix packs-v1

Usa `wrangler r2 object put` con la cuenta de Cloudflare ya autenticada (wrangler login) desde el proyecto web
(D:/prueba/book_web, que tiene wrangler en node_modules). Los nombres de fichero se conservan; la app los pide como
<base>/<prefijo>/<nombre> (PackSource). Solo sube lo que cambió (compara el sha256 local con el ETag/metadata remoto si
está; si no puede, sube).
"""
import argparse
import hashlib
import json
import mimetypes
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BUCKET = "lingoaudiobook-packs"
BASE_URL = "https://files.lingoaudiobook.eu"
WEB_DIR = Path(os.environ.get("LINGO_WEB_DIR", ROOT.parent / "book_web"))
PACKS_PREFIX = "packs-v1"
LIBRARY_PREFIX = "library-v1"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def wrangler(*args, check=True, capture=False):
    cmd = ["npx", "--no-install", "wrangler", *args]
    return subprocess.run(cmd, cwd=WEB_DIR, check=check, shell=(os.name == "nt"), capture_output=capture, text=True, encoding="utf-8")


def load_index(index_path: Path):
    return json.loads(index_path.read_text(encoding="utf-8")) if index_path.exists() else {}


def default_voice_files(release_dir: Path):
    """Use the current voice manifest, never stale aligner weights or unrelated release files."""
    catalog = release_dir / "voices.json"
    data = json.loads(catalog.read_text(encoding="utf-8"))
    if data.get("version") != 2 or not isinstance(data.get("languages"), dict):
        raise ValueError("Expected a version 2 voice catalog")
    files = []
    for entry in data["languages"].values():
        name = entry["asset"]
        if not isinstance(name, str) or Path(name).name != name or "/" in name or "\\" in name or not name.startswith("voices-") or not name.endswith(".zip"):
            raise ValueError(f"Unsafe or non-voice package name: {name}")
        path = release_dir / name
        if not path.is_file():
            raise FileNotFoundError(path)
        files.append(path)
    # Publish the catalog after its referenced archives are available.
    return sorted(set(files)) + [catalog]


def put(path: Path, prefix: str, index: dict, force: bool):
    key = f"{prefix}/{path.name}"
    digest = sha256(path)
    if not force and index.get(key) == digest:
        print(f"  = {key} (sin cambios)")
        return
    ctype = mimetypes.guess_type(path.name)[0] or ("application/zip" if path.suffix == ".zip" else "application/octet-stream")
    if path.suffix == ".json":
        ctype = "application/json"
    elif path.suffix == ".ass":
        ctype = "text/plain; charset=utf-8"
    elif path.suffix == ".zip":
        ctype = "application/zip"
    print(f"  ↑ {key} ({path.stat().st_size / 1e6:.1f} MB, {ctype})")
    wrangler("r2", "object", "put", f"{BUCKET}/{key}", "--file", str(path), "--content-type", ctype, "--remote",
             "--cache-control", "public, max-age=3600" if path.suffix == ".json" else "public, max-age=31536000, immutable")
    index[key] = digest


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--library", action="store_true", help="subir dist/library/* a library-v1/")
    ap.add_argument("--narrations", action="store_true", help="subir dist/narrations/* a library-v1/")
    ap.add_argument("--files", nargs="*", help="ficheros concretos")
    ap.add_argument("--prefix", default=None, help="prefijo para --files (por defecto packs-v1)")
    ap.add_argument("--force", action="store_true", help="subir aunque el índice local diga que no cambió")
    args = ap.parse_args()
    if not (WEB_DIR / "node_modules" / ".bin").exists():
        sys.exit(f"No encuentro wrangler en {WEB_DIR} (variable LINGO_WEB_DIR).")

    index_path = ROOT / ".models" / "r2-index.json"
    index = load_index(index_path)
    jobs = []
    if args.files:
        jobs += [(Path(f), args.prefix or PACKS_PREFIX) for f in args.files]
    if args.library:
        jobs += [(p, LIBRARY_PREFIX) for p in sorted((ROOT / "dist" / "library").glob("*")) if p.is_file()]
    if args.narrations:
        jobs += [(p, LIBRARY_PREFIX) for p in sorted((ROOT / "dist" / "narrations").glob("*")) if p.is_file()]
    if not jobs:
        jobs += [(p, PACKS_PREFIX) for p in default_voice_files(ROOT / ".models" / "release")]
    print(f"Bucket {BUCKET} → {BASE_URL}")
    try:
        for path, prefix in jobs:
            put(path, prefix, index, args.force)
    finally:
        index_path.write_text(json.dumps(index, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("Listo. Comprobar p. ej.: curl -I " + f"{BASE_URL}/{jobs[-1][1]}/{jobs[-1][0].name}")


if __name__ == "__main__":
    main()
