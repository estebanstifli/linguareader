"""Convierte un libro graduado (.txt de content/graded-books) en un EPUB 3 (con NCX para lectores antiguos).

Uso:
  python -I tools/build_epub.py es/A1/el-sastrecillo-valiente [--cover portada.jpg] [--out libro.epub]

Sin --cover usa la portada compuesta de make_covers.py (o una provisional si aún no hay ilustración).
Por defecto escribe el EPUB junto al .txt. Metadatos (para el importador de LinguaReader):
  dc:language, dc:title, dc:creator, dc:subject «CEFR A1» y meta schema:educationalLevel = A1.
"""
import argparse
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path
from xml.sax.saxutils import escape

sys.path.insert(0, str(Path(__file__).resolve().parent))
from library_meta import BOOKS_DIR, books, chapters  # noqa: E402

CREDITS = {
    "en": ("Graded adaptation", "LinguaReader"),
    "es": ("Adaptación graduada y traducción", "LinguaReader"),
    "fr": ("Adaptation graduée et traduction", "LinguaReader"),
}
CHAPTER_WORD = {"en": "Chapter", "es": "Capítulo", "fr": "Chapitre", "de": "Kapitel", "it": "Capitolo",
                "pt": "Capítulo"}
CONTENTS = {"en": "Contents", "es": "Índice", "fr": "Table des matières", "de": "Inhalt", "it": "Indice",
            "pt": "Índice"}
CSS = """body { font-family: serif; line-height: 1.5; margin: 0 5%; }
h2 { font-size: 1.3em; margin: 1.5em 0 1em; }
p { margin: 0 0 0.8em; text-indent: 0; }
.cover { margin: 0; padding: 0; text-align: center; }
.cover img { max-width: 100%; max-height: 100vh; }
"""


def xhtml(lang, title, body):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="{lang}" lang="{lang}">
<head><meta charset="utf-8"/><title>{escape(title)}</title><link rel="stylesheet" type="text/css" href="style.css"/></head>
<body>
{body}
</body>
</html>
"""


def build(book, cover_jpeg: bytes, out_path: Path):
    lang, level = book["lang"], book["level"]
    chs = chapters(book["txt"])
    ident = f"urn:linguareader:{lang}-{book['slug']}"
    # Fecha del texto, no de la compilación: el EPUB sale idéntico si nada cambia (sha256 estable).
    modified = datetime.fromtimestamp(Path(book["txt"]).stat().st_mtime, timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    role, credit = CREDITS.get(lang, CREDITS["en"])
    word = CHAPTER_WORD.get(lang, "Chapter")

    files = {}   # ruta dentro de OEBPS -> (bytes, media-type, id, properties)
    files["style.css"] = (CSS.encode(), "text/css", "css", None)
    files["images/cover.jpg"] = (cover_jpeg, "image/jpeg", "cover-image", "cover-image")
    files["cover.xhtml"] = (xhtml(lang, book["title"],
                                  f'<div class="cover"><img src="images/cover.jpg" alt="{escape(book["title"])}"/></div>'
                                  ).encode(), "application/xhtml+xml", "cover", None)
    spine = ["cover"]
    toc = []
    for i, (title, paragraphs) in enumerate(chs, 1):
        name = f"chap{i:02d}.xhtml"
        heading = f'<h2 id="ch{i}" aria-label="{word} {i}: {escape(title)}">{escape(title)}</h2>'
        body = heading + "\n" + "\n".join(f"<p>{escape(p)}</p>" for p in paragraphs)
        files[name] = (xhtml(lang, title, body).encode(), "application/xhtml+xml", f"c{i:02d}", None)
        spine.append(f"c{i:02d}")
        toc.append((name, title))

    nav_items = "\n".join(f'<li><a href="{n}">{escape(t)}</a></li>' for n, t in toc)
    files["nav.xhtml"] = (xhtml(lang, CONTENTS.get(lang, "Contents"),
                                f'<nav epub:type="toc" id="toc"><h1>{CONTENTS.get(lang, "Contents")}</h1><ol>\n'
                                f'{nav_items}\n</ol></nav>').encode(), "application/xhtml+xml", "nav", "nav")
    ncx_points = "\n".join(
        f'<navPoint id="n{i}" playOrder="{i}"><navLabel><text>{escape(t)}</text></navLabel>'
        f'<content src="{n}"/></navPoint>' for i, (n, t) in enumerate(toc, 1))
    files["toc.ncx"] = (f"""<?xml version="1.0" encoding="utf-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1" xml:lang="{lang}">
<head><meta name="dtb:uid" content="{ident}"/></head>
<docTitle><text>{escape(book['title'])}</text></docTitle>
<navMap>
{ncx_points}
</navMap>
</ncx>
""".encode(), "application/x-dtbncx+xml", "ncx", None)

    manifest = "\n".join(
        f'    <item id="{i}" href="{path}" media-type="{mt}"' + (f' properties="{pr}"' if pr else "") + "/>"
        for path, (_, mt, i, pr) in files.items())
    creators = "\n".join(f'    <dc:creator id="author{k}">{escape(a)}</dc:creator>'
                         for k, a in enumerate(book["authors"], 1))
    opf = f"""<?xml version="1.0" encoding="utf-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="{lang}"
         prefix="schema: http://schema.org/">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="bookid">{ident}</dc:identifier>
    <dc:title>{escape(book['title'])}</dc:title>
{creators}
    <dc:contributor id="adapter">{credit}</dc:contributor>
    <meta refines="#adapter" property="role" scheme="marc:relators">adp</meta>
    <dc:language>{lang}</dc:language>
    <dc:subject>CEFR {level}</dc:subject>
    <dc:rights>{escape(book['license'])}</dc:rights>
    <dc:description>{escape(role)} ({level}) · {escape(book['original'])}</dc:description>
    <meta property="schema:educationalLevel">{level}</meta>
    <meta property="dcterms:modified">{modified}</meta>
    <meta name="cover" content="cover-image"/>
  </metadata>
  <manifest>
{manifest}
  </manifest>
  <spine toc="ncx">
{chr(10).join(f'    <itemref idref="{s}"/>' for s in spine)}
  </spine>
</package>
"""
    container = """<?xml version="1.0" encoding="utf-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
"""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(".tmp")
    with zipfile.ZipFile(tmp, "w") as z:
        put(z, "mimetype", "application/epub+zip", zipfile.ZIP_STORED)
        put(z, "META-INF/container.xml", container)
        put(z, "OEBPS/content.opf", opf)
        for path, (data, mt, _, _) in files.items():
            put(z, f"OEBPS/{path}", data, zipfile.ZIP_STORED if mt.startswith("image/") else zipfile.ZIP_DEFLATED)
    validate(tmp)
    tmp.replace(out_path)
    return out_path


def put(z, name, data, method=zipfile.ZIP_DEFLATED):
    """Entrada con fecha fija para que el zip sea reproducible."""
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = method
    z.writestr(info, data)


def validate(path):
    """Comprobación interna: mimetype primero y sin comprimir, XML bien formado, manifiesto completo."""
    import xml.dom.minidom
    with zipfile.ZipFile(path) as z:
        first = z.infolist()[0]
        assert first.filename == "mimetype" and first.compress_type == zipfile.ZIP_STORED, "mimetype"
        names = set(z.namelist())
        opf = xml.dom.minidom.parseString(z.read("OEBPS/content.opf"))
        for item in opf.getElementsByTagName("item"):
            href = "OEBPS/" + item.getAttribute("href")
            assert href in names, f"falta {href}"
            if href.endswith((".xhtml", ".ncx")):
                xml.dom.minidom.parseString(z.read(href))


def find_book(key):
    lang, level, slug = key.strip("/").removesuffix(".txt").split("/")[-3:]
    for b in books():
        if (b["lang"], b["level"], b["slug"]) == (lang, level, slug):
            return b
    sys.exit(f"No está en el catálogo del README: {key}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser()
    ap.add_argument("book", help="<idioma>/<NIVEL>/<slug>, p. ej. es/A1/el-sastrecillo-valiente")
    ap.add_argument("--cover", help="JPEG de portada (por defecto, la compuesta por make_covers.py)")
    ap.add_argument("--out")
    args = ap.parse_args()
    book = find_book(args.book)
    if args.cover:
        cover = Path(args.cover).read_bytes()
    else:
        from make_covers import compose_cover
        cover = compose_cover(book)
    out = Path(args.out) if args.out else BOOKS_DIR / book["lang"] / book["level"] / f"{book['slug']}.epub"
    build(book, cover, out)
    print(f"{out}  {out.stat().st_size // 1024} KiB")


if __name__ == "__main__":
    main()
