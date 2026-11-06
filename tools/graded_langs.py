"""Ficheros de control de las traducciones: content/graded-books/<idioma>/README.md.

Uno por idioma, con una sola tabla «## Libros» (fila ⬜ = pendiente, ✅ = traducida y verificada, con
sus métricas) y el glosario de ese idioma. Lo leen translate_runner.py (qué falta), library_meta.py
(qué hay, para EPUB y bibliotecas) y la skill /graded-translate (qué actualizar).

Uso directo:  python -I tools/graded_langs.py [de]   → resumen de los ficheros de control.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BOOKS_DIR = ROOT / "content" / "graded-books"

TRANSLATION_LICENSE = "Traducción propia de la adaptación propia"

# Por idioma: nombre, variedad, tipografía y cabeceras que debe usar la traducción.
LANGS = {
    "es": {
        "name": "español", "title": "Traducciones al español (es)",
        "variety": "español peninsular estándar (con *vosotros*, sin regionalismos marcados)",
        "dialogue": "raya (`—`), `¿?` y `¡!` de apertura; carteles y citas con comillas latinas « »",
        "chapter": "Capítulo N: Título", "end": "Fin",
        "readability": "INFLESZ (> 80 muy fácil, 65-80 bastante fácil)",
    },
    "fr": {
        "name": "francés", "title": "Traductions en français (fr)",
        "variety": "francés de Francia",
        "dialogue": "guillemets « » con inciso invertido (`dit-il`); espacios no separables antes de `; : ! ? »`",
        "chapter": "Chapitre N : Titre", "end": "Fin",
        "readability": "Kandel-Moles (> 80 très facile, 70-80 facile)",
    },
    "de": {
        "name": "alemán", "title": "Übersetzungen ins Deutsche (de)",
        "variety": "alemán estándar (Hochdeutsch, ortografía de 2006; `ß` donde corresponde)",
        "dialogue": "comillas alemanas „…“ (nunca \" ni « »); inciso con `, sagt er.`",
        "chapter": "Kapitel N: Titel", "end": "Ende",
        "readability": "Flesch-Amstad (> 80 sehr leicht, 70-80 leicht)",
    },
    "it": {
        "name": "italiano", "title": "Traduzioni in italiano (it)",
        "variety": "italiano estándar",
        "dialogue": "caporali « » (o raya al inicio de línea); nunca comillas rectas \"",
        "chapter": "Capitolo N: Titolo", "end": "Fine",
        "readability": "Gulpease (> 80 facile para lector elemental, 60-80 media)",
    },
    "pt": {
        "name": "portugués", "title": "Traduções em português (pt)",
        "variety": "portugués europeo (Portugal; *tu* en familia y amigos, *o senhor / a senhora* o *você* con desconocidos)",
        "dialogue": "travessão (`—`) al inicio del diálogo; nunca comillas rectas \"",
        "chapter": "Capítulo N: Título", "end": "Fim",
        "readability": "Flesch-Martins (> 75 muito fácil, 50-75 fácil)",
    },
    "nl": {
        "name": "neerlandés", "title": "Vertalingen in het Nederlands (nl)",
        "variety": "neerlandés estándar de los Países Bajos (*jij* en familia y amigos, *u* con desconocidos y autoridades)",
        "dialogue": "comillas “…” (nunca \" ni « » ni „); inciso con `, zegt hij.`",
        "chapter": "Hoofdstuk N: Titel", "end": "Einde",
        "readability": "Flesch-Douma (> 80 zeer makkelijk, 60-80 makkelijk)",
    },
    "pl": {
        "name": "polaco", "title": "Tłumaczenia na język polski (pl)",
        "variety": "polaco estándar (*ty* en familia y amigos, *pan / pani* con desconocidos)",
        "dialogue": "pauza (`—`) al inicio del diálogo: `— Siedem! — mówi. — Siedem za jednym zamachem!`; citas y carteles con „…”",
        "chapter": "Rozdział N: Tytuł", "end": "Koniec",
        "readability": "FOG-PL (< 7 bardzo łatwy, 7-9 łatwy, 10-12 średni)",
    },
    "sv": {
        "name": "sueco", "title": "Översättningar till svenska (sv)",
        "variety": "sueco estándar de Suecia (*du* con casi todos; *ni* de cortesía solo con reyes y nobles)",
        "dialogue": "comillas suecas ”…” (la misma comilla al abrir y cerrar; nunca \" ni « » ni „ “)",
        "chapter": "Kapitel N: Titel", "end": "Slut",
        "readability": "LIX (< 30 mycket lätt, 30-40 lätt, 40-50 medel)",
    },
    "ru": {
        "name": "ruso", "title": "Переводы на русский язык (ru)",
        "variety": "ruso estándar (*ты* en familia y amigos, *вы* con desconocidos; ё escrita siempre)",
        "dialogue": "raya (`—`) al inicio del diálogo: `— Семь! — говорит он. — Семь одним ударом!`; citas y carteles con «…»",
        "chapter": "Глава N: Название", "end": "Конец",
        "readability": "Flesch-Oborneva (> 80 очень легко, 60-80 легко)",
    },
    "ko": {
        "name": "coreano", "title": "한국어 번역 (ko)",
        "variety": "coreano estándar de Seúl; narración en estilo -다 (해라체), diálogos en 해요체 o 반말 según la relación",
        "dialogue": "comillas “…” con verbo de cita: `“일곱!” 하고 그가 말해요.`; nunca « » ni \"",
        "chapter": "제N장: 제목", "end": "끝",
        "readability": "Sílabas/palabra (orientativo; sin índice estándar)",
    },
    "hi": {
        "name": "hindi", "title": "हिंदी अनुवाद (hi)",
        "variety": "hindi estándar en devanagari, vocabulario cotidiano (hindustani), sin sánscrito culto ni urdu marcado",
        "dialogue": "comillas “…” y danda `।` como punto final; nunca « » ni \"",
        "chapter": "अध्याय N: शीर्षक", "end": "समाप्त",
        "readability": "Sílabas/palabra (orientativo; sin índice estándar)",
    },
}

# Los 16 libros con los que arranca cada idioma nuevo (slugs ingleses, en orden de trabajo).
DEFAULT_PLAN = [
    "A1/the-brave-little-tailor", "A1/hansel-and-gretel", "A1/little-red-riding-hood", "A1/snow-white",
    "A1/cinderella", "A1/the-three-little-pigs",
    "A2/puss-in-boots", "A2/the-emperors-new-clothes", "A2/beauty-and-the-beast", "A2/the-little-mermaid",
    "A2/the-adventures-of-pinocchio",
    "B1/treasure-island", "B1/a-christmas-carol", "B1/around-the-world-in-eighty-days",
    "B2/frankenstein", "B2/pride-and-prejudice",
]

COLUMNS = ["#", "Estado", "Nivel", "Original (en)", "Fichero", "Título", "Palabras", "Palabras/frase",
           "Legibilidad", "Notas"]
ROW = re.compile(
    r"^\| (\d+) \| (✅|⬜) \| (A1|A2|B1|B2) \| `en/(?:A1|A2|B1|B2)/([^`]+)\.txt` \| (?:`([a-z]{2})/(A1|A2|B1|B2)/([^`]+)\.txt`)? \|"
    r" ([^|]*?) \| ([^|]*?) \| ([^|]*?) \| ([^|]*?) \| ([^|]*?) \|$", re.M)
GLOSSARY_ENTRY = re.compile(r"^- \*\*(.+?)\*\*:(.*(?:\n  .*)*)", re.M)


def readme_path(lang):
    return BOOKS_DIR / lang / "README.md"


def parse(lang):
    """{'rows': [...], 'glossary': {título: texto}} del fichero de control de un idioma (o vacío)."""
    path = readme_path(lang)
    if not path.exists():
        return {"rows": [], "glossary": {}}
    text = path.read_text(encoding="utf-8")
    rows = []
    for m in ROW.finditer(text):
        n, state, level, src_slug, tgt_lang, tgt_level, tgt_slug, title, words, wps, read, notes = m.groups()
        rows.append({
            "n": int(n), "done": state == "✅", "level": level, "source_slug": src_slug,
            "source": f"en/{level}/{src_slug}.txt",
            "target": f"{tgt_lang}/{tgt_level}/{tgt_slug}.txt" if tgt_slug else None,
            "slug": tgt_slug, "title": title.strip(), "words": words.strip(), "wps": wps.strip(),
            "readability": read.strip(), "notes": notes.strip(),
        })
    glossary = {}
    gl = text.split("## Vocabulario", 1)
    if len(gl) == 2:
        for m in GLOSSARY_ENTRY.finditer(gl[1]):
            glossary[m[1]] = m[2].strip()
    return {"rows": rows, "glossary": glossary}


def _row_line(r):
    cells = [str(r["n"]), "✅" if r["done"] else "⬜", r["level"], f"`{r['source']}`",
             f"`{r['target']}`" if r.get("target") else "", r.get("title", ""), r.get("words", ""),
             r.get("wps", ""), r.get("readability", ""), r.get("notes", "")]
    return "| " + " | ".join(cells) + " |"


def render(lang, rows, glossary):
    """Texto completo del README del idioma."""
    info = LANGS[lang]
    lines = [
        f"# {info['title']}",
        "",
        "Fichero de control de las traducciones a este idioma (lo leen `tools/translate_runner.py`,",
        "`tools/library_meta.py` y la skill `/graded-translate`). Una fila por libro: ⬜ pendiente, ✅",
        "traducido y verificado con `tools/translation_check.py`. Mismo nivel MCER, mismos capítulos y",
        f"mismo número de párrafos que el original inglés (1:1, para la vista bilingüe). Licencia de todas",
        f"las filas: {TRANSLATION_LICENSE.lower()} (la del libro inglés).",
        "",
        f"- Variedad: {info['variety']}.",
        f"- Diálogos: {info['dialogue']}.",
        f"- Cabecera de capítulo: `{info['chapter']}`; última línea: `{info['end']}`.",
        f"- Legibilidad: {info['readability']}.",
        "",
        f"Orden de trabajo: `/graded-translate` sin libro (o `tools/translate_runner.py --lang {lang}`)",
        "toma la primera fila ⬜. Al terminar, la skill pone ✅ y rellena fichero, título y métricas; el",
        "glosario de abajo recibe una entrada con el título traducido.",
        "",
        "## Libros",
        "",
        "| " + " | ".join(COLUMNS) + " |",
        "|" + "---|" * len(COLUMNS),
    ]
    lines += [_row_line(r) for r in rows]
    lines += ["", "## Vocabulario fuera de nivel (para glosario)", "",
              "Palabras propias de la historia que pasan del nivel; el contexto las explica, pero conviene",
              "incluirlas en el glosario del libro. Una entrada por libro, con el título traducido.", ""]
    for title, body in glossary.items():
        lines.append(f"- **{title}**: {body}")
    return "\n".join(lines).rstrip() + "\n"


def write(lang, rows, glossary):
    path = readme_path(lang)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(render(lang, rows, glossary), encoding="utf-8", newline="\n")
    return path


def plan_rows(plan=DEFAULT_PLAN, start=1):
    """Filas ⬜ para un idioma nuevo a partir de la lista de slugs ingleses."""
    rows = []
    for i, item in enumerate(plan, start):
        level, slug = item.split("/", 1)
        rows.append({"n": i, "done": False, "level": level, "source_slug": slug,
                     "source": f"en/{level}/{slug}.txt", "target": None, "slug": None, "title": "",
                     "words": "", "wps": "", "readability": "", "notes": ""})
    return rows


def languages():
    """Idiomas con fichero de control."""
    return sorted(p.parent.name for p in BOOKS_DIR.glob("*/README.md") if p.parent.name in LANGS)


if __name__ == "__main__":
    import sys
    sys.stdout.reconfigure(encoding="utf-8")
    for lang in (sys.argv[1:] or languages()):
        data = parse(lang)
        done = [r for r in data["rows"] if r["done"]]
        missing = [r["target"] for r in done if r["target"] and not (BOOKS_DIR / r["target"]).exists()]
        print(f"{lang}: {len(done)} ✅ / {len(data['rows']) - len(done)} ⬜, {len(data['glossary'])} glosarios"
              + (f"  FALTAN FICHEROS: {missing}" if missing else ""))
