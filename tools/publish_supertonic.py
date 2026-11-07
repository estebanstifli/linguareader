"""Publica el modelo Supertonic verificado en el R2 público de Lingoaudiobook.

  python -I tools/publish_supertonic.py                  # validar y mostrar el plan, sin red
  python -I tools/publish_supertonic.py --upload         # publicar/reanudar y verificar por HTTP
  python -I tools/publish_supertonic.py --verify-remote  # comprobar la copia pública, sin subir

Lee revisión, tamaños y SHA-256 del catálogo Kotlin de producción. Solo publica
sus 17 archivos originales y tres documentos reproducibles. Los pesos no se
modifican y el grafo de tiempos se genera en la app, por lo que no se distribuye
una segunda copia del estimador. Usa el Wrangler ya autenticado de book_web.
Una clave remota que contiene bytes distintos provoca un error: no se fuerza
la sustitución de objetos de una revisión inmutable.
"""
from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import subprocess
import sys
import tempfile
import time
from urllib.error import HTTPError
from urllib.parse import quote
from urllib.request import Request, urlopen
import uuid


ROOT = Path(__file__).resolve().parent.parent
CATALOG = ROOT / "engine/supertonic/src/commonMain/kotlin/com/linguareader/engine/supertonic/SupertonicModel.kt"
BUCKET = "lingoaudiobook-packs"
BASE_URL = "https://files.lingoaudiobook.eu"
WEB_DIR = Path(os.environ.get("LINGO_WEB_DIR", ROOT.parent / "book_web"))
CACHE_CONTROL = "public, max-age=31536000, immutable"


@dataclass(frozen=True)
class ModelFile:
    path: str
    size: int
    sha256: str


@dataclass(frozen=True)
class Catalog:
    revision: str
    repository: str
    license_name: str
    files: tuple[ModelFile, ...]

    @property
    def prefix(self) -> str:
        return f"models/supertonic-3/{self.revision}"

    @property
    def total_bytes(self) -> int:
        return sum(item.size for item in self.files)


@dataclass(frozen=True)
class PublicationFile:
    path: str
    source: Path
    size: int
    sha256: str


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def safe_model_path(name: str) -> bool:
    path = PurePosixPath(name)
    return bool(name) and not path.is_absolute() and "\\" not in name and ":" not in name and all(
        part not in ("", ".", "..") for part in name.split("/")
    )


def read_catalog(path: Path = CATALOG) -> Catalog:
    source = path.read_text(encoding="utf-8")

    def constant(name: str) -> str:
        match = re.search(rf'const val {name}\s*=\s*"([^"\n]+)"', source)
        if not match:
            raise ValueError(f"Missing literal {name} in model catalog")
        return match.group(1)

    revision = constant("REVISION")
    if constant("ENGINE_ID") != "supertonic-3" or not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("Unexpected engine or non-pinned model revision")
    files = tuple(ModelFile(name, int(size.replace("_", "")), sha) for name, size, sha in re.findall(
        r'ModelFile\("([^"\n]+)",\s*([\d_]+),\s*"([a-f0-9]{64})"\)', source
    ))
    if len(files) != 17 or len({item.path for item in files}) != 17:
        raise ValueError("Expected exactly 17 unique, hash-pinned original model files")
    if not any(item.path == "LICENSE" for item in files):
        raise ValueError("The original model license must be included")
    if not all(safe_model_path(item.path) and item.size > 0 for item in files):
        raise ValueError("Unsafe path or invalid file size in model catalog")
    repository = constant("REPO")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("Invalid upstream repository identifier")
    return Catalog(revision, repository, constant("LICENSE_NAME"), files)


def validate_sources(catalog: Catalog, directory: Path) -> list[PublicationFile]:
    root = directory.resolve(strict=True)
    result = []
    # Validate every source before the first upload, not halfway through publication.
    for item in catalog.files:
        source = (root / item.path).resolve(strict=True)
        if not source.is_relative_to(root) or not source.is_file():
            raise ValueError(f"Invalid source path: {item.path}")
        if source.stat().st_size != item.size or digest_file(source) != item.sha256:
            raise ValueError(f"Original model integrity mismatch: {item.path}")
        result.append(PublicationFile(item.path, source, item.size, item.sha256))
    return result


def publication_documents(catalog: Catalog) -> dict[str, bytes]:
    upstream = f"https://huggingface.co/{catalog.repository}/tree/{catalog.revision}"
    manifest = {
        "formatVersion": 1,
        "engineId": "supertonic-3",
        "revision": catalog.revision,
        "upstream": upstream,
        "license": {"name": catalog.license_name, "path": "LICENSE"},
        "weightsModified": False,
        "wordTimingGraph": "Generated locally by the app from its embedded graph and these original weights; not downloaded.",
        "baseUrl": f"{BASE_URL}/{catalog.prefix}",
        "totalModelBytes": catalog.total_bytes,
        "files": [{"path": item.path, "sizeBytes": item.size, "sha256": item.sha256} for item in catalog.files],
    }
    notice = (
        "Supertonic 3 — original model mirror for Lingoaudiobook\n\n"
        f"Original publisher/repository: {catalog.repository}\n"
        f"Immutable upstream revision: {catalog.revision}\n"
        f"Source: {upstream}\n"
        f"Model license: {catalog.license_name}; the original LICENSE accompanies the model.\n\n"
        "The 17 original model files are redistributed byte-for-byte without changes.\n"
        "Use and redistribution remain subject to all terms and use restrictions in LICENSE.\n"
        "Lingoaudiobook's word-timing inference graph is generated on the device from\n"
        "the application's embedded graph definition and the original model weights.\n"
        "That additional graph does not change these downloaded weights.\n"
    ).encode("utf-8")
    sums = "".join(f"{item.sha256}  {item.path}\n" for item in catalog.files).encode("utf-8")
    return {
        "NOTICE.txt": notice,
        "SHA256SUMS": sums,
        # Publish this last as the completion record for the immutable revision.
        "manifest.json": (json.dumps(manifest, indent=2, ensure_ascii=False) + "\n").encode("utf-8"),
    }


def content_type(path: str) -> str:
    if path.endswith(".json"):
        return "application/json; charset=utf-8"
    if path in {"LICENSE", "NOTICE.txt", "SHA256SUMS"}:
        return "text/plain; charset=utf-8"
    return "application/octet-stream"


def remote_identity(url: str) -> tuple[int, str] | None:
    # A unique query avoids a stale cached 404 or object during verification.
    request = Request(url + "?verify=" + uuid.uuid4().hex,
                      headers={"Accept-Encoding": "identity", "Cache-Control": "no-cache",
                               "User-Agent": "LingoAudioBook-model-publisher/1.0"})
    try:
        with urlopen(request, timeout=120) as response:
            if response.status != 200:
                raise RuntimeError(f"Unexpected public HTTP status {response.status}: {url}")
            digest = hashlib.sha256()
            size = 0
            while chunk := response.read(1 << 20):
                size += len(chunk)
                digest.update(chunk)
            declared = response.headers.get("Content-Length")
            if declared is not None and int(declared) != size:
                raise RuntimeError(f"Truncated public object: {url}")
            return size, digest.hexdigest()
    except HTTPError as error:
        if error.code == 404:
            return None
        raise


def wrangler_put(file: PublicationFile, key: str, web_dir: Path = WEB_DIR) -> None:
    script = web_dir / "node_modules/wrangler/bin/wrangler.js"
    node = shutil.which("node")
    if node is None or not script.is_file():
        raise RuntimeError(f"Node/Wrangler unavailable in {web_dir}; set LINGO_WEB_DIR if needed")
    # Invoke Node directly: no cmd.exe/string interpolation of paths or MIME headers.
    subprocess.run([
        node, str(script), "r2", "object", "put", f"{BUCKET}/{key}",
        "--file", str(file.source), "--content-type", content_type(file.path),
        "--cache-control", CACHE_CONTROL, "--remote",
    ], cwd=web_dir, check=True)


def publish_one(file: PublicationFile, prefix: str, upload: bool,
                identify=remote_identity, put=wrangler_put) -> str:
    key = f"{prefix}/{file.path}"
    url = f"{BASE_URL}/{quote(key, safe='/')}"
    expected = (file.size, file.sha256)
    actual = identify(url)
    if actual is not None:
        if actual != expected:
            raise RuntimeError(f"Refusing to overwrite different content at immutable key: {key}")
        print(f"  = {key} (SHA-256 público verificado)", flush=True)
        return "verified-existing"
    if not upload:
        raise RuntimeError(f"Missing public object: {key}")
    # Guard against a source edited after the all-files preflight.
    if file.source.stat().st_size != file.size or digest_file(file.source) != file.sha256:
        raise RuntimeError(f"Source changed after verification: {file.path}")
    print(f"  UPLOAD {key} ({file.size:,} bytes)", flush=True)
    put(file, key)
    # Give a new R2 object a short visibility window, but never accept wrong bytes.
    for attempt in range(5):
        actual = identify(url)
        if actual is not None:
            if actual != expected:
                raise RuntimeError(f"Public verification failed after upload: {key}")
            print(f"  OK {key} (SHA-256 público verificado)", flush=True)
            return "uploaded-and-verified"
        if attempt < 4:
            time.sleep(2)
    raise RuntimeError(f"Uploaded object is not publicly visible: {key}")


def main(argv: list[str] | None = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--model-dir", type=Path, help="carpeta de los archivos oficiales verificados")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--upload", action="store_true", help="publicar/reanudar y verificar íntegramente la copia pública")
    mode.add_argument("--verify-remote", action="store_true", help="verificar bytes públicos sin modificar R2")
    parser.add_argument("--report", type=Path, help="guardar el resultado JSON de una operación completada")
    args = parser.parse_args(argv)
    catalog = read_catalog()
    model_dir = args.model_dir or ROOT / ".models/supertonic-3" / catalog.revision
    files = validate_sources(catalog, model_dir)
    print(f"Modelo original verificado: {len(files)} archivos, {catalog.total_bytes:,} bytes.")
    print(f"Destino: {BASE_URL}/{catalog.prefix}/")
    print("El grafo interno de tiempos no se sube: lo prepara la app.")
    report = {"mode": "upload" if args.upload else "verify-remote" if args.verify_remote else "dry-run",
              "bucket": BUCKET, "prefix": catalog.prefix, "baseUrl": BASE_URL,
              "revision": catalog.revision, "totalModelBytes": catalog.total_bytes, "files": []}
    with tempfile.TemporaryDirectory(prefix="lingoaudiobook-supertonic-publish-") as temporary:
        for name, data in publication_documents(catalog).items():
            source = Path(temporary) / name
            source.write_bytes(data)
            files.append(PublicationFile(name, source, len(data), hashlib.sha256(data).hexdigest()))
        for file in files:
            if args.upload or args.verify_remote:
                status = publish_one(file, catalog.prefix, args.upload)
            else:
                status = "planned"
                print(f"  · {file.path}: {file.size:,} bytes, {content_type(file.path)}")
            report["files"].append({"path": file.path, "sizeBytes": file.size, "sha256": file.sha256, "status": status})
    report["complete"] = True
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print("Listo." if args.upload or args.verify_remote else "Simulación local: no se ha accedido a R2 ni publicado nada.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise SystemExit(1)
