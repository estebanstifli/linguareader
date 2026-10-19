"""Convierte un libro de Standard Ebooks (HTML de una página) al formato de content/graded-books.

Uso:
  curl -s https://standardebooks.org/ebooks/<autor>/<obra>/text/single-page -o libro.html
  python -I tools/se_to_txt.py libro.html salida.txt [--only 1,3,8] [--list]

Standard Ebooks dedica sus ediciones al dominio público (CC0); el texto original debe ser de
dominio público también en la UE (autor fallecido hace más de 70 años). Solo se convierte el
cuerpo (bodymatter): cada sección/artículo con párrafos propios es un capítulo `Chapter N: Título`.
Las comillas tipográficas pasan a rectas y se eliminan las llamadas a notas.
"""
import argparse
import html.parser
import re
import sys

UNIT_TYPES = ("chapter", "short-story", "z3998:letter", "z3998:fiction", "z3998:poem")
SKIP_TAGS = {"a.noteref", "style", "script"}


class Unit:
    def __init__(self, depth, parent=None):
        self.depth = depth
        self.parent = parent
        self.titles = []
        self.paragraphs = []


class Parser(html.parser.HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.stack = []          # (tag, is_unit, in_body)
        self.units = []
        self.open_units = []
        self.body_depth = None
        self.buf = None          # texto en curso: ("title"|"p", [partes])
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        etype = a.get("epub:type", "")
        if tag in ("section", "article"):
            in_body = "bodymatter" in etype or self.body_depth is not None
            if "bodymatter" in etype and self.body_depth is None:
                self.body_depth = len(self.stack)
            if "backmatter" in etype or "frontmatter" in etype:
                in_body = False
            unit = None
            if in_body:
                unit = Unit(len(self.stack), self.open_units[-1] if self.open_units else None)
                self.units.append(unit)
                self.open_units.append(unit)
            self.stack.append((tag, unit))
            return
        self.stack.append((tag, None))
        if self.skip or (tag == "a" and "noteref" in etype) or tag in ("style", "script"):
            self.skip += 1
            return
        if not self.open_units:
            return
        if tag in ("h1", "h2", "h3", "h4", "h5", "h6") or (tag == "p" and self._in("hgroup")):
            self.buf = ("title", [])
        elif tag == "p" and self.buf is None:
            self.buf = ("p", [])
        elif tag == "br" and self.buf:
            self.buf[1].append(" ")

    def _in(self, tag):
        return any(t == tag for t, _ in self.stack[:-1])

    def handle_endtag(self, tag):
        if not self.stack:
            return
        # Cierra hasta la etiqueta correspondiente (HTML bien formado de SE: casi siempre directa).
        while self.stack:
            t, unit = self.stack.pop()
            if self.skip:
                self.skip -= 1
                if t == tag:
                    return
                continue
            if unit is not None:
                self.open_units.remove(unit)
            if self.buf and t in ("p", "h1", "h2", "h3", "h4", "h5", "h6"):
                kind, parts = self.buf
                text = clean("".join(parts))
                if text and self.open_units:
                    target = self.open_units[-1]
                    (target.titles if kind == "title" else target.paragraphs).append(text)
                self.buf = None
            if t == tag:
                return

    def handle_data(self, data):
        if self.buf is not None and not self.skip:
            self.buf[1].append(data)


def clean(s):
    s = " ".join(s.split())
    s = s.replace("“", '"').replace("”", '"').replace("‘", "'").replace("’", "'")
    s = s.replace("⁠", "").replace(" ", " ").replace("—", " - ").replace("–", "-")
    s = re.sub(r"\s+", " ", s).replace(" - ", " - ")
    return s.strip()


NUMERAL = re.compile(r"(chapter|part|book|letter)?\s*[IVXLC\d]+\.?", re.I)


def named_titles(unit):
    return [t for t in unit.titles if t and not NUMERAL.fullmatch(t)] if unit else []


def title_of(unit, n):
    # "Chapter I" / "I" + subtítulo -> se queda con el subtítulo; si solo hay número, usa el
    # título de la sección padre (p. ej. una historia partida en I, II, III) más el número.
    named = named_titles(unit)
    if not named and named_titles(unit.parent):
        named = [named_titles(unit.parent)[-1] + (f" ({unit.titles[0]})" if unit.titles else "")]
    title = ": ".join(named) if named else ""
    title = re.sub(r"^(chapter\s+[ivxlc\d]+[.:]?\s*)", "", title, flags=re.I)
    if len(title) > 60:
        title = title[:57].rsplit(" ", 1)[0] + "..."
    return f"Chapter {n}: {title}" if title else f"Chapter {n}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("html")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--only", help="capítulos a conservar (numeración de --list), p. ej. 1,3,8")
    ap.add_argument("--list", action="store_true", help="lista los capítulos detectados y sale")
    args = ap.parse_args()

    p = Parser()
    p.feed(open(args.html, encoding="utf-8").read())
    units = [u for u in p.units if u.paragraphs]
    if args.list or not args.out:
        for i, u in enumerate(units, 1):
            words = sum(len(x.split()) for x in u.paragraphs)
            print(f"{i:3d}  {words:6d} palabras  {title_of(u, i)}")
        return 0
    if args.only:
        keep = {int(x) for x in args.only.split(",")}
        units = [u for i, u in enumerate(units, 1) if i in keep]

    out = []
    for n, u in enumerate(units, 1):
        out.append(title_of(u, n))
        out.extend(u.paragraphs)
    out.append("The End")
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n\n".join(out) + "\n")
    print(f"{len(units)} capítulos -> {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
