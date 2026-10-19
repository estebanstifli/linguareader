"""Metadatos de los libros graduados a partir de content/graded-books/README.md.

Lo usan make_covers.py, build_epub.py y build_library.py. Cada libro es un dict con:
  lang, level, slug, title, authors, year, work (slug inglés de la obra: clave de la ilustración),
  description (columna «Original»), license, txt (ruta), source (fichero inglés si es traducción).
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOOKS_DIR = ROOT / "content" / "graded-books"
README = BOOKS_DIR / "README.md"

SOURCE_ROW = re.compile(
    r"^\| `en/(A1|A2|B1|B2)/([^`]+)\.txt` \| ([^|]+?) \| (?:A1|A2|B1|B2) \| [^|]* \| [^|]* \| [^|]* \| "
    r"([^|]+?) \| ([^|]+?) \|$", re.M)
TRANSLATION_ROW = re.compile(
    r"^\| `([a-z]{2})/(A1|A2|B1|B2)/([^`]+)\.txt` \| ([^|]+?) \| (?:A1|A2|B1|B2) \|[^\n]*\| "
    r"`en/(?:A1|A2|B1|B2)/([^`]+)\.txt` \| ([^|]+?) \|$", re.M)

# Autores colectivos: se dan en el idioma del libro.
AUTHOR_NAMES = {
    "Hermanos Grimm": {"en": "Brothers Grimm", "es": "Hermanos Grimm", "fr": "Frères Grimm",
                       "de": "Brüder Grimm", "it": "Fratelli Grimm", "pt": "Irmãos Grimm"},
    "Cuento popular inglés": {"en": "English folk tale", "es": "Cuento popular inglés",
                              "fr": "Conte populaire anglais"},
    "Cuento popular estadounidense": {"en": "American folk tale", "es": "Cuento popular estadounidense",
                                      "fr": "Conte populaire américain"},
}

CHAPTER = re.compile(r"^(?:chapter|capítulo|chapitre|kapitel|capitolo)\s+\d+\s*:\s*(.+)$", re.I)
END_LINES = {"the end", "fin", "ende", "fine", "fim"}


def _author(original, lang):
    name = re.split(r",|\*|\(", original, maxsplit=1)[0].strip()
    return AUTHOR_NAMES.get(name, {}).get(lang, name)


def books():
    text = README.read_text(encoding="utf-8")
    sources = {}
    for level, slug, title, original, license in (m.groups() for m in SOURCE_ROW.finditer(text)):
        year = re.search(r"\b(1[5-9]\d\d)\b", original)
        sources[slug] = {
            "lang": "en", "level": level, "slug": slug, "title": title.strip(),
            "original": original.strip(), "authors": [_author(original, "en")],
            "year": year[1] if year else None, "work": slug, "description": original.strip(),
            "license": license.strip(), "txt": BOOKS_DIR / "en" / level / f"{slug}.txt", "source": None,
        }
    out = list(sources.values())
    for lang, level, slug, title, src_slug, license in (m.groups() for m in TRANSLATION_ROW.finditer(text)):
        src = sources.get(src_slug)
        if not src:
            continue
        out.append(dict(src, lang=lang, level=level, slug=slug, title=title.strip(),
                        authors=[_author(src["original"], lang)], license=license.strip(),
                        txt=BOOKS_DIR / lang / level / f"{slug}.txt", source=src["txt"]))
    return [b for b in out if b["txt"].exists()]


def chapters(txt_path):
    """[(título, [párrafos])] de un .txt del formato de content/graded-books."""
    result = []
    for block in re.split(r"\n\s*\n", Path(txt_path).read_text(encoding="utf-8").strip()):
        p = " ".join(block.split())
        m = CHAPTER.match(p)
        if m and len(p) <= 80:
            result.append((m[1].strip(), []))
        elif result and p:
            result[-1][1].append(p)
    if result and result[-1][1] and result[-1][1][-1].lower().strip(".! ") in END_LINES:
        result[-1][1].pop()
    return result


if __name__ == "__main__":
    import collections
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    bs = books()
    print(collections.Counter(b["lang"] for b in bs))
    for b in bs[:3] + [b for b in bs if b["lang"] != "en"][:2]:
        ch = chapters(b["txt"])
        print(b["lang"], b["level"], b["slug"], "|", b["title"], "|", b["authors"], b["year"],
              f"| {len(ch)} capítulos: {ch[0][0]!r} … {ch[-1][0]!r}, último párrafo: {ch[-1][1][-1][:40]!r}")
