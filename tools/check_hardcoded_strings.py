"""Comprueba que la interfaz no tenga textos escritos en el código ni claves de idioma desparejadas.

  python -I tools/check_hardcoded_strings.py

1. Textos fijos: busca en app/src/main/java/**/ui/** literales con letras en argumentos visibles de Compose
   (Text(...), contentDescription, label, title, placeholder, text = ...). Las interpolaciones (${...}) y los
   literales sin letras ("·", "%", "${n}") no cuentan. El lint HardcodedText de Android no revisa Compose.
   Una línea se puede excluir con el comentario `// i18n-ignore` (p. ej. un nombre propio que no se traduce).
2. Claves: values/strings.xml (inglés, base) y cada values-xx/strings.xml deben tener las mismas claves,
   salvo las marcadas translatable="false".

Sale con código 1 si encuentra algo. Ver docs/I18N.md.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
UI = ROOT / "app/src/main/java/com/linguareader/ui"
RES = ROOT / "app/src/main/res"

# Argumentos de Compose que acaban en pantalla o en TalkBack.
VISIBLE = re.compile(
    r'(?:\bText\(\s*|\bcontentDescription\s*=\s*(?:if\s*\([^)]*\)\s*)?|\b(?:label|title|placeholder|text|detail|subtitle|message)\s*=\s*)'
    r'"((?:[^"\\]|\\.)*)"'
)
# ${...} completas, o cortadas por un literal anidado ("${if (x) a else ""}"), y $nombre.
INTERPOLATION = re.compile(r"\$\{[^}]*(?:\}|$)|\$[A-Za-z_][A-Za-z0-9_]*")
LETTERS = re.compile(r"[^\W\d_]{2,}")
LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
# Frase (dos palabras o más) en cualquier otro sitio: textos pasados por posición a composables propios.
PHRASE = re.compile(r"[^\W\d_]{2,}\s+[^\W\d_]{2,}")
# Mensajes para desarrolladores (excepciones, registros): no se muestran tal cual.
DEVELOPER = re.compile(r"\b(?:error|require|check|requireNotNull|checkNotNull)\(|\bthrow\b|\bLog\.[dwiev]\(")


def hardcoded_texts() -> list[str]:
    problems = []
    for path in sorted(UI.rglob("*.kt")):
        for n, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            stripped = line.strip()
            if stripped.startswith(("//", "*", "/*")) or "i18n-ignore" in line:
                continue
            code = line.split(" // ")[0]
            visible = any(LETTERS.search(INTERPOLATION.sub("", m.group(1))) for m in VISIBLE.finditer(code))
            phrase = not DEVELOPER.search(code) and any(
                PHRASE.search(INTERPOLATION.sub(" ", m.group(1))) for m in LITERAL.finditer(code)
            )
            if visible or phrase:
                problems.append(f"{path.relative_to(ROOT)}:{n}: {stripped}")
    return problems


def keys(path: pathlib.Path) -> set[str]:
    root = ET.parse(path).getroot()
    return {e.get("name") for e in root if e.tag in ("string", "plurals", "string-array") and e.get("translatable") != "false"}


def mismatched_keys() -> list[str]:
    base = keys(RES / "values/strings.xml")
    problems = []
    for other in sorted(RES.glob("values-*/strings.xml")):
        found = keys(other)
        for k in sorted(base - found):
            problems.append(f"{other.relative_to(ROOT)}: falta «{k}»")
        for k in sorted(found - base):
            problems.append(f"{other.relative_to(ROOT)}: «{k}» no existe en values/strings.xml")
    return problems


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    texts, missing = hardcoded_texts(), mismatched_keys()
    for p in texts:
        print("Texto fijo:", p)
    for p in missing:
        print("Clave:", p)
    if texts or missing:
        print(f"\n{len(texts)} textos fijos y {len(missing)} claves desparejadas. Ver docs/I18N.md.")
        return 1
    print("OK: sin textos fijos en la interfaz y con las mismas claves en todos los idiomas.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
