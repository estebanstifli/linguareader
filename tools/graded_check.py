"""Comprueba métricas de nivel de un libro graduado en texto plano (inglés).

Uso: python -I tools/graded_check.py <libro.txt> [--level A1|A2|B1|B2] [--original] [--show-long N]

Métricas: palabras totales (sin títulos de capítulo), frases, palabras por frase (media,
mediana, % fuera del rango objetivo), formas de pasado (verbos -ed e irregulares frecuentes),
palabras más largas y vocabulario poco frecuente para revisar a mano.
"""
import argparse
import re
import statistics
import sys

CHAPTER = re.compile(r"^(chapter\b.{0,60}|the end)$", re.IGNORECASE)
WORD = re.compile(r"[A-Za-z]+(?:'[A-Za-z]+)?")
SENTENCE_END = re.compile(r'(?<=[.!?])["”]?\s+(?=["“]?[A-Z])')

IRREGULAR_PAST = set("""
was were had did said went came saw took gave made got knew thought told found left felt
became began brought bought caught fell flew forgot heard held kept lay led lost met paid put
ran sat slept spoke stood swam threw understood woke wore won wrote ate drank drove broke chose
""".split())
# Palabras en -ed que no son pasado (o que son adjetivos habituales en A1).
ED_ALLOWED = set("red bed need needed tired hundred bored married roasted dead seed feed speed shed wed stuck".split())

LEVELS = {
    "A1": {"words": (3000, 3500), "sentence": (6, 8)},
    "A2": {"words": (4000, 5500), "sentence": (8, 12)},
    "B1": {"words": (9000, 12000), "sentence": (10, 14), "flesch": (70, 85)},
    "B2": {"words": (15000, 22000), "sentence": (12, 18), "flesch": (60, 75)},
}
# Originales sin adaptar (B2): la extensión es la de la obra; se comprueban frase y Flesch.
ORIGINAL_B2 = {"words": (15000, 50000), "sentence": (12, 20), "flesch": (60, 80)}


def syllables(word: str) -> int:
    w = word.lower().rstrip("'s")
    groups = re.findall(r"[aeiouy]+", w)
    n = len(groups)
    if w.endswith("e") and not w.endswith(("le", "ee")) and n > 1:
        n -= 1
    return max(n, 1)


def flesch(words, n_sentences) -> float:
    syl = sum(syllables(w) for w in words)
    return 206.835 - 1.015 * len(words) / n_sentences - 84.6 * syl / len(words)


def sentences_of(text: str):
    out = []
    for paragraph in re.split(r"\n\s*\n", text):
        paragraph = " ".join(paragraph.split())
        if not paragraph or CHAPTER.match(paragraph):
            continue
        out.extend(s for s in SENTENCE_END.split(paragraph) if WORD.search(s))
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("path")
    parser.add_argument("--level", choices=LEVELS, default="A1")
    parser.add_argument("--show-long", type=int, default=0)
    parser.add_argument("--original", action="store_true", help="texto original sin adaptar (B2)")
    args = parser.parse_args()

    text = open(args.path, encoding="utf-8").read()
    sentences = sentences_of(text)
    lengths = [len(WORD.findall(s)) for s in sentences]
    words = [w.lower() for s in sentences for w in WORD.findall(s)]
    target = ORIGINAL_B2 if args.original else LEVELS[args.level]
    lo, hi = target["sentence"]

    print(f"Libro: {args.path}  (objetivo {args.level})")
    print(f"Palabras: {len(words)}  objetivo {target['words'][0]}-{target['words'][1]}")
    print(f"Frases: {len(sentences)}  media {statistics.mean(lengths):.1f}  mediana {statistics.median(lengths)}")
    over = sum(1 for n in lengths if n > hi + 4)
    print(f"Frases > {hi + 4} palabras: {over} ({100 * over / len(lengths):.1f}%)  máx {max(lengths)}")
    print(f"Palabras distintas: {len(set(words))}")
    fl = flesch(words, len(sentences))
    goal = f"  objetivo {target['flesch'][0]}-{target['flesch'][1]}" if "flesch" in target else ""
    print(f"Flesch (aprox.): {fl:.0f}{goal}")

    past_irregular = [w for w in words if w in IRREGULAR_PAST]
    past_ed = [w for w in words if w.endswith("ed") and len(w) > 3 and w not in ED_ALLOWED]
    print(f"Pasados irregulares: {len(past_irregular)}  formas -ed: {len(past_ed)}")
    if args.level == "A1":
        for label, items in (("irregulares", past_irregular), ("-ed", past_ed)):
            if items:
                print(f"  revisar {label}: {sorted(set(items))}")

    long_words = sorted({w for w in words if len(w) >= 9}, key=len, reverse=True)
    print(f"Palabras de 9+ letras ({len(long_words)}): {', '.join(long_words)}")

    if args.show_long:
        print(f"\nFrases más largas:")
        for n, s in sorted(zip(lengths, sentences), reverse=True)[: args.show_long]:
            print(f"  [{n}] {s}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
