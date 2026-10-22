"""Publica la narración (audio + tiempos por palabra en ASS de karaoke) de un libro graduado.

  python -I tools/publish_narration.py --lang en --slug the-three-little-pigs \
      --audio "test_mp3/The Three Little Pigs.mp3" --ass "test_mp3/The Three Little Pigs.ass" [--upload]

- Copia los ficheros a dist/narrations/ con nombres planos `narration-<lang>-<slug>-v<N>.<ext>` (la versión N
  solo sube si cambia el contenido) y registra tamaño y sha256 en content/graded-books/narrations.json.
- Con --upload los sube como adjuntos de la release de la biblioteca (gh).
- Después hay que regenerar la biblioteca del idioma (build_library.py): build_epub.py declara la narración en el
  OPF del libro (URL = build_epub.NARRATION_BASE_URL + nombre del adjunto) y la app la descarga al abrirlo.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_epub import NARRATIONS  # noqa: E402
from library_meta import BOOKS_DIR, ROOT  # noqa: E402

DIST = ROOT / "dist" / "narrations"
TAG = "library-v1"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", required=True)
    ap.add_argument("--slug", required=True)
    ap.add_argument("--audio", required=True, type=Path)
    ap.add_argument("--ass", required=True, type=Path)
    ap.add_argument("--upload", action="store_true", help=f"subir los adjuntos a la release {TAG}")
    ap.add_argument("--repo", default="estebanstifli/linguareader")
    args = ap.parse_args()

    if not list(BOOKS_DIR.glob(f"{args.lang}/*/{args.slug}.txt")):
        sys.exit(f"No existe el libro {args.lang}/{args.slug} en {BOOKS_DIR}")
    registry = json.loads(NARRATIONS.read_text(encoding="utf-8")) if NARRATIONS.exists() else {}
    registry.setdefault("formatVersion", 1)
    narrations = registry.setdefault("narrations", {})
    key = f"{args.lang}/{args.slug}"
    previous = narrations.get(key, {})
    sources = {"audio": args.audio, "timings": args.ass}
    hashes = {kind: sha256(path) for kind, path in sources.items()}
    version = previous.get("version", 0)
    if any(previous.get(kind, {}).get("sha256") != h for kind, h in hashes.items()):
        version += 1

    DIST.mkdir(parents=True, exist_ok=True)
    entry = {"version": version}
    files = []
    for kind, path in sources.items():
        asset = f"narration-{args.lang}-{args.slug}-v{version}{path.suffix.lower()}"
        target = DIST / asset
        shutil.copyfile(path, target)
        entry[kind] = {"asset": asset, "sizeBytes": target.stat().st_size, "sha256": hashes[kind]}
        files.append(str(target))
    narrations[key] = entry
    NARRATIONS.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{key}: v{version}, audio {entry['audio']['sizeBytes'] / 1e6:.1f} MB")

    cmd = ["gh", "release", "upload", TAG, *files, "--clobber", "--repo", args.repo]
    if args.upload:
        subprocess.run(cmd, check=True)
    else:
        print("Para publicar:\n  " + " ".join(f'"{c}"' if " " in c else c for c in cmd))
    print(f"Después: python -I tools/build_library.py --lang {args.lang} --upload")


if __name__ == "__main__":
    main()
