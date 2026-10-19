"""Construye la biblioteca EPUB de cada idioma y su catálogo para la app.

  python -I tools/build_library.py                  # todos los idiomas con libros
  python -I tools/build_library.py --lang es,fr
  python -I tools/build_library.py --upload         # además sube a la release (gh)

Pasos por idioma: portada compuesta (make_covers.compose_cover) + EPUB junto a cada .txt
(build_epub.build) y el zip dist/library/library-<lang>-v<N>.zip con:
  library.json  {formatVersion, language, version, books: [{id, file, title, authors, level, words,
                 sizeBytes, sha256}]}
  books/<NIVEL>/<slug>.epub
La versión N solo sube si cambia el contenido (los EPUB son reproducibles). El catálogo
`libraries.json` ({formatVersion, libraries: {<lang>: {version, asset, sizeBytes, sha256, books, words}}})
se escribe en dist/library/ (adjunto de la release, para que la app vea bibliotecas nuevas sin
actualizarse) y en app/src/main/assets/packs/libraries.json (copia de respaldo sin red).
Las portadas sin ilustración (make_covers.py) salen provisionales: genera las ilustraciones antes.
"""
import argparse
import hashlib
import json
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_epub import build, put  # noqa: E402
from library_meta import BOOKS_DIR, ROOT, books, chapters  # noqa: E402
from make_covers import COVERS, compose_cover  # noqa: E402

DIST = ROOT / "dist" / "library"
ASSET_CATALOG = ROOT / "app" / "src" / "main" / "assets" / "packs" / "libraries.json"
TAG = "library-v1"
LEVEL_ORDER = {"A1": 0, "A2": 1, "B1": 2, "B2": 3}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def load_catalog():
    for path in (DIST / "libraries.json", ASSET_CATALOG):
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
    return {"formatVersion": 1, "libraries": {}}


def build_language(lang, items, previous):
    entries, contents = [], []
    missing = []
    for b in sorted(items, key=lambda b: (LEVEL_ORDER[b["level"]], b["title"])):
        if not (COVERS / f"{b['work']}.webp").exists():
            missing.append(b["work"])
        epub = build(b, compose_cover(b), BOOKS_DIR / lang / b["level"] / f"{b['slug']}.epub")
        data = epub.read_bytes()
        words = sum(len(p.split()) for _, ps in chapters(b["txt"]) for p in ps)
        entries.append({"id": f"lib-{lang}-{b['slug']}", "file": f"books/{b['level']}/{b['slug']}.epub",
                        "title": b["title"], "authors": b["authors"], "level": b["level"], "words": words,
                        "sizeBytes": len(data), "sha256": sha256(data)})
        contents.append((entries[-1]["file"], data))

    fingerprint = sha256(json.dumps(entries, sort_keys=True).encode())
    version = previous.get("version", 0)
    if previous.get("fingerprint") != fingerprint:
        version += 1
    library = {"formatVersion": 1, "language": lang, "version": version, "books": entries}
    DIST.mkdir(parents=True, exist_ok=True)
    asset = f"library-{lang}-v{version}.zip"
    zpath = DIST / asset
    with zipfile.ZipFile(zpath, "w") as z:
        put(z, "library.json", json.dumps(library, ensure_ascii=False, indent=2))
        for name, data in contents:
            put(z, name, data, zipfile.ZIP_STORED)   # los EPUB ya van comprimidos
    zdata = zpath.read_bytes()
    info = {"version": version, "asset": asset, "sizeBytes": len(zdata), "sha256": sha256(zdata),
            "books": len(entries), "words": sum(e["words"] for e in entries), "fingerprint": fingerprint}
    levels = {lvl: sum(1 for e in entries if e["level"] == lvl) for lvl in LEVEL_ORDER}
    print(f"{lang}: {len(entries)} libros {levels}, {len(zdata) / 1e6:.1f} MB, v{version}"
          + (f"  (portadas provisionales: {', '.join(missing)})" if missing else ""))
    return info


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", help="idiomas separados por comas (por defecto, todos)")
    ap.add_argument("--upload", action="store_true", help=f"subir zips y catálogo a la release {TAG}")
    ap.add_argument("--repo", default="estebanstifli/linguareader")
    args = ap.parse_args()

    all_books = books()
    langs = args.lang.split(",") if args.lang else sorted({b["lang"] for b in all_books})
    catalog = load_catalog()
    for lang in langs:
        items = [b for b in all_books if b["lang"] == lang]
        if items:
            catalog["libraries"][lang] = build_language(lang, items, catalog["libraries"].get(lang, {}))

    text = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
    (DIST / "libraries.json").write_text(text, encoding="utf-8")
    ASSET_CATALOG.parent.mkdir(parents=True, exist_ok=True)
    ASSET_CATALOG.write_text(text, encoding="utf-8")
    assets = [str(DIST / "libraries.json")] + [str(DIST / catalog["libraries"][l]["asset"]) for l in langs
                                               if l in catalog["libraries"]]
    cmd = ["gh", "release", "upload", TAG, *assets, "--clobber", "--repo", args.repo]
    if args.upload:
        subprocess.run(cmd, check=True)
    else:
        print("Para publicar (crea antes la release si no existe: gh release create "
              f"{TAG} --repo {args.repo} --title \"Biblioteca\" --notes \"Bibliotecas EPUB por idioma\"):")
        print("  " + " ".join(f'"{c}"' if " " in c else c for c in cmd))


if __name__ == "__main__":
    main()
