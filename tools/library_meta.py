"""Metadatos de los libros graduados a partir de content/graded-books/README.md (catálogo inglés) y de
content/graded-books/<idioma>/README.md (traducciones, ver graded_langs.py).

Lo usan make_covers.py, build_epub.py y build_library.py. Cada libro es un dict con:
  lang, level, slug, title, authors, year, work (slug inglés de la obra: clave de la ilustración),
  description (columna «Original»), license, txt (ruta), source (fichero inglés si es traducción).
"""
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import graded_langs  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
BOOKS_DIR = ROOT / "content" / "graded-books"
README = BOOKS_DIR / "README.md"

SOURCE_ROW = re.compile(
    r"^\| `en/(A1|A2|B1|B2)/([^`]+)\.txt` \| ([^|]+?) \| (?:A1|A2|B1|B2) \| [^|]* \| [^|]* \| [^|]* \| "
    r"([^|]+?) \| ([^|]+?) \|$", re.M)

# Autores colectivos: se dan en el idioma del libro.
AUTHOR_NAMES = {
    "Hermanos Grimm": {"en": "Brothers Grimm", "es": "Hermanos Grimm", "fr": "Frères Grimm",
                       "de": "Brüder Grimm", "it": "Fratelli Grimm", "pt": "Irmãos Grimm",
                       "nl": "Gebroeders Grimm", "pl": "Bracia Grimm", "sv": "Bröderna Grimm"},
    "Cuento popular inglés": {"en": "English folk tale", "es": "Cuento popular inglés",
                              "fr": "Conte populaire anglais", "de": "Englisches Volksmärchen",
                              "it": "Fiaba popolare inglese", "pt": "Conto popular inglês",
                              "nl": "Engels volksverhaal", "pl": "Angielska baśń ludowa",
                              "sv": "Engelsk folksaga"},
    "Cuento popular estadounidense": {"en": "American folk tale", "es": "Cuento popular estadounidense",
                                      "fr": "Conte populaire américain", "de": "Amerikanisches Volksmärchen",
                                      "it": "Fiaba popolare americana", "pt": "Conto popular americano",
                                      "nl": "Amerikaans volksverhaal", "pl": "Amerykańska opowieść ludowa",
                                      "sv": "Amerikansk folksaga"},
}

# Autores en ruso, coreano e hindi (sus alfabetos no usan la forma latina).
for _name, (_ru, _ko, _hi) in {
    "Hermanos Grimm": ("Братья Гримм", "그림 형제", "ग्रिम बंधु"),
    "Cuento popular inglés": ("Английская народная сказка", "영국 민담", "अंग्रेज़ी लोककथा"),
    "Cuento popular estadounidense": ("Американская народная сказка", "미국 민담", "अमेरिकी लोककथा"),
    "Carlo Collodi": ("Карло Коллоди", "카를로 콜로디", "कार्लो कोलोडी"),
    "Charles Dickens": ("Чарльз Диккенс", "찰스 디킨스", "चार्ल्स डिकेंस"),
    "Charles Perrault": ("Шарль Перро", "샤를 페로", "शार्ल पेरो"),
    "Hans Christian Andersen": ("Ганс Христиан Андерсен", "한스 크리스티안 안데르센", "हैंस क्रिश्चियन एंडरसन"),
    "Jane Austen": ("Джейн Остин", "제인 오스틴", "जेन ऑस्टेन"),
    "Jeanne-Marie Leprince de Beaumont": ("Жанна-Мари Лепренс де Бомон", "잔마리 르프랭스 드 보몽", "जान-मारी लेप्रिंस द बोमों"),
    "Jules Verne": ("Жюль Верн", "쥘 베른", "जूल वर्न"),
    "Mary Shelley": ("Мэри Шелли", "메리 셸리", "मैरी शेली"),
    "Robert Louis Stevenson": ("Роберт Льюис Стивенсон", "로버트 루이스 스티븐슨", "रॉबर्ट लुई स्टीवेन्सन"),
}.items():
    AUTHOR_NAMES.setdefault(_name, {}).update(ru=_ru, ko=_ko, hi=_hi)

# Autores en turco, griego y ucraniano.
for _name, (_tr, _el, _uk) in {
    "Hermanos Grimm": ("Grimm Kardeşler", "Αδελφοί Γκριμ", "Брати Грімм"),
    "Cuento popular inglés": ("İngiliz halk masalı", "Αγγλικό λαϊκό παραμύθι", "Англійська народна казка"),
    "Cuento popular estadounidense": ("Amerikan halk masalı", "Αμερικανικό λαϊκό παραμύθι", "Американська народна казка"),
    "Carlo Collodi": ("Carlo Collodi", "Κάρλο Κολόντι", "Карло Коллоді"),
    "Charles Dickens": ("Charles Dickens", "Κάρολος Ντίκενς", "Чарльз Діккенс"),
    "Charles Perrault": ("Charles Perrault", "Σαρλ Περό", "Шарль Перро"),
    "Hans Christian Andersen": ("Hans Christian Andersen", "Χανς Κρίστιαν Άντερσεν", "Ганс Крістіан Андерсен"),
    "Jane Austen": ("Jane Austen", "Τζέιν Όστιν", "Джейн Остін"),
    "Jeanne-Marie Leprince de Beaumont": ("Jeanne-Marie Leprince de Beaumont", "Ζαν-Μαρί Λεπρένς ντε Μπομόν", "Жанна-Марі Лепренс де Бомон"),
    "Jules Verne": ("Jules Verne", "Ιούλιος Βερν", "Жуль Верн"),
    "Mary Shelley": ("Mary Shelley", "Μέρι Σέλεϊ", "Мері Шеллі"),
    "Robert Louis Stevenson": ("Robert Louis Stevenson", "Ρόμπερτ Λιούις Στίβενσον", "Роберт Луїс Стівенсон"),
}.items():
    AUTHOR_NAMES.setdefault(_name, {}).update(tr=_tr, el=_el, uk=_uk)

CHAPTER = re.compile(r"^(?:(?:chapter|capítulo|chapitre|kapitel|capitolo|hoofdstuk|rozdział|глава|अध्याय"
                     r"|bölüm|κεφάλαιο|розділ)\s+\d+"
                     r"|제\s*\d+\s*장)\s*:\s*(.+)$", re.I)
END_LINES = {"the end", "fin", "ende", "fine", "fim", "einde", "koniec", "slut", "конец", "끝", "समाप्त", "son", "τέλος", "кінець"}


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
    for lang in graded_langs.languages():
        for r in graded_langs.parse(lang)["rows"]:
            src = sources.get(r["source_slug"])
            if not src or not r["done"] or not r["target"]:
                continue
            out.append(dict(src, lang=lang, level=r["level"], slug=r["slug"], title=r["title"],
                            authors=[_author(src["original"], lang)], license=graded_langs.TRANSLATION_LICENSE,
                            txt=BOOKS_DIR / r["target"], source=src["txt"]))
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
