"""Prepara los paquetes descargables de voces y sus catálogos.

Entrada:  paquetes de voces por idioma generados con `./gradlew :engine:supertonic:voicePack`
          (.models/voice-pack/voices-<lang>-v1.zip).
Salida:   .models/release/voices-*.zip            -> objetos del bucket R2 bajo packs-v1/
          .models/release/voices.json             -> catálogo remoto de voces (la app lo refresca sin actualizarse)
          app/src/main/assets/packs/voices.json (tamaños y sha256 que verifica la app)

    python tools/make_release.py
    python tools/publish_packs.py                 # sube .models/release/* a https://files.lingoaudiobook.eu/packs-v1/
"""
import hashlib, json, pathlib, re, shutil

def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def prepare(root):
    rel = root / ".models/release"
    assets = root / "app/src/main/assets"
    rel.mkdir(parents=True, exist_ok=True)
    (assets / "packs").mkdir(parents=True, exist_ok=True)
    languages = {}
    for zip_ in sorted((root / ".models/voice-pack").glob("voices-*-v*.zip")):
        match = re.fullmatch(r"voices-([a-z]+)-v\d+\.zip", zip_.name)
        if match is None:
            raise ValueError(f"Unexpected voice pack filename: {zip_.name}")
        lang = match.group(1)
        shutil.copy(zip_, rel / zip_.name)
        languages[lang] = dict(asset=zip_.name, sizeBytes=zip_.stat().st_size, sha256=sha(zip_))
    (rel / "voices.zip").unlink(missing_ok=True)  # paquete único antiguo
    voices = json.dumps(dict(version=2, languages=languages), indent=2) + "\n"
    (assets / "packs/voices.json").write_text(voices, encoding="utf-8")
    (rel / "voices.json").write_text(voices, encoding="utf-8")
    # Historical aligner artifacts may remain in .models for research. They are neither
    # regenerated here nor selected by publish_packs.py's default voice-only manifest.
    print(f"voces: {len(languages)} idiomas")
    return languages


if __name__ == "__main__":
    prepare(pathlib.Path(__file__).resolve().parent.parent)
